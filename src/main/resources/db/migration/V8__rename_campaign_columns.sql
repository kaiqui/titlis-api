-- ================================================================
-- V8: Renomeia colunas das tabelas de campanhas de PR para alinhar
--     com o schema esperado pelo ORM (Exposed).
--
-- Contexto:
--   O V7__pr_campaigns.sql foi aplicado originalmente com nomes de
--   coluna genéricos (id, campaign_id, item_id). O código Kotlin
--   evoluiu para usar nomes descritivos (pr_campaign_id, etc.) mas
--   createMissingTablesAndColumns() não renomeia colunas existentes.
--
-- Idempotente: cada bloco verifica se a coluna com o nome antigo
--   ainda existe antes de executar o RENAME — seguro para re-execução
--   e para ambientes onde o V7 já criou a tabela com os nomes corretos.
-- ================================================================

DO $$
BEGIN

    -- ----------------------------------------------------------------
    -- titlis_oltp.pr_campaigns
    --   id → pr_campaign_id
    -- ----------------------------------------------------------------
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'titlis_oltp'
          AND table_name   = 'pr_campaigns'
          AND column_name  = 'id'
    ) THEN
        ALTER TABLE titlis_oltp.pr_campaigns RENAME COLUMN id TO pr_campaign_id;
    END IF;

    -- ----------------------------------------------------------------
    -- titlis_oltp.pr_campaign_items
    --   id          → pr_campaign_item_id
    --   campaign_id → pr_campaign_id
    -- ----------------------------------------------------------------
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'titlis_oltp'
          AND table_name   = 'pr_campaign_items'
          AND column_name  = 'id'
    ) THEN
        ALTER TABLE titlis_oltp.pr_campaign_items RENAME COLUMN id TO pr_campaign_item_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'titlis_oltp'
          AND table_name   = 'pr_campaign_items'
          AND column_name  = 'campaign_id'
    ) THEN
        ALTER TABLE titlis_oltp.pr_campaign_items RENAME COLUMN campaign_id TO pr_campaign_id;
    END IF;

    -- ----------------------------------------------------------------
    -- titlis_oltp.pr_campaign_env_steps
    --   id      → pr_campaign_env_step_id
    --   item_id → pr_campaign_item_id
    -- ----------------------------------------------------------------
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'titlis_oltp'
          AND table_name   = 'pr_campaign_env_steps'
          AND column_name  = 'id'
    ) THEN
        ALTER TABLE titlis_oltp.pr_campaign_env_steps RENAME COLUMN id TO pr_campaign_env_step_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'titlis_oltp'
          AND table_name   = 'pr_campaign_env_steps'
          AND column_name  = 'item_id'
    ) THEN
        ALTER TABLE titlis_oltp.pr_campaign_env_steps RENAME COLUMN item_id TO pr_campaign_item_id;
    END IF;

END $$;
