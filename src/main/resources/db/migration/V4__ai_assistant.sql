-- ================================================================
-- V4: Suporte ao assistente IA (RAG + configuração por tenant)
-- Requer pgvector >= 0.5 instalado no PostgreSQL.
-- Em produção, garanta que a extensão já esteja instalada pelo DBA
-- antes de executar esta migration.
-- ================================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS titlis_ai;

GRANT USAGE ON SCHEMA titlis_ai TO titlis_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA titlis_ai
    GRANT SELECT, INSERT, UPDATE ON TABLES TO titlis_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA titlis_ai
    GRANT USAGE ON SEQUENCES TO titlis_app;

-- Configuração de provider LLM e token GitHub por tenant
CREATE TABLE titlis_oltp.tenant_ai_configs (
    tenant_id            BIGINT      NOT NULL PRIMARY KEY REFERENCES titlis_oltp.tenants(tenant_id) ON DELETE CASCADE,
    provider             TEXT        NOT NULL,
    model                TEXT        NOT NULL,
    api_key_enc          TEXT        NOT NULL,
    github_token_enc     TEXT,
    github_base_branch   TEXT        NOT NULL DEFAULT 'main',
    monthly_token_budget INTEGER,
    tokens_used_month    INTEGER     NOT NULL DEFAULT 0,
    is_active            BOOLEAN     NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_provider
        CHECK (provider IN ('openai','anthropic','google','gemini','mistral','cohere','azure','ollama'))
);

COMMENT ON TABLE  titlis_oltp.tenant_ai_configs             IS 'Configuração de provider/model de IA por tenant';
COMMENT ON COLUMN titlis_oltp.tenant_ai_configs.api_key_enc IS 'API key do provider — write-only, nunca exposta em respostas JSON';
COMMENT ON COLUMN titlis_oltp.tenant_ai_configs.github_token_enc IS 'GitHub token para abertura de PRs — write-only';

-- Base de conhecimento vetorial para RAG
-- Chunks globais (tenant_id IS NULL) são visíveis a todos os tenants;
-- chunks de tenant (tenant_id NOT NULL) são privados.
CREATE TABLE titlis_ai.knowledge_chunks (
    chunk_id    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   BIGINT       REFERENCES titlis_oltp.tenants(tenant_id) ON DELETE CASCADE,
    source_type TEXT         NOT NULL,
    source_id   TEXT         NOT NULL,
    chunk_text  TEXT         NOT NULL,
    embedding   VECTOR(1536) NOT NULL,
    metadata    JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  titlis_ai.knowledge_chunks           IS 'Embeddings de texto para RAG: docs de regras (global) e remediações passadas (por tenant)';
COMMENT ON COLUMN titlis_ai.knowledge_chunks.tenant_id IS 'NULL = chunk global visível a todos; NOT NULL = chunk privado do tenant';
COMMENT ON COLUMN titlis_ai.knowledge_chunks.embedding IS 'Vetor dimensão 1536 (text-embedding-3-small)';

CREATE INDEX idx_knowledge_chunks_tenant
    ON titlis_ai.knowledge_chunks (tenant_id);

CREATE UNIQUE INDEX uq_knowledge_chunks_global_src
    ON titlis_ai.knowledge_chunks (source_type, source_id)
    WHERE tenant_id IS NULL;

CREATE UNIQUE INDEX uq_knowledge_chunks_tenant_src
    ON titlis_ai.knowledge_chunks (tenant_id, source_type, source_id)
    WHERE tenant_id IS NOT NULL;
