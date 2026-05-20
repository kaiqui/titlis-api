-- ================================================================
-- V7: Tabelas de campanhas de PR em massa (bulk PR campaign).
--
-- Responsável: titlis-api + titlis-prbot
-- Contexto:
--   - titlis-prbot orquestra workflows Temporal de criação de PRs em lote
--   - titlis-api persiste estado das campanhas e eventos para o frontend
--   - pr_campaign_items e pr_campaign_env_steps são escritos pelo prbot
--     via titlis-api (rota interna /v1/internal/prbot/*)
--
-- Padrão de escrita:
--   - pr_campaigns          : INSERT + UPDATE de status (CampaignRepository)
--   - pr_campaign_items     : INSERT + UPDATE pelo prbot
--   - pr_campaign_env_steps : INSERT + UPDATE pelo prbot
--   - event_store_pr_campaign : INSERT append-only (CampaignRepository.appendEvent)
-- ================================================================

-- ----------------------------------------------------------------
-- titlis_oltp.pr_campaigns
-- Registro mestre de cada campanha de PR em lote.
-- pr_campaign_id: gerado pelo prbot no formato "cmp_01H..." (ULID-like).
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS titlis_oltp.pr_campaigns (
    pr_campaign_id  VARCHAR(255) NOT NULL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    workflow_id     VARCHAR(255) NOT NULL,
    actor_user_id   UUID,
    actor_email     VARCHAR(320),
    trigger_source  VARCHAR(50)  NOT NULL,
    rule_id         VARCHAR(50),
    title           TEXT         NOT NULL,
    description     TEXT,
    status          VARCHAR(50)  NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    total_items     INTEGER      NOT NULL,
    succeeded_items INTEGER      NOT NULL DEFAULT 0,
    failed_items    INTEGER      NOT NULL DEFAULT 0,
    skipped_items   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pr_campaigns_tenant
    ON titlis_oltp.pr_campaigns (tenant_id);

CREATE INDEX IF NOT EXISTS idx_pr_campaigns_tenant_created
    ON titlis_oltp.pr_campaigns (tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_pr_campaigns_status
    ON titlis_oltp.pr_campaigns (tenant_id, status)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');

-- ----------------------------------------------------------------
-- titlis_oltp.pr_campaign_items
-- Um item por workload/deployment dentro de uma campanha.
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS titlis_oltp.pr_campaign_items (
    pr_campaign_item_id   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pr_campaign_id        VARCHAR(255) NOT NULL REFERENCES titlis_oltp.pr_campaigns(pr_campaign_id),
    tenant_id             BIGINT       NOT NULL,
    workload_id           VARCHAR(255) NOT NULL,
    cluster_name          VARCHAR(255) NOT NULL,
    namespace             VARCHAR(255) NOT NULL,
    deployment_name       VARCHAR(255) NOT NULL,
    repo_url              TEXT         NOT NULL,
    recommendation_source VARCHAR(100) NOT NULL,
    cascade_up_to         VARCHAR(20)  NOT NULL,
    status                VARCHAR(50)  NOT NULL,
    error_message         TEXT,
    started_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_pr_campaign_items_campaign
    ON titlis_oltp.pr_campaign_items (pr_campaign_id);

CREATE INDEX IF NOT EXISTS idx_pr_campaign_items_tenant
    ON titlis_oltp.pr_campaign_items (tenant_id);

-- ----------------------------------------------------------------
-- titlis_oltp.pr_campaign_env_steps
-- Um passo por ambiente (dev → staging → prod) de cada item.
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS titlis_oltp.pr_campaign_env_steps (
    pr_campaign_env_step_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pr_campaign_item_id     BIGINT       NOT NULL REFERENCES titlis_oltp.pr_campaign_items(pr_campaign_item_id),
    environment             VARCHAR(20)  NOT NULL,
    manifest_path           TEXT         NOT NULL,
    branch_name             VARCHAR(255),
    pr_number               INTEGER,
    pr_url                  TEXT,
    status                  VARCHAR(50)  NOT NULL,
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_pr_campaign_env_steps_item
    ON titlis_oltp.pr_campaign_env_steps (pr_campaign_item_id);

-- ----------------------------------------------------------------
-- titlis_audit.event_store_pr_campaign
-- Log de eventos de cada campanha (append-only, particionável por created_at).
-- campaign_id sem FK — sobrevive mesmo se a campanha for removida.
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS titlis_audit.event_store_pr_campaign (
    event_store_pr_campaign_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id                VARCHAR(255) NOT NULL,
    tenant_id                  BIGINT       NOT NULL,
    event_type                 VARCHAR(100) NOT NULL,
    payload                    JSONB        NOT NULL,
    occurred_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_event_store_pr_campaign_campaign_time
    ON titlis_audit.event_store_pr_campaign (campaign_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_event_store_pr_campaign_tenant
    ON titlis_audit.event_store_pr_campaign (tenant_id);

CREATE INDEX IF NOT EXISTS idx_event_store_pr_campaign_created
    ON titlis_audit.event_store_pr_campaign (created_at DESC);

-- ----------------------------------------------------------------
-- Cleanup: substitui idx_clusters_tenant (single-col) pelo índice
-- composto (cluster_name, tenant_id) para suportar o lookup do
-- ensureCluster() e o UNIQUE constraint multi-tenant.
-- Não executado pelo terceiro_script.sql (estava no bloco separado).
-- ----------------------------------------------------------------
DROP INDEX IF EXISTS titlis_oltp.idx_clusters_tenant;

CREATE INDEX IF NOT EXISTS idx_clusters_cluster_tenant
    ON titlis_oltp.clusters (cluster_name, tenant_id);
