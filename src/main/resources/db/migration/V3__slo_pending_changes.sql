-- ================================================================
-- V3: Fila de mudanças de threshold de SLO propostas pelo titlis-ai
-- ================================================================

CREATE TABLE titlis_oltp.slo_config_pending_changes (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES titlis_oltp.tenants(tenant_id) ON DELETE CASCADE,
    slo_config_name TEXT        NOT NULL,
    namespace       TEXT        NOT NULL,
    field           TEXT        NOT NULL,
    old_value       TEXT        NOT NULL,
    new_value       TEXT        NOT NULL,
    requested_by    TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'pending',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    applied_at      TIMESTAMPTZ,
    error           TEXT,
    CONSTRAINT chk_slo_pending_field  CHECK (field  IN ('target', 'warning', 'timeframe')),
    CONSTRAINT chk_slo_pending_status CHECK (status IN ('pending', 'applied', 'failed', 'cancelled'))
);

COMMENT ON TABLE  titlis_oltp.slo_config_pending_changes IS 'Fila de mudanças de threshold de SLO aguardando aplicação pelo operator';
COMMENT ON COLUMN titlis_oltp.slo_config_pending_changes.tenant_id      IS 'Garante isolamento no polling do operator';
COMMENT ON COLUMN titlis_oltp.slo_config_pending_changes.field          IS 'Campo do spec: target, warning ou timeframe';
COMMENT ON COLUMN titlis_oltp.slo_config_pending_changes.old_value      IS 'Valor atual — para auditoria e rollback manual';
COMMENT ON COLUMN titlis_oltp.slo_config_pending_changes.requested_by   IS 'Ator: titlis-ai ou user:{user_id}';
COMMENT ON COLUMN titlis_oltp.slo_config_pending_changes.status         IS 'pending | applied | failed | cancelled';

CREATE INDEX idx_slo_pending_tenant_status
    ON titlis_oltp.slo_config_pending_changes (tenant_id, status)
    WHERE status = 'pending';

CREATE INDEX idx_slo_pending_created
    ON titlis_oltp.slo_config_pending_changes (created_at DESC);
