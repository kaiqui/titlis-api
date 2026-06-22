-- Fase 0 — service-yaml-discovery-worker (docs/todo/service-yaml-discovery-worker-plan.md)
-- Base de dados para descoberta zero-touch de .titlis/service.yaml + correlação por pattern.
-- Aditivo: não altera comportamento existente (correlação por pattern entra na Fase 1).

-- service_definitions ganha gitops/remediation (consumidos pela remediação) + lifecycle.
ALTER TABLE titlis_oltp.service_definitions
    ADD COLUMN IF NOT EXISTS gitops_paths JSONB,
    ADD COLUMN IF NOT EXISTS remediation  JSONB,
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS is_stale     BOOLEAN NOT NULL DEFAULT false;

-- Padrões de correlação workload (namespaces + name_pattern regex).
-- Espelha titlis_oltp.service_queue_patterns. Populado na Fase 1; criado agora.
-- Regra DBA 16: PK <tabela>_id; FK = nome do PK referenciado.
CREATE TABLE IF NOT EXISTS titlis_oltp.service_workload_patterns (
    service_workload_pattern_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    service_definition_id BIGINT NOT NULL REFERENCES titlis_oltp.service_definitions(service_definition_id),
    namespaces            JSONB,                 -- ["orders-prod", ...]; NULL/[] = qualquer
    name_pattern          TEXT NOT NULL,         -- regex contra workload_name
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (service_definition_id, name_pattern)
);
CREATE INDEX IF NOT EXISTS idx_swp_tenant ON titlis_oltp.service_workload_patterns (tenant_id);
