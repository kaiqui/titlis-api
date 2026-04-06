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
import io.titlis.api.database.tables.SloConfigs
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
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init(config: DatabaseConfig) {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            connectionTimeout = config.connectionTimeout
            idleTimeout = config.idleTimeout
            driverClassName = "org.postgresql.Driver"
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        val database = Database.connect(HikariDataSource(hikariConfig))
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
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
