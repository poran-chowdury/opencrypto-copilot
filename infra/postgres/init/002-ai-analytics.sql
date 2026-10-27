-- Optional AI/analytics schema for OpenCrypto Copilot.
-- Applied automatically on first start of the pgvector/pgvector:pg16 image (docker compose).
-- Existing volumes are not re-initialised: run `docker compose down -v` to apply changes.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_document (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    source_uri TEXT NOT NULL,
    source_type TEXT NOT NULL,
    version_label TEXT,
    published_at TIMESTAMPTZ,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    access_scope TEXT NOT NULL DEFAULT 'PUBLIC',
    content_hash TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'REJECTED'))
);

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    page_number INTEGER,
    section_label TEXT,
    chunk_text TEXT NOT NULL,
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('english', chunk_text)) STORED,
    -- Choose the vector dimension to match the embedding model before applying in production.
    embedding vector(1536),
    UNIQUE (document_id, chunk_index)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_search ON knowledge_chunk USING GIN(search_vector);
CREATE INDEX IF NOT EXISTS idx_knowledge_document_status ON knowledge_document(status, published_at DESC);

CREATE TABLE IF NOT EXISTS market_event (
    id UUID PRIMARY KEY,
    asset_symbol TEXT,
    event_type TEXT NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    source_name TEXT NOT NULL,
    source_uri TEXT NOT NULL,
    title TEXT NOT NULL,
    summary TEXT,
    ingestion_status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ingestion_status IN ('PENDING', 'PROCESSED', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_market_event_asset_time ON market_event(asset_symbol, event_time DESC);

CREATE TABLE IF NOT EXISTS ai_evaluation_run (
    id UUID PRIMARY KEY,
    task_name TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    dataset_version TEXT NOT NULL,
    model_provider TEXT NOT NULL,
    model_name TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    aggregate_metrics JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS ai_evaluation_case_result (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES ai_evaluation_run(id) ON DELETE CASCADE,
    case_id TEXT NOT NULL,
    response_json JSONB,
    expected_json JSONB,
    schema_valid BOOLEAN,
    correctness_score NUMERIC(5,4),
    citation_valid BOOLEAN,
    latency_ms BIGINT,
    input_tokens INTEGER,
    output_tokens INTEGER,
    estimated_cost_usd NUMERIC(12,8),
    safety_flags JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_message TEXT,
    UNIQUE (run_id, case_id)
);

CREATE TABLE IF NOT EXISTS forecast_experiment (
    id UUID PRIMARY KEY,
    model_name TEXT NOT NULL,
    model_version TEXT NOT NULL,
    target_name TEXT NOT NULL,
    feature_window JSONB NOT NULL,
    train_start TIMESTAMPTZ NOT NULL,
    train_end TIMESTAMPTZ NOT NULL,
    test_start TIMESTAMPTZ NOT NULL,
    test_end TIMESTAMPTZ NOT NULL,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS forecast_observation (
    id UUID PRIMARY KEY,
    experiment_id UUID NOT NULL REFERENCES forecast_experiment(id) ON DELETE CASCADE,
    forecast_for TIMESTAMPTZ NOT NULL,
    predicted_value NUMERIC(24,10) NOT NULL,
    actual_value NUMERIC(24,10),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, forecast_for)
);
