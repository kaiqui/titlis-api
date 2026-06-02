-- Adds GitHub App auth columns to tenant_ai_configs so tenants can
-- authenticate via GitHub App instead of a personal access token (PAT).
ALTER TABLE titlis_oltp.tenant_ai_configs
    ADD COLUMN IF NOT EXISTS github_auth_mode         TEXT NOT NULL DEFAULT 'pat',
    ADD COLUMN IF NOT EXISTS github_app_id_enc        TEXT,
    ADD COLUMN IF NOT EXISTS github_app_priv_key_enc  TEXT,
    ADD COLUMN IF NOT EXISTS github_app_install_id_enc TEXT;
