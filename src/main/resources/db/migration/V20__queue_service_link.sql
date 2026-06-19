-- Queue ↔ Service linkage — de-para automático fila → service_definition.
-- Regra 16 (DBA): PK <tabela>_id; FK = nome do PK referenciado.

-- Padrões declarados por serviço via .titlis/service.yaml (spec.integrations.queues).
-- Re-sincronizados a cada upsert do service.yaml (delete por service_definition_id + insert).
CREATE TABLE IF NOT EXISTS titlis_oltp.service_queue_patterns (
    service_queue_pattern_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    service_definition_id    BIGINT       NOT NULL REFERENCES titlis_oltp.service_definitions(service_definition_id),
    provider                 VARCHAR(50)  NOT NULL DEFAULT 'gcp_pubsub',
    pattern                  VARCHAR(500) NOT NULL,
    match_type               VARCHAR(20)  NOT NULL DEFAULT 'glob',          -- exact | prefix | glob
    match_field              VARCHAR(20)  NOT NULL DEFAULT 'display_name',  -- display_name | external_id | topic_id
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (service_definition_id, provider, pattern, match_field)
);
CREATE INDEX IF NOT EXISTS idx_sqp_tenant_provider ON titlis_oltp.service_queue_patterns (tenant_id, provider);

-- queues ganha o vínculo + origem + labels (hoje os labels chegam no payload e são descartados).
ALTER TABLE titlis_oltp.queues
    ADD COLUMN IF NOT EXISTS service_definition_id BIGINT REFERENCES titlis_oltp.service_definitions(service_definition_id),
    ADD COLUMN IF NOT EXISTS link_source     VARCHAR(20),   -- pattern | manual | suggested | env
    ADD COLUMN IF NOT EXISTS link_confidence NUMERIC(4,3),
    ADD COLUMN IF NOT EXISTS linked_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS labels          JSONB;

CREATE INDEX IF NOT EXISTS idx_queues_service_def ON titlis_oltp.queues (service_definition_id);

-- Sugestões pendentes de confirmação humana (1 fila pode ter N candidatos).
CREATE TABLE IF NOT EXISTS titlis_oltp.queue_link_suggestions (
    queue_link_suggestion_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    queue_id                 BIGINT       NOT NULL REFERENCES titlis_oltp.queues(queue_id),
    service_definition_id    BIGINT       NOT NULL REFERENCES titlis_oltp.service_definitions(service_definition_id),
    confidence               NUMERIC(4,3) NOT NULL,
    source                   VARCHAR(20)  NOT NULL,  -- name | env
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (queue_id, service_definition_id, source)
);
CREATE INDEX IF NOT EXISTS idx_qls_tenant_queue ON titlis_oltp.queue_link_suggestions (tenant_id, queue_id);
