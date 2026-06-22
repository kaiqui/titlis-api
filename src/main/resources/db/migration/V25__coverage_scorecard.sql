-- D5 — Observability Intelligence Platform / Coverage scorecard
-- (docs/todo/observability-intelligence-d5-downstream-plan.md)
-- Resultado do engine "coverage" do titlis-scoreops (scorecard personalizado por serviço, gerado
-- por natureza). Aditivo. Regra DBA 16: PK <tabela>_id.

CREATE TABLE IF NOT EXISTS titlis_oltp.coverage_scorecard (
    coverage_scorecard_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    workload_uid  TEXT   NOT NULL,
    service_name  TEXT,
    cluster_name  TEXT,
    trust_score   NUMERIC(5,2),            -- 0–100 sobre findings avaliáveis (não-N/A)
    coverage_json JSONB NOT NULL DEFAULT '[]',  -- cobertura por dimensão
    findings_json JSONB NOT NULL DEFAULT '[]',  -- itens personalizados (pass/fail/na)
    evaluated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, workload_uid)
);
CREATE INDEX IF NOT EXISTS idx_coverage_scorecard_tenant ON titlis_oltp.coverage_scorecard (tenant_id);
