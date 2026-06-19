ALTER TABLE titlis_oltp.tenant_ai_configs
    ADD COLUMN IF NOT EXISTS monitor_creation_enabled BOOLEAN NOT NULL DEFAULT false;
