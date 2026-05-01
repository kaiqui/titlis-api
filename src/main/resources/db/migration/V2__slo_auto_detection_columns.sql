-- ================================================================
-- V2: Colunas de rastreabilidade de auto-criação de SLOs
-- Adicionadas pelo ScorecardController quando ENABLE_AUTO_SLO_CREATION=true.
-- ================================================================

ALTER TABLE titlis_oltp.slo_configs
    ADD COLUMN IF NOT EXISTS auto_created           BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS source_deployment_uid  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS source_deployment_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS source_namespace       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS dd_env                 VARCHAR(100);

COMMENT ON COLUMN titlis_oltp.slo_configs.auto_created           IS 'true quando criado automaticamente pelo ScorecardController';
COMMENT ON COLUMN titlis_oltp.slo_configs.source_deployment_uid  IS 'UID do Deployment K8s que originou a auto-criação';
COMMENT ON COLUMN titlis_oltp.slo_configs.source_deployment_name IS 'Nome do Deployment K8s que originou a auto-criação';
COMMENT ON COLUMN titlis_oltp.slo_configs.source_namespace       IS 'Namespace do Deployment de origem';
COMMENT ON COLUMN titlis_oltp.slo_configs.dd_env                 IS 'Ambiente Datadog extraído de tags.datadoghq.com/env';

CREATE INDEX IF NOT EXISTS idx_slo_auto_created ON titlis_oltp.slo_configs (auto_created)
    WHERE auto_created = TRUE;

CREATE INDEX IF NOT EXISTS idx_slo_source_uid ON titlis_oltp.slo_configs (source_deployment_uid)
    WHERE source_deployment_uid IS NOT NULL;
