-- Titlis-001: adds Datadog credential columns to tenant_ai_configs
-- so titlis-insights can fetch per-tenant DD keys via the internal API.
ALTER TABLE titlis_oltp.tenant_ai_configs
    ADD COLUMN IF NOT EXISTS dd_api_key_enc TEXT,
    ADD COLUMN IF NOT EXISTS dd_app_key_enc  TEXT;
