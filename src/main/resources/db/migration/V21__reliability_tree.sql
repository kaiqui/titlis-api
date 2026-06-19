-- Termômetro de Confiabilidade — topo da árvore (product) + folha workload→service.
-- product e o vínculo de workload vêm do .titlis/service.yaml (mesma porta do team/integrations).

ALTER TABLE titlis_oltp.service_definitions
    ADD COLUMN IF NOT EXISTS product TEXT;

ALTER TABLE titlis_oltp.workloads
    ADD COLUMN IF NOT EXISTS service_definition_id BIGINT
        REFERENCES titlis_oltp.service_definitions(service_definition_id);

CREATE INDEX IF NOT EXISTS idx_workloads_service_def    ON titlis_oltp.workloads (service_definition_id);
CREATE INDEX IF NOT EXISTS idx_service_def_tenant_product ON titlis_oltp.service_definitions (tenant_id, product);
