-- D0 — Observability Intelligence Platform / Discovery Engine
-- (docs/todo/observability-intelligence-platform-plan.md)
-- Grafo de ativos descobertos pelo titlis-operator-go (K8s nativo + providers externos).
-- Aditivo e isolado: nada existente é alterado; populado apenas quando ENABLE_DISCOVERY=true.
-- Regra DBA 16: PK <tabela>_id; FK = nome do PK referenciado.
-- A relação é auto-referencial (source/target) → prefixo de papel + sufixo = PK referenciado.

CREATE TABLE IF NOT EXISTS titlis_oltp.discovered_asset (
    discovered_asset_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    provider      TEXT    NOT NULL,            -- kubernetes | datadog | ...
    kind          TEXT    NOT NULL,            -- deployment | service | dd_monitor | ...
    external_id   TEXT    NOT NULL,            -- chave natural por provider (k8s UID, dd id, ...)
    name          TEXT    NOT NULL,
    namespace     TEXT,
    cluster_name  TEXT,
    tags          JSONB   NOT NULL DEFAULT '{}',
    attributes    JSONB   NOT NULL DEFAULT '{}',
    is_active     BOOLEAN NOT NULL DEFAULT true,   -- soft-delete (regra 13): nunca DELETE
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, provider, external_id)
);
CREATE INDEX IF NOT EXISTS idx_discovered_asset_tenant  ON titlis_oltp.discovered_asset (tenant_id);
CREATE INDEX IF NOT EXISTS idx_discovered_asset_kind    ON titlis_oltp.discovered_asset (tenant_id, provider, kind);
CREATE INDEX IF NOT EXISTS idx_discovered_asset_cluster ON titlis_oltp.discovered_asset (tenant_id, cluster_name);

CREATE TABLE IF NOT EXISTS titlis_oltp.asset_relation (
    asset_relation_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                  BIGINT  NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    source_discovered_asset_id BIGINT  NOT NULL REFERENCES titlis_oltp.discovered_asset(discovered_asset_id),
    target_discovered_asset_id BIGINT  NOT NULL REFERENCES titlis_oltp.discovered_asset(discovered_asset_id),
    relation_type              TEXT    NOT NULL,   -- selects | routes_to | scaled_by | uses_config | ...
    is_active                  BOOLEAN NOT NULL DEFAULT true,
    last_seen_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, source_discovered_asset_id, target_discovered_asset_id, relation_type)
);
CREATE INDEX IF NOT EXISTS idx_asset_relation_source ON titlis_oltp.asset_relation (source_discovered_asset_id);
CREATE INDEX IF NOT EXISTS idx_asset_relation_target ON titlis_oltp.asset_relation (target_discovered_asset_id);
