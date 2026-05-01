-- ================================================================
-- V1: Schema inicial do Titlis
-- Inclui todos os schemas, enums, tabelas OLTP, audit, time-series,
-- views e índices de performance.
-- ================================================================

-- ================================================================
-- SCHEMAS
-- ================================================================
CREATE SCHEMA IF NOT EXISTS titlis_oltp;
CREATE SCHEMA IF NOT EXISTS titlis_audit;
CREATE SCHEMA IF NOT EXISTS titlis_ts;

-- Permissões para o usuário da aplicação (SELECT, INSERT, UPDATE apenas).
-- DEFAULT PRIVILEGES aplica-se a todos os objetos criados a partir deste ponto
-- pelo usuário que executa esta migration.
GRANT USAGE ON SCHEMA titlis_oltp  TO titlis_app;
GRANT USAGE ON SCHEMA titlis_audit TO titlis_app;
GRANT USAGE ON SCHEMA titlis_ts    TO titlis_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA titlis_oltp
    GRANT SELECT, INSERT, UPDATE ON TABLES TO titlis_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA titlis_audit
    GRANT SELECT, INSERT, UPDATE ON TABLES TO titlis_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA titlis_ts
    GRANT SELECT, INSERT, UPDATE ON TABLES TO titlis_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA titlis_oltp
    GRANT USAGE ON SEQUENCES TO titlis_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA titlis_audit
    GRANT USAGE ON SEQUENCES TO titlis_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA titlis_ts
    GRANT USAGE ON SEQUENCES TO titlis_app;

-- ================================================================
-- ENUMS
-- ================================================================
CREATE TYPE titlis_oltp.compliance_status AS ENUM (
    'COMPLIANT', 'NON_COMPLIANT', 'UNKNOWN', 'PENDING'
);

CREATE TYPE titlis_oltp.service_tier AS ENUM (
    'TIER_1', 'TIER_2', 'TIER_3', 'TIER_4'
);

CREATE TYPE titlis_oltp.validation_pillar AS ENUM (
    'RESILIENCE', 'SECURITY', 'COST', 'PERFORMANCE', 'OPERATIONAL', 'COMPLIANCE'
);

CREATE TYPE titlis_oltp.validation_severity AS ENUM (
    'CRITICAL', 'ERROR', 'WARNING', 'INFO', 'OPTIONAL'
);

CREATE TYPE titlis_oltp.validation_rule_type AS ENUM (
    'BOOLEAN', 'NUMERIC', 'ENUM', 'REGEX'
);

CREATE TYPE titlis_oltp.remediation_status AS ENUM (
    'PENDING', 'IN_PROGRESS', 'PR_OPEN', 'PR_MERGED', 'FAILED', 'SKIPPED'
);

CREATE TYPE titlis_oltp.slo_type AS ENUM (
    'METRIC', 'MONITOR', 'TIME_SLICE'
);

CREATE TYPE titlis_oltp.slo_timeframe AS ENUM (
    '7d', '30d', '90d'
);

CREATE TYPE titlis_oltp.slo_state AS ENUM (
    'ok', 'warning', 'error', 'no_data'
);

CREATE TYPE titlis_oltp.notification_severity AS ENUM (
    'INFO', 'WARNING', 'ERROR', 'CRITICAL'
);

CREATE TYPE titlis_oltp.remediation_category AS ENUM (
    'resources', 'hpa'
);

CREATE TYPE titlis_oltp.slo_app_framework AS ENUM (
    'WSGI', 'FASTAPI', 'AIOHTTP'
);

-- ================================================================
-- SCHEMA: titlis_oltp — Estado Atual (OLTP)
-- ================================================================

CREATE TABLE titlis_oltp.tenants (
    tenant_id   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_name VARCHAR(255) NOT NULL UNIQUE,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    tenant_plan VARCHAR(50)  NOT NULL DEFAULT 'free',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_oltp.tenants             IS 'Organizações ou times que utilizam o Titlis Operator';
COMMENT ON COLUMN titlis_oltp.tenants.tenant_id   IS 'Chave primária surrogate, gerada automaticamente';
COMMENT ON COLUMN titlis_oltp.tenants.tenant_name IS 'Nome de exibição da organização';
COMMENT ON COLUMN titlis_oltp.tenants.slug        IS 'Identificador URL-safe único da organização';
COMMENT ON COLUMN titlis_oltp.tenants.is_active   IS 'Soft-delete do tenant';
COMMENT ON COLUMN titlis_oltp.tenants.tenant_plan IS 'Plano contratado: free | pro | enterprise';
COMMENT ON COLUMN titlis_oltp.tenants.created_at  IS 'Data de criação do registro';
COMMENT ON COLUMN titlis_oltp.tenants.updated_at  IS 'Data da última modificação';

CREATE TABLE titlis_oltp.clusters (
    cluster_id   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    cluster_name VARCHAR(255) NOT NULL,
    environment  VARCHAR(50)  NOT NULL,
    region       VARCHAR(100),
    provider     VARCHAR(100),
    k8s_version  VARCHAR(50),
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (cluster_name, tenant_id)
);

COMMENT ON TABLE  titlis_oltp.clusters              IS 'Clusters Kubernetes monitorados pelo operador';
COMMENT ON COLUMN titlis_oltp.clusters.cluster_id   IS 'Chave primária surrogate';
COMMENT ON COLUMN titlis_oltp.clusters.tenant_id    IS 'Referência ao tenant proprietário';
COMMENT ON COLUMN titlis_oltp.clusters.cluster_name IS 'Nome do cluster; único por tenant';
COMMENT ON COLUMN titlis_oltp.clusters.environment  IS 'Ambiente: production, staging, develop';
COMMENT ON COLUMN titlis_oltp.clusters.updated_at   IS 'Data da última modificação';

CREATE INDEX idx_clusters_tenant ON titlis_oltp.clusters (tenant_id) WHERE tenant_id IS NOT NULL;

CREATE TABLE titlis_oltp.namespaces (
    namespace_id   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cluster_id     BIGINT       NOT NULL REFERENCES titlis_oltp.clusters(cluster_id),
    namespace_name VARCHAR(255) NOT NULL,
    is_excluded    BOOLEAN      NOT NULL DEFAULT FALSE,
    labels         JSONB,
    annotations    JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (cluster_id, namespace_name)
);

COMMENT ON TABLE  titlis_oltp.namespaces                IS 'Namespaces Kubernetes por cluster';
COMMENT ON COLUMN titlis_oltp.namespaces.namespace_id   IS 'Chave primária surrogate';
COMMENT ON COLUMN titlis_oltp.namespaces.is_excluded    IS 'Reflete excluded_namespaces do scorecard-config.yaml';
COMMENT ON COLUMN titlis_oltp.namespaces.updated_at     IS 'Data da última modificação';

CREATE TABLE titlis_oltp.workloads (
    workload_id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    namespace_id          BIGINT       NOT NULL REFERENCES titlis_oltp.namespaces(namespace_id),
    workload_name         VARCHAR(255) NOT NULL,
    workload_kind         VARCHAR(100) NOT NULL DEFAULT 'Deployment',
    k8s_uid               VARCHAR(255),
    service_tier          titlis_oltp.service_tier,
    dd_git_repository_url VARCHAR(500),
    backstage_component   VARCHAR(255),
    owner_team            VARCHAR(255),
    labels                JSONB,
    annotations           JSONB,
    resource_version      VARCHAR(100),
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (namespace_id, workload_name, workload_kind)
);

COMMENT ON TABLE  titlis_oltp.workloads                       IS 'Deployments Kubernetes rastreados pelo operador';
COMMENT ON COLUMN titlis_oltp.workloads.k8s_uid               IS 'metadata.uid do recurso K8s';
COMMENT ON COLUMN titlis_oltp.workloads.dd_git_repository_url IS 'DD_GIT_REPOSITORY_URL; pré-condição de auto-remediação';
COMMENT ON COLUMN titlis_oltp.workloads.is_active             IS 'Soft-delete';
COMMENT ON COLUMN titlis_oltp.workloads.updated_at            IS 'Data da última modificação';

CREATE INDEX idx_workloads_k8s_uid ON titlis_oltp.workloads (k8s_uid) WHERE k8s_uid IS NOT NULL;

CREATE TABLE titlis_oltp.validation_rules (
    validation_rule_id   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id              VARCHAR(50)  NOT NULL UNIQUE,
    pillar               titlis_oltp.validation_pillar    NOT NULL,
    rule_severity        titlis_oltp.validation_severity  NOT NULL,
    rule_type            titlis_oltp.validation_rule_type NOT NULL,
    weight               NUMERIC(5,2) NOT NULL DEFAULT 1.0,
    rule_name            VARCHAR(255) NOT NULL,
    description          TEXT,
    is_remediable        BOOLEAN      NOT NULL DEFAULT FALSE,
    remediation_category titlis_oltp.remediation_category,
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_oltp.validation_rules IS 'Catálogo das 26+ regras de validação de workloads';
COMMENT ON COLUMN titlis_oltp.validation_rules.rule_id IS 'Código legível: RES-001, PERF-002, etc.';
COMMENT ON COLUMN titlis_oltp.validation_rules.updated_at IS 'Data da última modificação';

CREATE TABLE titlis_oltp.app_scorecards (
    app_scorecard_id  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workload_id       BIGINT       NOT NULL REFERENCES titlis_oltp.workloads(workload_id),
    tenant_id         BIGINT       REFERENCES titlis_oltp.tenants(tenant_id),
    version           INTEGER      NOT NULL DEFAULT 1,
    overall_score     NUMERIC(5,2) NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
    compliance_status titlis_oltp.compliance_status NOT NULL DEFAULT 'UNKNOWN',
    total_rules       INTEGER      NOT NULL DEFAULT 0,
    passed_rules      INTEGER      NOT NULL DEFAULT 0,
    failed_rules      INTEGER      NOT NULL DEFAULT 0,
    critical_failures INTEGER      NOT NULL DEFAULT 0,
    error_count       INTEGER      NOT NULL DEFAULT 0,
    warning_count     INTEGER      NOT NULL DEFAULT 0,
    evaluated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    k8s_event_type    VARCHAR(50),
    raw_metadata      JSONB,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workload_id)
);

COMMENT ON TABLE  titlis_oltp.app_scorecards IS 'Estado atual do scorecard por workload (SCD Type 4)';
COMMENT ON COLUMN titlis_oltp.app_scorecards.tenant_id IS 'Desnormalizado para performance de RLS sem join';
COMMENT ON COLUMN titlis_oltp.app_scorecards.version   IS 'Contador monotônico de avaliações';
COMMENT ON COLUMN titlis_oltp.app_scorecards.updated_at IS 'Data da última modificação';

CREATE TABLE titlis_oltp.pillar_scores (
    pillar_score_id  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_scorecard_id BIGINT       NOT NULL REFERENCES titlis_oltp.app_scorecards(app_scorecard_id) ON DELETE CASCADE,
    pillar           titlis_oltp.validation_pillar NOT NULL,
    pillar_score     NUMERIC(5,2) NOT NULL CHECK (pillar_score BETWEEN 0 AND 100),
    passed_checks    INTEGER      NOT NULL DEFAULT 0,
    failed_checks    INTEGER      NOT NULL DEFAULT 0,
    weighted_score   NUMERIC(8,4),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (app_scorecard_id, pillar)
);

COMMENT ON TABLE  titlis_oltp.pillar_scores IS 'Score por pilar de governança do scorecard atual';
COMMENT ON COLUMN titlis_oltp.pillar_scores.updated_at IS 'Data da última modificação';

CREATE TABLE titlis_oltp.validation_results (
    validation_result_id BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_scorecard_id     BIGINT      NOT NULL REFERENCES titlis_oltp.app_scorecards(app_scorecard_id) ON DELETE CASCADE,
    validation_rule_id   BIGINT      NOT NULL REFERENCES titlis_oltp.validation_rules(validation_rule_id),
    rule_passed          BOOLEAN     NOT NULL,
    result_message       TEXT,
    actual_value         TEXT,
    evaluated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (app_scorecard_id, validation_rule_id)
);

COMMENT ON TABLE  titlis_oltp.validation_results IS 'Resultado de cada regra no scorecard atual';
COMMENT ON COLUMN titlis_oltp.validation_results.actual_value IS 'Valor observado no Deployment (ex: "100m" para CPU)';

CREATE TABLE titlis_oltp.app_remediations (
    app_remediation_id     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workload_id            BIGINT      NOT NULL REFERENCES titlis_oltp.workloads(workload_id),
    tenant_id              BIGINT      REFERENCES titlis_oltp.tenants(tenant_id),
    version                INTEGER     NOT NULL DEFAULT 1,
    app_scorecard_id       BIGINT      REFERENCES titlis_oltp.app_scorecards(app_scorecard_id),
    app_remediation_status titlis_oltp.remediation_status NOT NULL DEFAULT 'PENDING',
    github_pr_number       INTEGER,
    github_pr_url          VARCHAR(500),
    github_pr_title        VARCHAR(500),
    github_branch          VARCHAR(255),
    repository_url         VARCHAR(500),
    error_message          TEXT,
    triggered_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workload_id)
);

COMMENT ON TABLE  titlis_oltp.app_remediations IS 'Estado atual da remediação automática por workload (SCD Type 4)';
COMMENT ON COLUMN titlis_oltp.app_remediations.tenant_id IS 'Desnormalizado para performance de RLS';
COMMENT ON COLUMN titlis_oltp.app_remediations.updated_at IS 'Data da última modificação';

CREATE TABLE titlis_oltp.remediation_issues (
    remediation_issue_id BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    app_remediation_id   BIGINT      NOT NULL REFERENCES titlis_oltp.app_remediations(app_remediation_id) ON DELETE CASCADE,
    validation_rule_id   BIGINT      NOT NULL REFERENCES titlis_oltp.validation_rules(validation_rule_id),
    issue_category       titlis_oltp.remediation_category NOT NULL,
    description          TEXT,
    suggested_value      VARCHAR(100),
    applied_value        VARCHAR(100),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_oltp.remediation_issues IS 'Issues individuais de uma remediação';
COMMENT ON COLUMN titlis_oltp.remediation_issues.suggested_value IS 'Valor calculado antes de aplicar _keep_max';
COMMENT ON COLUMN titlis_oltp.remediation_issues.applied_value   IS 'Valor efetivamente aplicado no PR';

CREATE TABLE titlis_oltp.slo_configs (
    slo_config_id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    namespace_id          BIGINT       NOT NULL REFERENCES titlis_oltp.namespaces(namespace_id),
    tenant_id             BIGINT       REFERENCES titlis_oltp.tenants(tenant_id),
    slo_config_name       VARCHAR(255) NOT NULL,
    slo_type              titlis_oltp.slo_type      NOT NULL,
    timeframe             titlis_oltp.slo_timeframe NOT NULL,
    target                NUMERIC(6,4) NOT NULL CHECK (target BETWEEN 0 AND 100),
    warning               NUMERIC(6,4)              CHECK (warning BETWEEN 0 AND 100),
    auto_detect_framework BOOLEAN      NOT NULL DEFAULT FALSE,
    app_framework         titlis_oltp.slo_app_framework,
    detected_framework    VARCHAR(50),
    detection_source      VARCHAR(50),
    k8s_resource_uid      VARCHAR(255),
    datadog_slo_id        VARCHAR(255),
    datadog_slo_state     titlis_oltp.slo_state,
    last_sync_at          TIMESTAMPTZ,
    sync_error            TEXT,
    spec_raw              JSONB,
    version               INTEGER      NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (namespace_id, slo_config_name),
    CONSTRAINT chk_warning_gt_target CHECK (warning IS NULL OR warning > target)
);

COMMENT ON TABLE  titlis_oltp.slo_configs IS 'Estado atual dos SLOs — espelha SLOConfig CRDs do Kubernetes';
COMMENT ON COLUMN titlis_oltp.slo_configs.warning IS 'Limiar de aviso; deve ser maior que target';
COMMENT ON COLUMN titlis_oltp.slo_configs.k8s_resource_uid IS 'metadata.uid do SLOConfig CRD; tag titlis_resource_uid no Datadog (Path B de idempotência)';
COMMENT ON COLUMN titlis_oltp.slo_configs.updated_at IS 'Data da última modificação';

CREATE INDEX idx_slo_datadog_id       ON titlis_oltp.slo_configs (datadog_slo_id);
CREATE INDEX idx_slo_k8s_uid          ON titlis_oltp.slo_configs (k8s_resource_uid) WHERE k8s_resource_uid IS NOT NULL;
CREATE INDEX idx_slo_detection_source ON titlis_oltp.slo_configs (detection_source);

CREATE TABLE titlis_oltp.platform_users (
    platform_user_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    email            VARCHAR(320) NOT NULL,
    display_name     VARCHAR(255),
    password_hash    TEXT,
    platform_role    VARCHAR(50)  NOT NULL DEFAULT 'viewer',
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    is_break_glass   BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    UNIQUE (tenant_id, email),
    CONSTRAINT chk_platform_users_role
        CHECK (platform_role IN ('admin', 'engineer', 'pm', 'viewer'))
);

COMMENT ON TABLE  titlis_oltp.platform_users IS 'Usuarios humanos da plataforma Titlis';
COMMENT ON COLUMN titlis_oltp.platform_users.password_hash   IS 'Hash da senha local; nullable quando usa apenas login federado';
COMMENT ON COLUMN titlis_oltp.platform_users.is_break_glass  IS 'Conta local de emergencia para acesso administrativo';

CREATE INDEX idx_platform_users_tenant ON titlis_oltp.platform_users (tenant_id);
CREATE INDEX idx_platform_users_active ON titlis_oltp.platform_users (tenant_id, is_active);

CREATE TABLE titlis_oltp.tenant_auth_integrations (
    tenant_auth_integration_id       BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                        BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    provider_type                    VARCHAR(50)  NOT NULL,
    integration_kind                 VARCHAR(50)  NOT NULL DEFAULT 'sso_oidc',
    integration_name                 VARCHAR(255) NOT NULL,
    is_enabled                       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_primary                       BOOLEAN      NOT NULL DEFAULT FALSE,
    issuer_url                       VARCHAR(500),
    client_id                        VARCHAR(255),
    audience                         VARCHAR(255),
    scopes                           VARCHAR(500),
    config_json                      JSONB,
    configured_by_platform_user_id   BIGINT       REFERENCES titlis_oltp.platform_users(platform_user_id),
    verified_at                      TIMESTAMPTZ,
    activated_at                     TIMESTAMPTZ,
    created_at                       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at                       TIMESTAMPTZ,
    CONSTRAINT chk_auth_provider_type
        CHECK (provider_type IN ('okta', 'azure_ad', 'google_oidc', 'github_oidc', 'local')),
    CONSTRAINT chk_auth_integration_kind
        CHECK (integration_kind IN ('local_password', 'sso_oidc', 'saml'))
);

COMMENT ON TABLE  titlis_oltp.tenant_auth_integrations IS 'Configuracoes de autenticacao por tenant';
COMMENT ON COLUMN titlis_oltp.tenant_auth_integrations.is_primary IS 'Define a integracao primaria de login do tenant';

CREATE INDEX idx_tenant_auth_integrations_tenant ON titlis_oltp.tenant_auth_integrations (tenant_id);

CREATE UNIQUE INDEX uq_tenant_auth_integrations_name
    ON titlis_oltp.tenant_auth_integrations (tenant_id, integration_name);

CREATE UNIQUE INDEX uq_tenant_auth_integrations_primary
    ON titlis_oltp.tenant_auth_integrations (tenant_id)
    WHERE is_primary = TRUE;

CREATE TABLE titlis_oltp.user_auth_identities (
    user_auth_identity_id      BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_user_id           BIGINT       NOT NULL REFERENCES titlis_oltp.platform_users(platform_user_id) ON DELETE CASCADE,
    tenant_auth_integration_id BIGINT       NOT NULL REFERENCES titlis_oltp.tenant_auth_integrations(tenant_auth_integration_id) ON DELETE CASCADE,
    provider_subject           VARCHAR(255) NOT NULL,
    issuer_url                 VARCHAR(500),
    email_snapshot             VARCHAR(320),
    claims_snapshot            JSONB,
    last_authenticated_at      TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_oltp.user_auth_identities IS 'Vinculo entre usuario interno e identidade externa do provedor';
COMMENT ON COLUMN titlis_oltp.user_auth_identities.provider_subject IS 'Claim sub do usuario no provedor';

CREATE INDEX idx_user_auth_identities_user        ON titlis_oltp.user_auth_identities (platform_user_id);
CREATE INDEX idx_user_auth_identities_integration ON titlis_oltp.user_auth_identities (tenant_auth_integration_id);

CREATE UNIQUE INDEX uq_user_auth_identities_subject
    ON titlis_oltp.user_auth_identities (tenant_auth_integration_id, provider_subject);

CREATE TABLE titlis_oltp.platform_user_invites (
    platform_user_invite_id       BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                     BIGINT       NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    email                         VARCHAR(320) NOT NULL,
    target_role                   VARCHAR(50)  NOT NULL DEFAULT 'viewer',
    invite_status                 VARCHAR(50)  NOT NULL DEFAULT 'pending',
    tenant_auth_integration_id    BIGINT       REFERENCES titlis_oltp.tenant_auth_integrations(tenant_auth_integration_id),
    invited_by_platform_user_id   BIGINT       REFERENCES titlis_oltp.platform_users(platform_user_id),
    accepted_by_platform_user_id  BIGINT       REFERENCES titlis_oltp.platform_users(platform_user_id),
    invite_token                  VARCHAR(255),
    expires_at                    TIMESTAMPTZ,
    accepted_at                   TIMESTAMPTZ,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_platform_user_invites_role
        CHECK (target_role IN ('admin', 'engineer', 'pm', 'viewer')),
    CONSTRAINT chk_platform_user_invites_status
        CHECK (invite_status IN ('pending', 'sent', 'accepted', 'expired', 'revoked'))
);

COMMENT ON TABLE  titlis_oltp.platform_user_invites IS 'Convites e preprovisionamento de usuarios';
COMMENT ON COLUMN titlis_oltp.platform_user_invites.invite_token IS 'Token do convite para onboarding';

CREATE INDEX idx_platform_user_invites_tenant ON titlis_oltp.platform_user_invites (tenant_id);
CREATE INDEX idx_platform_user_invites_status ON titlis_oltp.platform_user_invites (tenant_id, invite_status);

CREATE UNIQUE INDEX uq_platform_user_invites_token
    ON titlis_oltp.platform_user_invites (invite_token)
    WHERE invite_token IS NOT NULL;

CREATE TABLE titlis_oltp.tenant_api_keys (
    api_key_id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT      NOT NULL REFERENCES titlis_oltp.tenants(tenant_id),
    key_prefix         VARCHAR(16) NOT NULL,
    key_hash           VARCHAR(64) NOT NULL UNIQUE,
    description        VARCHAR(255),
    is_active          BOOLEAN     NOT NULL DEFAULT TRUE,
    last_used_at       TIMESTAMPTZ,
    created_by_user_id BIGINT      REFERENCES titlis_oltp.platform_users(platform_user_id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at         TIMESTAMPTZ,
    deleted_at         TIMESTAMPTZ
);

COMMENT ON TABLE  titlis_oltp.tenant_api_keys IS 'API keys para autenticação do operator (modelo Datadog agent key)';
COMMENT ON COLUMN titlis_oltp.tenant_api_keys.key_prefix   IS 'Primeiros 12 chars do token — exibição sem expor a key completa';
COMMENT ON COLUMN titlis_oltp.tenant_api_keys.key_hash     IS 'SHA-256 do token completo — lookup rápido sem bcrypt';
COMMENT ON COLUMN titlis_oltp.tenant_api_keys.last_used_at IS 'Atualizado a cada evento válido — detecta keys órfãs';

CREATE INDEX idx_tenant_api_keys_tenant ON titlis_oltp.tenant_api_keys (tenant_id);

-- ================================================================
-- SCHEMA: titlis_audit — Histórico e Auditoria (SCD Type 4)
-- ================================================================

CREATE TABLE titlis_audit.app_scorecard_history (
    app_scorecard_history_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workload_id              BIGINT       NOT NULL,
    tenant_id                BIGINT,
    scorecard_version        INTEGER      NOT NULL,
    overall_score            NUMERIC(5,2) NOT NULL,
    compliance_status        VARCHAR(50)  NOT NULL,
    total_rules              INTEGER      NOT NULL,
    passed_rules             INTEGER      NOT NULL,
    failed_rules             INTEGER      NOT NULL,
    critical_failures        INTEGER      NOT NULL,
    error_count              INTEGER      NOT NULL,
    warning_count            INTEGER      NOT NULL,
    pillar_scores            JSONB        NOT NULL,
    validation_results       JSONB        NOT NULL,
    evaluated_at             TIMESTAMPTZ  NOT NULL,
    k8s_event_type           VARCHAR(50),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_audit.app_scorecard_history IS 'Histórico de scorecards arquivados antes de cada sobrescrita (SCD Type 4)';
COMMENT ON COLUMN titlis_audit.app_scorecard_history.workload_id        IS 'Referência lógica sem FK — sobrevive à deleção do workload';
COMMENT ON COLUMN titlis_audit.app_scorecard_history.pillar_scores      IS 'Snapshot JSONB dos pillar_scores';
COMMENT ON COLUMN titlis_audit.app_scorecard_history.validation_results IS 'Snapshot JSONB dos validation_results';

CREATE INDEX idx_scorecard_hist_workload_time
    ON titlis_audit.app_scorecard_history (workload_id, evaluated_at DESC);

CREATE INDEX idx_scorecard_hist_compliance
    ON titlis_audit.app_scorecard_history (compliance_status, evaluated_at DESC);

CREATE INDEX idx_scorecard_hist_pillar_gin
    ON titlis_audit.app_scorecard_history USING GIN (pillar_scores);

CREATE INDEX idx_scorecard_hist_validation_gin
    ON titlis_audit.app_scorecard_history USING GIN (validation_results);

CREATE TABLE titlis_audit.pillar_score_history (
    pillar_score_history_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workload_id             BIGINT       NOT NULL,
    tenant_id               BIGINT,
    scorecard_version       INTEGER      NOT NULL,
    pillar                  VARCHAR(50)  NOT NULL,
    pillar_score            NUMERIC(5,2) NOT NULL,
    passed_checks           INTEGER      NOT NULL,
    failed_checks           INTEGER      NOT NULL,
    weighted_score          NUMERIC(8,4),
    evaluated_at            TIMESTAMPTZ  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_audit.pillar_score_history IS 'Histórico de scores por pilar para gráficos de evolução';
COMMENT ON COLUMN titlis_audit.pillar_score_history.workload_id IS 'Referência lógica sem FK';

CREATE INDEX idx_pillar_hist_workload_pillar_time
    ON titlis_audit.pillar_score_history (workload_id, pillar, evaluated_at DESC);

CREATE TABLE titlis_audit.remediation_history (
    remediation_history_id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workload_id                     BIGINT      NOT NULL,
    tenant_id                       BIGINT,
    remediation_version             INTEGER     NOT NULL,
    app_remediation_status          VARCHAR(50) NOT NULL,
    previous_app_remediation_status VARCHAR(50),
    scorecard_version               INTEGER,
    github_pr_number                INTEGER,
    github_pr_url                   VARCHAR(500),
    github_branch                   VARCHAR(255),
    repository_url                  VARCHAR(500),
    issues_snapshot                 JSONB,
    error_message                   TEXT,
    triggered_at                    TIMESTAMPTZ NOT NULL,
    resolved_at                     TIMESTAMPTZ,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_audit.remediation_history IS 'Log imutável de todas as transições de estado de remediação';
COMMENT ON COLUMN titlis_audit.remediation_history.workload_id IS 'Referência lógica sem FK';
COMMENT ON COLUMN titlis_audit.remediation_history.previous_app_remediation_status IS 'Estado anterior — permite reconstruir a máquina de estados';

CREATE INDEX idx_remediation_hist_workload_time
    ON titlis_audit.remediation_history (workload_id, triggered_at DESC);

CREATE INDEX idx_remediation_hist_status
    ON titlis_audit.remediation_history (app_remediation_status, created_at DESC);

CREATE TABLE titlis_audit.slo_compliance_history (
    slo_compliance_history_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slo_config_id             BIGINT       NOT NULL,
    namespace_id              BIGINT       NOT NULL,
    tenant_id                 BIGINT,
    slo_config_name           VARCHAR(255) NOT NULL,
    datadog_slo_id            VARCHAR(255),
    slo_type                  VARCHAR(50)  NOT NULL,
    timeframe                 VARCHAR(10)  NOT NULL,
    target                    NUMERIC(6,4) NOT NULL,
    actual_value              NUMERIC(6,4),
    slo_state                 VARCHAR(50),
    sync_action               VARCHAR(50),
    sync_error                TEXT,
    detected_framework        VARCHAR(50),
    detection_source          VARCHAR(50),
    recorded_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_audit.slo_compliance_history IS 'Histórico de sincronizações de SLO com o Datadog';
COMMENT ON COLUMN titlis_audit.slo_compliance_history.detected_framework IS 'Framework detectado nesta sincronização (auditoria de H-13)';

CREATE INDEX idx_slo_hist_config_time
    ON titlis_audit.slo_compliance_history (slo_config_id, recorded_at DESC);

CREATE INDEX idx_slo_hist_detection
    ON titlis_audit.slo_compliance_history (detected_framework, detection_source)
    WHERE detected_framework IS NOT NULL;

CREATE TABLE titlis_audit.notification_log (
    notification_log_id   BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workload_id           BIGINT,
    namespace_id          BIGINT,
    tenant_id             BIGINT,
    notification_type     VARCHAR(50) NOT NULL,
    notification_severity titlis_oltp.notification_severity NOT NULL,
    channel               VARCHAR(255),
    notification_title    VARCHAR(500),
    message_preview       VARCHAR(500),
    sent_at               TIMESTAMPTZ,
    success               BOOLEAN     NOT NULL DEFAULT FALSE,
    error_message         TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_audit.notification_log IS 'Auditoria de todas as notificações Slack enviadas pelo operador';
COMMENT ON COLUMN titlis_audit.notification_log.workload_id IS 'Referência lógica; NULL quando for digest de namespace';

CREATE INDEX idx_notif_log_workload_time
    ON titlis_audit.notification_log (workload_id, created_at DESC);

CREATE INDEX idx_notif_log_namespace_time
    ON titlis_audit.notification_log (namespace_id, created_at DESC);

-- ================================================================
-- SCHEMA: titlis_ts — Time-series de Métricas
-- ================================================================

CREATE TABLE titlis_ts.resource_metrics (
    resource_metric_id    BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workload_id           BIGINT       NOT NULL,
    tenant_id             BIGINT,
    container_name        VARCHAR(255),
    metric_source         VARCHAR(50)  NOT NULL DEFAULT 'datadog',
    cpu_avg_millicores    NUMERIC(10,3),
    cpu_p95_millicores    NUMERIC(10,3),
    mem_avg_mib           NUMERIC(10,3),
    mem_p95_mib           NUMERIC(10,3),
    suggested_cpu_request VARCHAR(50),
    suggested_cpu_limit   VARCHAR(50),
    suggested_mem_request VARCHAR(50),
    suggested_mem_limit   VARCHAR(50),
    sample_window         VARCHAR(20),
    collected_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_ts.resource_metrics IS 'Métricas de CPU e memória por workload (série temporal)';
COMMENT ON COLUMN titlis_ts.resource_metrics.workload_id IS 'Referência lógica ao workload';

CREATE INDEX idx_resource_metrics_workload_time
    ON titlis_ts.resource_metrics (workload_id, collected_at DESC);

CREATE TABLE titlis_ts.scorecard_scores (
    scorecard_score_id BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workload_id        BIGINT       NOT NULL,
    tenant_id          BIGINT,
    overall_score      NUMERIC(5,2) NOT NULL,
    resilience_score   NUMERIC(5,2),
    security_score     NUMERIC(5,2),
    cost_score         NUMERIC(5,2),
    performance_score  NUMERIC(5,2),
    operational_score  NUMERIC(5,2),
    compliance_score   NUMERIC(5,2),
    compliance_status  VARCHAR(50)  NOT NULL,
    passed_rules       INTEGER,
    failed_rules       INTEGER,
    recorded_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_ts.scorecard_scores IS 'Série temporal plana de scores para dashboards (desnormalizado)';
COMMENT ON COLUMN titlis_ts.scorecard_scores.recorded_at IS 'Eixo X de todos os gráficos de evolução';

CREATE INDEX idx_score_ts_workload_time
    ON titlis_ts.scorecard_scores (workload_id, recorded_at DESC);

CREATE INDEX idx_score_ts_recorded_at
    ON titlis_ts.scorecard_scores (recorded_at DESC);

-- ================================================================
-- VIEWS — abstração para Frontend e APIs
-- ================================================================

CREATE OR REPLACE VIEW titlis_oltp.v_workload_dashboard AS
SELECT
    w.workload_id,
    c.cluster_name,
    c.environment,
    c.tenant_id,
    n.namespace_name            AS namespace,
    w.workload_name,
    w.workload_kind,
    w.service_tier,
    w.owner_team,
    sc.overall_score,
    sc.compliance_status,
    sc.passed_rules,
    sc.failed_rules,
    sc.critical_failures,
    sc.version                  AS scorecard_version,
    sc.evaluated_at,
    ar.app_remediation_status   AS remediation_status,
    ar.github_pr_url,
    ar.github_pr_number,
    sc.updated_at               AS last_scored_at
FROM titlis_oltp.workloads w
JOIN titlis_oltp.namespaces n      ON n.namespace_id = w.namespace_id
JOIN titlis_oltp.clusters c        ON c.cluster_id = n.cluster_id
LEFT JOIN titlis_oltp.app_scorecards sc ON sc.workload_id = w.workload_id
LEFT JOIN titlis_oltp.app_remediations ar ON ar.workload_id = w.workload_id
WHERE w.is_active = TRUE
  AND n.is_excluded = FALSE;

CREATE OR REPLACE VIEW titlis_audit.v_score_evolution AS
SELECT
    workload_id,
    scorecard_version,
    overall_score,
    compliance_status,
    passed_rules,
    failed_rules,
    evaluated_at,
    overall_score - LAG(overall_score) OVER (
        PARTITION BY workload_id ORDER BY evaluated_at
    ) AS score_delta
FROM titlis_audit.app_scorecard_history
ORDER BY workload_id, evaluated_at DESC;

CREATE OR REPLACE VIEW titlis_audit.v_top_failing_rules AS
SELECT
    (vr->>'rule_ref')   AS rule_id,
    (vr->>'pillar')     AS pillar,
    (vr->>'severity')   AS severity,
    COUNT(*)            AS total_failures,
    COUNT(DISTINCT h.workload_id) AS affected_workloads,
    MAX(h.evaluated_at) AS last_seen
FROM titlis_audit.app_scorecard_history h,
     jsonb_array_elements(h.validation_results) AS vr
WHERE (vr->>'passed')::BOOLEAN = FALSE
GROUP BY 1, 2, 3
ORDER BY total_failures DESC;

CREATE OR REPLACE VIEW titlis_audit.v_remediation_effectiveness AS
SELECT
    workload_id,
    COUNT(*)                                                                   AS total_attempts,
    COUNT(*) FILTER (WHERE app_remediation_status = 'PR_MERGED')               AS successful,
    COUNT(*) FILTER (WHERE app_remediation_status = 'FAILED')                  AS failed,
    ROUND(
        100.0 * COUNT(*) FILTER (WHERE app_remediation_status = 'PR_MERGED')
        / NULLIF(COUNT(*), 0), 2
    )                                                                          AS success_rate_pct,
    MAX(triggered_at)                                                          AS last_attempt_at
FROM titlis_audit.remediation_history
GROUP BY workload_id;

CREATE OR REPLACE VIEW titlis_oltp.v_slo_framework_detection AS
SELECT
    n.namespace_name            AS namespace,
    sc.slo_config_name          AS slo_name,
    sc.slo_type,
    sc.auto_detect_framework,
    sc.app_framework            AS explicit_framework,
    sc.detected_framework,
    sc.detection_source,
    sc.datadog_slo_id,
    sc.datadog_slo_state,
    sc.last_sync_at,
    sc.sync_error
FROM titlis_oltp.slo_configs sc
JOIN titlis_oltp.namespaces n ON n.namespace_id = sc.namespace_id
ORDER BY
    (sc.detection_source = 'fallback') DESC,
    sc.last_sync_at DESC NULLS LAST;

-- ================================================================
-- ÍNDICES DE PERFORMANCE — leituras do frontend
-- ================================================================

CREATE INDEX idx_workloads_namespace  ON titlis_oltp.workloads (namespace_id)
    WHERE is_active = TRUE;
CREATE INDEX idx_scorecard_compliance ON titlis_oltp.app_scorecards (compliance_status);
CREATE INDEX idx_scorecard_score      ON titlis_oltp.app_scorecards (overall_score);
CREATE INDEX idx_remediation_status   ON titlis_oltp.app_remediations (app_remediation_status);
CREATE INDEX idx_val_results_rule_passed ON titlis_oltp.validation_results (validation_rule_id, rule_passed);
