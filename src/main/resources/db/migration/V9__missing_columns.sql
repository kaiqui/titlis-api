-- ================================================================
-- V9: Colunas adicionadas manualmente em produção (prod/terceiro_script.sql)
--     mas não incluídas nas migrations V1–V8.
--
-- 1. app_remediations.pending_rule_ids
--    Necessária para scorecard/dashboard atuais.
--    Referenciada em OltpTables.kt:AppRemediations.pendingRuleIds
--    Causa PSQLException se ausente: "column pending_rule_ids does not exist"
--
-- 2. user_auth_identities.deleted_at
--    Soft-delete da identidade externa vinculada ao provedor OIDC.
--    Referenciada em OltpTables.kt:UserAuthIdentities.deletedAt
--    Usada em AuthRepository.kt (linhas 244 e 556) como:
--      UserAuthIdentities.deletedAt.isNull()
--
-- Ambas são idempotentes (IF NOT EXISTS) — seguro re-executar.
-- ================================================================

-- ----------------------------------------------------------------
-- 1. pending_rule_ids em app_remediations
-- JSON array dos rule_ids cobertos pelo PR aberto,
-- ou lista CSV dependendo do caller (campo não estruturado por design).
-- ----------------------------------------------------------------
ALTER TABLE titlis_oltp.app_remediations
    ADD COLUMN IF NOT EXISTS pending_rule_ids TEXT;

COMMENT ON COLUMN titlis_oltp.app_remediations.pending_rule_ids
    IS 'Rule IDs cobertos pelo PR aberto; formato livre (JSON array ou CSV). NULL quando remediação não tem PR associado.';

-- ----------------------------------------------------------------
-- 2. deleted_at em user_auth_identities
-- Soft-delete do vínculo entre conta interna e identidade externa.
-- NULL enquanto o vínculo está ativo.
-- ----------------------------------------------------------------
ALTER TABLE titlis_oltp.user_auth_identities
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN titlis_oltp.user_auth_identities.deleted_at
    IS 'Timestamp de soft-delete do vínculo com a identidade externa; NULL enquanto ativo.';
