-- Adiciona status PR_CLOSED ao enum de remediação.
-- Necessário para registrar PRs fechados pelo usuário sem merge,
-- permitindo que nova remediação seja iniciada para o mesmo workload.
ALTER TYPE titlis_oltp.remediation_status ADD VALUE IF NOT EXISTS 'PR_CLOSED';

COMMENT ON COLUMN titlis_oltp.app_remediations.app_remediation_status
    IS 'Estado atual: PENDING, IN_PROGRESS, PR_OPEN, PR_MERGED, PR_CLOSED, FAILED, SKIPPED';
