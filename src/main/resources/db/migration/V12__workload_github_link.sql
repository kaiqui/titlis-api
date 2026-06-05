-- Vinculação de workload ao repositório GitHub para remediação via ARIA.
-- github_repo_url: URL do repo (ex: https://github.com/org/titlis-api)
-- service_yaml_path: caminho do .titlis/service.yaml dentro do repo (default raiz)
ALTER TABLE titlis_oltp.workloads
    ADD COLUMN IF NOT EXISTS github_repo_url   VARCHAR(500),
    ADD COLUMN IF NOT EXISTS service_yaml_path VARCHAR(500);
