-- ================================================================
-- V10: Clerk Auth v2 — infraestrutura (Fase 1)
--
-- 1. clerk_user_id em platform_users
--    Vincula conta interna ao Clerk user ID.
--    Nullable — usuários /v1 (local/Okta) não têm.
--
-- 2. tenant_team_invites
--    Emails pré-autorizados pelo admin do tenant.
--    Ao fazer login via Clerk com email registrado aqui, o usuário
--    é provisionado automaticamente no tenant com o role definido.
--
-- Ambas as operações são idempotentes (IF NOT EXISTS) — seguro re-executar.
-- ================================================================

-- ----------------------------------------------------------------
-- 1. clerk_user_id em platform_users
-- ----------------------------------------------------------------
ALTER TABLE titlis_oltp.platform_users
    ADD COLUMN IF NOT EXISTS clerk_user_id TEXT UNIQUE;

COMMENT ON COLUMN titlis_oltp.platform_users.clerk_user_id
    IS 'ID Clerk do usuário (user_xxx). NULL para usuários criados via /v1 (local/Okta). Único — uma conta Clerk pertence a no máximo um usuário Titlis.';

-- ----------------------------------------------------------------
-- 2. tenant_team_invites
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS titlis_oltp.tenant_team_invites (
    tenant_team_invite_id BIGSERIAL   PRIMARY KEY,
    tenant_id             BIGINT      NOT NULL REFERENCES titlis_oltp.tenants(tenant_id) ON DELETE CASCADE,
    email                 TEXT        NOT NULL,
    titlis_role           TEXT        NOT NULL DEFAULT 'viewer',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    provisioned_at        TIMESTAMPTZ,
    CONSTRAINT tenant_team_invites_tenant_email_uq UNIQUE (tenant_id, email)
);

COMMENT ON TABLE titlis_oltp.tenant_team_invites
    IS 'Emails pré-autorizados pelo admin. Na primeira vez que o email faz login via Clerk, o usuário é adicionado ao tenant com o role registrado aqui.';

COMMENT ON COLUMN titlis_oltp.tenant_team_invites.provisioned_at
    IS 'Preenchido quando o usuário logou via Clerk pela primeira vez e foi adicionado ao tenant. NULL se ainda não logou.';

CREATE INDEX IF NOT EXISTS idx_tenant_team_invites_email
    ON titlis_oltp.tenant_team_invites (email);

CREATE INDEX IF NOT EXISTS idx_tenant_team_invites_tenant_id
    ON titlis_oltp.tenant_team_invites (tenant_id);
