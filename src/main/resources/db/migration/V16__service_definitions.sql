-- Fase 2: Service.yaml + Team Ownership
-- service_definitions: catálogo de serviços declarados via .titlis/service.yaml
CREATE TABLE IF NOT EXISTS titlis_oltp.service_definitions (
    service_definition_id  BIGSERIAL PRIMARY KEY,
    tenant_id              BIGINT NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    service_name           TEXT NOT NULL,
    team                   TEXT NOT NULL,
    tier                   TEXT,
    description            TEXT,
    repo_url               TEXT,
    raw_yaml               TEXT,
    synced_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, service_name)
);

-- workloads ganha coluna team (populada pelo service.yaml sync; distinta de owner_team)
ALTER TABLE titlis_oltp.workloads ADD COLUMN IF NOT EXISTS team TEXT;

-- Índices de suporte
CREATE INDEX IF NOT EXISTS idx_service_definitions_tenant_team ON titlis_oltp.service_definitions (tenant_id, team);
CREATE INDEX IF NOT EXISTS idx_workloads_team ON titlis_oltp.workloads (team) WHERE team IS NOT NULL;
