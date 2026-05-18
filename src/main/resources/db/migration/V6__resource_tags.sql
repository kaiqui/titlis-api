-- ================================================================
-- V6: Tabela genérica de tags por recurso (Datadog-like).
-- resource_type: 'cluster' | 'namespace' | 'workload' | 'tenant' | 'slo'
-- resource_id: PK da entidade correspondente — sem FK (polimórfico);
--              existência validada na aplicação antes do INSERT.
-- Formato de tag recomendado: "chave:valor" (ex: env:dev, team:backend).
-- Políticas de scoring por tag ficam em titlis_config.tag_rule_policies (scoreops).
-- ================================================================

CREATE TABLE IF NOT EXISTS titlis_oltp.resource_tags (
    resource_tag_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    resource_type   VARCHAR(50)  NOT NULL CHECK (resource_type IN ('cluster','namespace','workload','tenant','slo')),
    resource_id     BIGINT       NOT NULL,
    tag             VARCHAR(100) NOT NULL,
    created_by      VARCHAR(256),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, resource_type, resource_id, tag)
);

-- Lookup principal: todas as tags de um recurso específico
CREATE INDEX IF NOT EXISTS idx_resource_tags_lookup
    ON titlis_oltp.resource_tags (tenant_id, resource_type, resource_id);

-- Lookup inverso: todos os recursos com uma tag específica (ex: "env:dev")
CREATE INDEX IF NOT EXISTS idx_resource_tags_by_tag
    ON titlis_oltp.resource_tags (tenant_id, tag);
