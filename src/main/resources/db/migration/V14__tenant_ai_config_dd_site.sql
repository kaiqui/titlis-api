-- Titlis-002: adds the Datadog site column to tenant_ai_configs.
-- The column exists in the Exposed model (OltpTables.TenantAiConfigs.ddSite) but no
-- migration created it, so queries selecting dd_site failed with "column does not exist".
ALTER TABLE titlis_oltp.tenant_ai_configs
    ADD COLUMN IF NOT EXISTS dd_site TEXT NOT NULL DEFAULT 'datadoghq.com';
