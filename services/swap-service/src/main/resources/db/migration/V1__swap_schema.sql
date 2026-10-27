-- swap-service: quotes and the swap state machine (CREATED -> PENDING -> COMPLETED | FAILED).

CREATE TABLE quote (
    id               UUID PRIMARY KEY,
    user_id          VARCHAR(64)     NOT NULL,
    from_symbol      VARCHAR(16)     NOT NULL,
    to_symbol        VARCHAR(16)     NOT NULL,
    input_amount     NUMERIC(38, 18) NOT NULL CHECK (input_amount > 0),
    fee_amount       NUMERIC(38, 18) NOT NULL CHECK (fee_amount >= 0),
    fee_rate_bps     INT             NOT NULL,
    rate             NUMERIC(38, 18) NOT NULL CHECK (rate > 0),
    estimated_output NUMERIC(38, 18) NOT NULL CHECK (estimated_output > 0),
    minimum_received NUMERIC(38, 18) NOT NULL CHECK (minimum_received > 0),
    slippage_bps     INT             NOT NULL CHECK (slippage_bps BETWEEN 1 AND 500),
    from_price_usd   NUMERIC(38, 18) NOT NULL,
    to_price_usd     NUMERIC(38, 18) NOT NULL,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ     NOT NULL,
    CHECK (from_symbol <> to_symbol)
);
CREATE INDEX idx_quote_expires ON quote (expires_at);

CREATE TABLE swap_order (
    id                     UUID PRIMARY KEY,
    user_id                VARCHAR(64)     NOT NULL,
    quote_id               UUID            NOT NULL UNIQUE REFERENCES quote (id),   -- a quote executes at most once
    idempotency_key        VARCHAR(64)     NOT NULL,
    from_symbol            VARCHAR(16)     NOT NULL,
    to_symbol              VARCHAR(16)     NOT NULL,
    input_amount           NUMERIC(38, 18) NOT NULL,
    fee_amount             NUMERIC(38, 18) NOT NULL,
    estimated_output       NUMERIC(38, 18) NOT NULL,
    minimum_received       NUMERIC(38, 18) NOT NULL,
    output_amount          NUMERIC(38, 18),
    execution_rate         NUMERIC(38, 18),
    exec_from_price_usd    NUMERIC(38, 18),
    exec_to_price_usd      NUMERIC(38, 18),
    state                  VARCHAR(16)     NOT NULL CHECK (state IN ('CREATED', 'PENDING', 'COMPLETED', 'FAILED')),
    failure_reason         VARCHAR(64),
    pending_failure_reason VARCHAR(64),   -- why we asked the wallet to release funds; final once confirmed
    ledger_entry_id        VARCHAR(64),
    release_requested_at   TIMESTAMPTZ,
    created_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX idx_swap_user_time ON swap_order (user_id, created_at DESC);
CREATE INDEX idx_swap_open ON swap_order (updated_at) WHERE state IN ('CREATED', 'PENDING');

CREATE TABLE swap_state_history (
    id             BIGSERIAL PRIMARY KEY,
    swap_id        UUID        NOT NULL REFERENCES swap_order (id),
    from_state     VARCHAR(16),
    to_state       VARCHAR(16) NOT NULL,
    note           TEXT        NOT NULL,
    correlation_id VARCHAR(64),
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_history_swap ON swap_state_history (swap_id, id);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
