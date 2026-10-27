-- asset-service owns the asset catalogue, latest market data, price history and market events.

CREATE TABLE asset (
    symbol                VARCHAR(16) PRIMARY KEY,
    coingecko_id          VARCHAR(64) UNIQUE,
    name                  VARCHAR(64)  NOT NULL,
    kind                  VARCHAR(16)  NOT NULL CHECK (kind IN ('FIAT', 'CRYPTO', 'STABLECOIN')),
    decimals              INT          NOT NULL CHECK (decimals BETWEEN 0 AND 18),
    image_url             TEXT,
    description           TEXT         NOT NULL DEFAULT '',
    networks              JSONB        NOT NULL DEFAULT '[]',
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    -- parameters for the simulated market provider
    sim_initial_price     NUMERIC(38, 18) NOT NULL,
    sim_annual_volatility NUMERIC(8, 4)   NOT NULL DEFAULT 0,
    sim_annual_drift      NUMERIC(8, 4)   NOT NULL DEFAULT 0,
    -- latest market data, denormalised so list/sort/filter is a single indexed query
    price_usd             NUMERIC(38, 18),
    market_cap_usd        NUMERIC(38, 2),
    volume_24h_usd        NUMERIC(38, 2),
    change_24h_pct        NUMERIC(12, 4),
    market_cap_rank       INT,
    price_source          VARCHAR(32),
    price_updated_at      TIMESTAMPTZ
);
CREATE INDEX idx_asset_market_cap ON asset (market_cap_usd DESC NULLS LAST);

-- Price history. One row per asset per capture; all assets captured together share a timestamp,
-- which keeps histories time-aligned for portfolio valuation.
CREATE TABLE price_snapshot (
    symbol      VARCHAR(16)     NOT NULL REFERENCES asset (symbol),
    captured_at TIMESTAMPTZ     NOT NULL,
    price_usd   NUMERIC(38, 18) NOT NULL CHECK (price_usd >= 0),
    source      VARCHAR(32)     NOT NULL,
    PRIMARY KEY (symbol, captured_at)
);
CREATE INDEX idx_price_snapshot_time ON price_snapshot (captured_at);

CREATE TABLE market_event (
    id          UUID PRIMARY KEY,
    symbol      VARCHAR(16)    NOT NULL REFERENCES asset (symbol),
    shock_pct   NUMERIC(10, 4) NOT NULL,
    headline    TEXT           NOT NULL,
    source      VARCHAR(32)    NOT NULL,
    occurred_at TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_market_event_time ON market_event (occurred_at DESC);

-- Audit of every market-data synchronisation, for the status page and debugging.
CREATE TABLE sync_run (
    id             BIGSERIAL PRIMARY KEY,
    provider       VARCHAR(32) NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'OK', 'FAILED')),
    assets_updated INT         NOT NULL DEFAULT 0,
    error          TEXT
);
CREATE INDEX idx_sync_run_started ON sync_run (started_at DESC);

-- ShedLock: makes scheduled jobs run on one instance at a time when the service is scaled out.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
