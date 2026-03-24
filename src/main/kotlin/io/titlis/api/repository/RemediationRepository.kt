package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.AppRemediations
import io.titlis.api.database.tables.RemediationHistory
import io.titlis.api.database.tables.Workloads
import io.titlis.api.domain.RemediationEvent
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

class RemediationRepository {

    // Resolve k8s_uid para workload_id (Long BIGINT IDENTITY).
    private fun resolveWorkloadId(k8sUid: String): Long =
        Workloads
            .select(Workloads.workloadId)
            .where { Workloads.k8sUid eq k8sUid }
            .singleOrNull()?.get(Workloads.workloadId)
            ?: error("Workload não encontrado para k8s_uid=$k8sUid")

    suspend fun upsertRemediation(event: RemediationEvent) = dbQuery {
        val workloadId = resolveWorkloadId(event.workloadId)
        val now = Instant.now().toKotlinInstant()

        // SCD Type 4 — a aplicação registra transição de estado em remediation_history (sem triggers DML).
        val existing = AppRemediations
            .select(AppRemediations.appRemediationStatus, AppRemediations.version)
            .where { AppRemediations.workloadId eq workloadId }
            .singleOrNull()

        if (existing != null &&
            existing[AppRemediations.appRemediationStatus] != event.status) {
            RemediationHistory.insert {
                it[RemediationHistory.workloadId]                   = workloadId
                it[remediationVersion]                              = event.version
                it[appRemediationStatus]                            = event.status
                it[previousAppRemediationStatus]                    = existing[AppRemediations.appRemediationStatus]
                it[createdAt]                                       = now
            }
        }

        AppRemediations.upsert(AppRemediations.workloadId) {
            it[AppRemediations.workloadId]    = workloadId
            it[version]                       = event.version
            it[appRemediationStatus]          = event.status
            it[githubPrNumber]                = event.githubPrNumber
            it[githubPrUrl]                   = event.githubPrUrl?.take(500)
            it[githubBranch]                  = event.githubBranch?.take(255)
            it[repositoryUrl]                 = event.repositoryUrl?.take(500)
            it[errorMessage]                  = event.errorMessage
            it[triggeredAt]                   = Instant.parse(event.triggeredAt).toKotlinInstant()
            it[resolvedAt]                    = event.resolvedAt?.let { ts -> Instant.parse(ts).toKotlinInstant() }
            it[updatedAt]                     = now
        }
    }

    suspend fun getByWorkload(k8sUid: String): Map<String, Any?>? = dbQuery {
        val workloadId = Workloads
            .select(Workloads.workloadId)
            .where { Workloads.k8sUid eq k8sUid }
            .singleOrNull()?.get(Workloads.workloadId) ?: return@dbQuery null

        AppRemediations
            .select(AppRemediations.columns)
            .where { AppRemediations.workloadId eq workloadId }
            .singleOrNull()
            ?.let { row ->
                mapOf(
                    "status"          to row[AppRemediations.appRemediationStatus],
                    "version"         to row[AppRemediations.version],
                    "github_pr_url"   to row[AppRemediations.githubPrUrl],
                    "github_pr_number" to row[AppRemediations.githubPrNumber],
                    "triggered_at"    to row[AppRemediations.triggeredAt].toString(),
                )
            }
    }
}