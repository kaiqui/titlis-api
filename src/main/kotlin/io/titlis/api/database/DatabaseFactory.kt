package io.titlis.api.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.titlis.api.config.DatabaseConfig
import io.titlis.api.database.tables.AppRemediations
import io.titlis.api.database.tables.AppScorecardHistory
import io.titlis.api.database.tables.AppScorecards
import io.titlis.api.database.tables.Clusters
import io.titlis.api.database.tables.Namespaces
import io.titlis.api.database.tables.NotificationLog
import io.titlis.api.database.tables.PillarScoreHistory
import io.titlis.api.database.tables.PillarScores
import io.titlis.api.database.tables.PlatformUserInvites
import io.titlis.api.database.tables.PlatformUsers
import io.titlis.api.database.tables.RemediationHistory
import io.titlis.api.database.tables.RemediationIssues
import io.titlis.api.database.tables.ResourceMetrics
import io.titlis.api.database.tables.ScorecardScores
import io.titlis.api.database.tables.SloComplianceHistory
import io.titlis.api.database.tables.SloConfigPendingChanges
import io.titlis.api.database.tables.SloConfigs
import io.titlis.api.database.tables.TenantAiConfigs
import io.titlis.api.database.tables.TenantApiKeys
import io.titlis.api.database.tables.TenantAuthIntegrations
import io.titlis.api.database.tables.Tenants
import io.titlis.api.database.tables.UserAuthIdentities
import io.titlis.api.database.tables.ValidationResults
import io.titlis.api.database.tables.ValidationRules
import io.titlis.api.database.tables.Workloads
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private lateinit var dataSource: HikariDataSource

    fun init(config: DatabaseConfig) {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            minimumIdle = config.minIdle
            connectionTimeout = config.connectionTimeout
            idleTimeout = config.idleTimeout
            maxLifetime = config.maxLifetime
            driverClassName = "org.postgresql.Driver"
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        dataSource = HikariDataSource(hikariConfig)
        val database = Database.connect(dataSource)

        // Use dedicated autocommit connections for optional DDL so one failure does not
        // poison the Exposed transaction used for table introspection/migration.
        executeOptionalDdl("DROP VIEW IF EXISTS titlis_oltp.v_workload_dashboard CASCADE")
        executeOptionalDdl("DROP VIEW IF EXISTS titlis_oltp.v_slo_framework_detection CASCADE")
        executeOptionalDdl("DROP VIEW IF EXISTS titlis_audit.v_score_evolution CASCADE")
        executeOptionalDdl("DROP VIEW IF EXISTS titlis_oltp.v_top_failing_rules CASCADE")
        executeOptionalDdl("DROP VIEW IF EXISTS titlis_audit.v_remediation_effectiveness CASCADE")
        executeOptionalDdl("CREATE EXTENSION IF NOT EXISTS vector")
        executeOptionalDdl("CREATE SCHEMA IF NOT EXISTS titlis_ai")
        executeOptionalDdl("""
            CREATE TABLE IF NOT EXISTS titlis_ai.knowledge_chunks (
                chunk_id    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                tenant_id   BIGINT       REFERENCES titlis_oltp.tenants(tenant_id) ON DELETE CASCADE,
                source_type TEXT         NOT NULL,
                source_id   TEXT         NOT NULL,
                chunk_text  TEXT         NOT NULL,
                embedding   VECTOR(1536) NOT NULL,
                metadata    JSONB,
                created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
        """)
        executeOptionalDdl("""
            CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_tenant
                ON titlis_ai.knowledge_chunks (tenant_id)
        """)
        executeOptionalDdl("""
            CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_chunks_global_src
                ON titlis_ai.knowledge_chunks (source_type, source_id)
                WHERE tenant_id IS NULL
        """)
        executeOptionalDdl("""
            CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_chunks_tenant_src
                ON titlis_ai.knowledge_chunks (tenant_id, source_type, source_id)
                WHERE tenant_id IS NOT NULL
        """)

        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                // titlis_oltp
                Tenants,
                Clusters,
                Namespaces,
                Workloads,
                ValidationRules,
                AppScorecards,
                PillarScores,
                ValidationResults,
                AppRemediations,
                RemediationIssues,
                SloConfigs,
                SloConfigPendingChanges,
                TenantAiConfigs,
                PlatformUsers,
                TenantAuthIntegrations,
                UserAuthIdentities,
                TenantApiKeys,
                PlatformUserInvites,
                // titlis_audit
                AppScorecardHistory,
                PillarScoreHistory,
                RemediationHistory,
                SloComplianceHistory,
                NotificationLog,
                // titlis_ts
                ResourceMetrics,
                ScorecardScores,
            )
        }

        executeOptionalDdl("""
            ALTER TABLE titlis_oltp.slo_configs
                DROP CONSTRAINT IF EXISTS chk_warning_lt_target
        """)
        executeOptionalDdl("""
            ALTER TABLE titlis_oltp.slo_configs
                DROP CONSTRAINT IF EXISTS chk_warning_gt_target
        """)
        executeOptionalDdl("""
            ALTER TABLE titlis_oltp.slo_configs
                ADD CONSTRAINT chk_warning_gt_target
                CHECK (warning IS NULL OR warning > target)
        """)
        executeOptionalDdl("""
            COMMENT ON COLUMN titlis_oltp.slo_configs.warning
                IS 'Limiar de aviso; deve ser maior que target'
        """)

        executeOptionalDdl("""
                CREATE OR REPLACE VIEW titlis_oltp.v_workload_dashboard AS
                SELECT w.workload_id,
                    c.cluster_name,
                    c.environment,
                    c.tenant_id,
                    n.namespace_name AS namespace,
                    w.workload_name,
                    w.workload_kind,
                    w.service_tier,
                    w.owner_team,
                    sc.overall_score,
                    sc.compliance_status,
                    sc.passed_rules,
                    sc.failed_rules,
                    sc.critical_failures,
                    sc.version AS scorecard_version,
                    sc.evaluated_at,
                    ar.app_remediation_status AS remediation_status,
                    ar.github_pr_url,
                    ar.github_pr_number,
                    sc.updated_at AS last_scored_at
                FROM titlis_oltp.workloads w
                    JOIN titlis_oltp.namespaces n ON n.namespace_id = w.namespace_id
                    JOIN titlis_oltp.clusters c ON c.cluster_id = n.cluster_id
                    LEFT JOIN titlis_oltp.app_scorecards sc ON sc.workload_id = w.workload_id
                    LEFT JOIN titlis_oltp.app_remediations ar ON ar.workload_id = w.workload_id
                WHERE w.is_active = true AND n.is_excluded = false
            """)

        executeOptionalDdl("""
                CREATE OR REPLACE VIEW titlis_oltp.v_slo_framework_detection AS
                SELECT n.namespace_name AS namespace,
                    sc.slo_config_name AS slo_name,
                    sc.slo_type,
                    sc.auto_detect_framework,
                    sc.app_framework AS explicit_framework,
                    sc.detected_framework,
                    sc.detection_source,
                    sc.datadog_slo_id,
                    sc.datadog_slo_state,
                    sc.last_sync_at,
                    sc.sync_error
                FROM titlis_oltp.slo_configs sc
                    JOIN titlis_oltp.namespaces n ON n.namespace_id = sc.namespace_id
                ORDER BY (sc.detection_source::text = 'fallback'::text) DESC, sc.last_sync_at DESC NULLS LAST
            """)

        executeOptionalDdl("""
                CREATE OR REPLACE VIEW titlis_audit.v_score_evolution AS
                SELECT app_scorecard_history.workload_id,
                    app_scorecard_history.scorecard_version,
                    app_scorecard_history.overall_score,
                    app_scorecard_history.compliance_status,
                    app_scorecard_history.passed_rules,
                    app_scorecard_history.failed_rules,
                    app_scorecard_history.evaluated_at,
                    app_scorecard_history.overall_score - lag(app_scorecard_history.overall_score)
                        OVER (PARTITION BY app_scorecard_history.workload_id ORDER BY app_scorecard_history.evaluated_at) AS score_delta
                FROM titlis_audit.app_scorecard_history
                ORDER BY app_scorecard_history.workload_id, app_scorecard_history.evaluated_at DESC
            """)

        executeOptionalDdl("""
                CREATE OR REPLACE VIEW titlis_audit.v_top_failing_rules AS
                SELECT vr.value ->> 'rule_ref' AS rule_id,
                    vr.value ->> 'pillar' AS pillar,
                    vr.value ->> 'severity' AS severity,
                    count(*) AS total_failures,
                    count(DISTINCT h.workload_id) AS affected_workloads,
                    max(h.evaluated_at) AS last_seen
                FROM titlis_audit.app_scorecard_history h,
                    LATERAL jsonb_array_elements(h.validation_results) vr(value)
                WHERE ((vr.value ->> 'passed')::boolean) = false
                GROUP BY (vr.value ->> 'rule_ref'), (vr.value ->> 'pillar'), (vr.value ->> 'severity')
                ORDER BY count(*) DESC
            """)

        executeOptionalDdl("""
                CREATE OR REPLACE VIEW titlis_audit.v_remediation_effectiveness AS
                SELECT remediation_history.workload_id,
                    count(*) AS total_attempts,
                    count(*) FILTER (WHERE remediation_history.app_remediation_status::text = 'PR_MERGED') AS successful,
                    count(*) FILTER (WHERE remediation_history.app_remediation_status::text = 'FAILED') AS failed,
                    round(100.0 * count(*) FILTER (WHERE remediation_history.app_remediation_status::text = 'PR_MERGED')::numeric
                        / NULLIF(count(*), 0)::numeric, 2) AS success_rate_pct,
                    max(remediation_history.triggered_at) AS last_attempt_at
                FROM titlis_audit.remediation_history
                GROUP BY remediation_history.workload_id
            """)
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    private fun executeOptionalDdl(sql: String) {
        try {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = true
                try {
                    connection.createStatement().use { statement ->
                        statement.execute(sql)
                    }
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        } catch (e: Exception) {
            log.warn("DDL skipped (insufficient privileges — expected in production): ${e.message}")
        }
    }
}
