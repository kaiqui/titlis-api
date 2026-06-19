-- Fase 1: Cost Service
-- gcp_billing_configs: credenciais GCP Billing por tenant (write-only na UI)
CREATE TABLE IF NOT EXISTS titlis_oltp.gcp_billing_configs (
    gcp_billing_config_id  BIGSERIAL PRIMARY KEY,
    tenant_id              BIGINT NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    billing_account_id     TEXT NOT NULL,
    project_id             TEXT NOT NULL,
    bigquery_dataset       TEXT NOT NULL,
    bigquery_location      TEXT NOT NULL DEFAULT 'US',
    credentials_enc        TEXT NOT NULL,
    last_collection_at     TIMESTAMPTZ,
    workloads_covered      INT DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id)
);

-- workload_cost_metrics: custo alocado por workload (append-only)
CREATE TABLE IF NOT EXISTS titlis_ts.workload_cost_metrics (
    workload_cost_metric_id BIGSERIAL PRIMARY KEY,
    workload_id             BIGINT NOT NULL,
    tenant_id               BIGINT NOT NULL,
    namespace               TEXT NOT NULL,
    cluster_name            TEXT NOT NULL,
    workload_name           TEXT NOT NULL,
    team                    TEXT,
    collected_date          DATE NOT NULL,
    provider                TEXT NOT NULL,
    currency                TEXT NOT NULL DEFAULT 'USD',
    compute_cost            NUMERIC(12,6) NOT NULL DEFAULT 0,
    storage_cost            NUMERIC(12,6) NOT NULL DEFAULT 0,
    network_cost            NUMERIC(12,6) NOT NULL DEFAULT 0,
    total_cost              NUMERIC(12,6) NOT NULL DEFAULT 0,
    allocation_method       TEXT NOT NULL DEFAULT 'proportional',
    cost_breakdown          JSONB NOT NULL DEFAULT '{}',
    collected_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workload_id, collected_date, provider)
);

-- namespace_cost_metrics: custo alocado por namespace (append-only)
CREATE TABLE IF NOT EXISTS titlis_ts.namespace_cost_metrics (
    namespace_cost_metric_id BIGSERIAL PRIMARY KEY,
    tenant_id                BIGINT NOT NULL,
    namespace                TEXT NOT NULL,
    cluster_name             TEXT NOT NULL,
    collected_date           DATE NOT NULL,
    provider                 TEXT NOT NULL,
    currency                 TEXT NOT NULL DEFAULT 'USD',
    total_cost               NUMERIC(12,6) NOT NULL DEFAULT 0,
    raw_cluster_cost         NUMERIC(12,6) NOT NULL DEFAULT 0,
    workload_count           INT NOT NULL DEFAULT 0,
    allocation_method        TEXT NOT NULL DEFAULT 'proportional',
    collected_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, namespace, cluster_name, collected_date, provider)
);

-- Índices para queries de custo
CREATE INDEX IF NOT EXISTS idx_wcm_tenant_date ON titlis_ts.workload_cost_metrics (tenant_id, collected_date DESC);
CREATE INDEX IF NOT EXISTS idx_wcm_workload_date ON titlis_ts.workload_cost_metrics (workload_id, collected_date DESC);
CREATE INDEX IF NOT EXISTS idx_wcm_tenant_team_date ON titlis_ts.workload_cost_metrics (tenant_id, team, collected_date DESC);
CREATE INDEX IF NOT EXISTS idx_ncm_tenant_cluster_date ON titlis_ts.namespace_cost_metrics (tenant_id, cluster_name, collected_date DESC);
