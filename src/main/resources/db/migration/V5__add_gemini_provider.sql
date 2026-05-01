-- ================================================================
-- V5: Adiciona 'gemini' aos provedores de IA permitidos.
-- O código (SUPPORTED_PROVIDERS) já aceitava gemini; o constraint
-- do banco estava desalinhado.
-- ================================================================

ALTER TABLE titlis_oltp.tenant_ai_configs
    DROP CONSTRAINT IF EXISTS chk_ai_provider;

ALTER TABLE titlis_oltp.tenant_ai_configs
    ADD CONSTRAINT chk_ai_provider
        CHECK (provider IN ('openai','anthropic','google','gemini','mistral','cohere','azure','ollama'));
