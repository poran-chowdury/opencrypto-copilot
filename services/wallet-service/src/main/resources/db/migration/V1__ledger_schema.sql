-- wallet-service: double-entry ledger for simulated balances.
-- The invariants live in the database, so no code path (or bug) can break them:
--   1. every journal entry sums to zero per asset          (deferred constraint trigger)
--   2. user balances never go negative                     (CHECK constraint)
--   3. a posting's asset matches its account's asset       (composite foreign key)
--   4. journal entries, postings and audit rows are immutable (triggers)

CREATE TABLE account (
    id             BIGSERIAL PRIMARY KEY,
    owner_type     VARCHAR(8)  NOT NULL CHECK (owner_type IN ('USER', 'SYSTEM')),
    owner_id       VARCHAR(64) NOT NULL,
    bucket         VARCHAR(16) NOT NULL CHECK (bucket IN ('AVAILABLE', 'HELD', 'MAIN')),
    asset          VARCHAR(16) NOT NULL,
    balance        NUMERIC(38, 18) NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_type, owner_id, bucket, asset),
    UNIQUE (id, asset),
    CONSTRAINT balance_non_negative CHECK (allow_negative OR balance >= 0)
);
CREATE INDEX idx_account_owner ON account (owner_type, owner_id);

CREATE TABLE journal_entry (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    request_hash    VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    type            VARCHAR(32)  NOT NULL,
    reference_id    VARCHAR(128),
    description     TEXT         NOT NULL,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_journal_user_time ON journal_entry (user_id, created_at DESC);

CREATE TABLE posting (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   UUID            NOT NULL REFERENCES journal_entry (id),
    account_id BIGINT          NOT NULL,
    asset      VARCHAR(16)     NOT NULL,
    amount     NUMERIC(38, 18) NOT NULL CHECK (amount <> 0),
    FOREIGN KEY (account_id, asset) REFERENCES account (id, asset)
);
CREATE INDEX idx_posting_entry ON posting (entry_id);
CREATE INDEX idx_posting_account ON posting (account_id);

-- Checked at COMMIT (DEFERRABLE INITIALLY DEFERRED): all postings of an entry are inserted first.
CREATE FUNCTION check_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM posting WHERE entry_id = NEW.entry_id GROUP BY asset HAVING sum(amount) <> 0) THEN
        RAISE EXCEPTION 'journal entry % is not balanced', NEW.entry_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER posting_balanced AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();

CREATE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER journal_entry_append_only BEFORE UPDATE OR DELETE ON journal_entry FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER posting_append_only BEFORE UPDATE OR DELETE ON posting FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- Funds earmarked for an in-flight swap. The swap id is the key, which makes every saga step idempotent.
CREATE TABLE reservation (
    swap_id    VARCHAR(64) PRIMARY KEY,
    user_id    VARCHAR(64)     NOT NULL,
    asset      VARCHAR(16)     NOT NULL,
    amount     NUMERIC(38, 18) NOT NULL,
    status     VARCHAR(16)     NOT NULL CHECK (status IN ('HELD', 'CAPTURED', 'RELEASED', 'REJECTED')),
    reason     TEXT,
    created_at TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ     NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservation_user ON reservation (user_id);

-- Average-cost positions for profit/loss. Derived data (not part of the ledger) updated in the same transaction.
CREATE TABLE position (
    user_id          VARCHAR(64)     NOT NULL,
    asset            VARCHAR(16)     NOT NULL,
    quantity         NUMERIC(38, 18) NOT NULL DEFAULT 0,
    cost_basis_usd   NUMERIC(38, 10) NOT NULL DEFAULT 0,
    realized_pnl_usd NUMERIC(38, 10) NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, asset)
);

CREATE TABLE audit_log (
    id             BIGSERIAL PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor          VARCHAR(64) NOT NULL,
    action         VARCHAR(64) NOT NULL,
    entity_type    VARCHAR(32) NOT NULL,
    entity_id      VARCHAR(128) NOT NULL,
    user_id        VARCHAR(64),
    details        JSONB       NOT NULL DEFAULT '{}',
    correlation_id VARCHAR(64)
);
CREATE INDEX idx_audit_user_time ON audit_log (user_id, occurred_at DESC);
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TABLE reconciliation_run (
    id                BIGSERIAL PRIMARY KEY,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ NOT NULL,
    accounts_checked  INT         NOT NULL,
    balance_mismatches INT        NOT NULL,
    unbalanced_assets INT         NOT NULL,
    hold_mismatches   INT         NOT NULL,
    status            VARCHAR(16) NOT NULL CHECK (status IN ('OK', 'MISMATCH')),
    details           JSONB       NOT NULL DEFAULT '[]'
);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
