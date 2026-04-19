package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.AppRemediations
import io.titlis.api.database.tables.RemediationHistory
import io.titlis.api.database.tables.Workloads
import io.titlis.api.domain.RemediationEvent
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset

class RemediationRepository {

    // Resolve k8s_uid para workload_id (Long BIGINT IDENTITY).
    private fun resolveWorkloadId(k8sUid: String): Long =
        Workloads
            .select(Workloads.workloadId)
            .where { Workloads.k8sUid eq k8sUid }
            .singleOrNull()?.get(Workloads.workloadId)
            ?: error("Workload não encontrado para k8s_uid=$k8sUid")

    suspend fun upsertRemediation(event: RemediationEvent, tenantIdHint: Long? = null) = dbQuery {
        val workloadId = resolveWorkloadId(event.workloadId)
        val tenantId = chooseTenantId(
            trustedTenantId = tenantIdHint,
            derivedTenantId = resolveTenantIdByWorkloadId(workloadId) ?: resolveSingleActiveTenantIdOrNull(),
        )
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        // SCD Type 4 — a aplicação registra transição de estado em remediation_history (sem triggers DML).
        val existing = AppRemediations
            .select(AppRemediations.appRemediationStatus, AppRemediations.version)
            .where { AppRemediations.workloadId eq workloadId }
            .singleOrNull()

        if (
            existing == null ||
            existing[AppRemediations.appRemediationStatus] != event.status ||
            existing[AppRemediations.version] != event.version
        ) {
            RemediationHistory.insert {
                it[RemediationHistory.workloadId] = workloadId
                it[RemediationHistory.tenantId] = tenantId
                it[RemediationHistory.remediationVersion] = event.version
                it[RemediationHistory.appRemediationStatus] = event.status
                it[RemediationHistory.previousAppRemediationStatus] =
                    event.previousStatus ?: existing?.get(AppRemediations.appRemediationStatus)
                it[RemediationHistory.scorecardVersion] = event.scorecardVersion
                it[RemediationHistory.githubPrNumber] = event.githubPrNumber
                it[RemediationHistory.githubPrUrl] = event.githubPrUrl?.take(500)
                it[RemediationHistory.githubBranch] = event.githubBranch?.take(255)
                it[RemediationHistory.repositoryUrl] = event.repositoryUrl?.take(500)
                it[RemediationHistory.issuesSnapshot] = event.issuesSnapshot?.toString()
                it[RemediationHistory.errorMessage] = event.errorMessage
                it[RemediationHistory.triggeredAt] =
                    OffsetDateTime.parse(event.triggeredAt)
                it[RemediationHistory.resolvedAt] = event.resolvedAt?.let { ts ->
                    OffsetDateTime.parse(ts)
                }
                it[RemediationHistory.createdAt] = now
            }
        }

        AppRemediations.upsert(
            AppRemediations.workloadId,
            onUpdateExclude = listOf(AppRemediations.createdAt),
        ) {
            it[AppRemediations.workloadId]    = workloadId
            it[AppRemediations.tenantId]      = tenantId
            it[AppRemediations.version]       = event.version
            it[AppRemediations.appRemediationStatus] = event.status
            it[AppRemediations.githubPrNumber] = event.githubPrNumber
            it[AppRemediations.githubPrTitle] = event.githubPrTitle?.take(500)
            it[AppRemediations.githubPrUrl]   = event.githubPrUrl?.take(500)
            it[AppRemediations.githubBranch]  = event.githubBranch?.take(255)
            it[AppRemediations.repositoryUrl] = event.repositoryUrl?.take(500)
            it[AppRemediations.errorMessage]  = event.errorMessage
            it[AppRemediations.triggeredAt]   =
                OffsetDateTime.parse(event.triggeredAt)
            it[AppRemediations.resolvedAt]    = event.resolvedAt?.let { ts ->
                OffsetDateTime.parse(ts)
            }
            it[AppRemediations.createdAt]     = now
            it[AppRemediations.updatedAt]     = now
        }
    }

    suspend fun notifyRemediationStarted(
        k8sUid: String,
        tenantId: Long,
        prUrl: String?,
        prNumber: Int?,
        githubBranch: String?,
        repositoryUrl: String?,
        findingIds: List<String>,
    ) = dbQuery {
        val workloadId = resolveWorkloadId(k8sUid)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val pendingJson = buildJsonArray { findingIds.forEach { add(it) } }.toString()
        AppRemediations.upsert(
            AppRemediations.workloadId,
            onUpdateExclude = listOf(AppRemediations.createdAt),
        ) {
            it[AppRemediations.workloadId]          = workloadId
            it[AppRemediations.tenantId]            = tenantId
            it[AppRemediations.version]             = 1
            it[AppRemediations.appRemediationStatus] = "IN_PROGRESS"
            it[AppRemediations.githubPrNumber]      = prNumber
            it[AppRemediations.githubPrUrl]         = prUrl?.take(500)
            it[AppRemediations.githubBranch]        = githubBranch?.take(255)
            it[AppRemediations.repositoryUrl]       = repositoryUrl?.take(500)
            it[AppRemediations.pendingRuleIds]      = pendingJson
            it[AppRemediations.triggeredAt]         = now
            it[AppRemediations.createdAt]           = now
            it[AppRemediations.updatedAt]           = now
        }
    }

    suspend fun getHistory(k8sUid: String, tenantId: Long): List<Map<String, Any?>> = dbQuery {
        val workloadId = tenantScopedWorkloadRow(k8sUid, tenantId)
            ?.get(Workloads.workloadId)
            ?: return@dbQuery emptyList()

        RemediationHistory
            .select(
                RemediationHistory.appRemediationStatus,
                RemediationHistory.previousAppRemediationStatus,
                RemediationHistory.remediationVersion,
                RemediationHistory.githubPrNumber,
                RemediationHistory.githubPrUrl,
                RemediationHistory.githubBranch,
                RemediationHistory.repositoryUrl,
                RemediationHistory.triggeredAt,
                RemediationHistory.resolvedAt,
            )
            .where {
                (RemediationHistory.workloadId eq workloadId) and
                    (RemediationHistory.tenantId eq tenantId)
            }
            .orderBy(RemediationHistory.triggeredAt, SortOrder.DESC)
            .limit(20)
            .map { row ->
                mapOf(
                    "status" to row[RemediationHistory.appRemediationStatus],
                    "previous_status" to row[RemediationHistory.previousAppRemediationStatus],
                    "version" to row[RemediationHistory.remediationVersion],
                    "github_pr_number" to row[RemediationHistory.githubPrNumber],
                    "github_pr_url" to row[RemediationHistory.githubPrUrl],
                    "github_branch" to row[RemediationHistory.githubBranch],
                    "repository_url" to row[RemediationHistory.repositoryUrl],
                    "triggered_at" to row[RemediationHistory.triggeredAt].toString(),
                    "resolved_at" to row[RemediationHistory.resolvedAt]?.toString(),
                )
            }
    }

    suspend fun getByWorkload(k8sUid: String, tenantId: Long): Map<String, Any?>? = dbQuery {
        val workloadId = tenantScopedWorkloadRow(k8sUid, tenantId)
            ?.get(Workloads.workloadId)
            ?: return@dbQuery null

        AppRemediations
            .select(AppRemediations.columns)
            .where {
                (AppRemediations.workloadId eq workloadId) and
                    (AppRemediations.tenantId eq tenantId)
            }
            .singleOrNull()
            ?.let { row ->
                mapOf(
                    "status" to row[AppRemediations.appRemediationStatus],
                    "version" to row[AppRemediations.version],
                    "github_pr_url" to row[AppRemediations.githubPrUrl],
                    "github_pr_number" to row[AppRemediations.githubPrNumber],
                    "triggered_at" to row[AppRemediations.triggeredAt].toString(),
                )
            }
    }
}
