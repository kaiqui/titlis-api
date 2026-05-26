package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.Clusters
import io.titlis.api.database.tables.Namespaces
import io.titlis.api.database.tables.SloComplianceHistory
import io.titlis.api.database.tables.SloConfigPendingChanges
import io.titlis.api.database.tables.SloConfigs
import io.titlis.api.database.tables.Workloads
import io.titlis.api.domain.SloReconciledEvent
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SloRepository {
    suspend fun upsertSloConfig(event: SloReconciledEvent, tenantIdHint: Long? = null) = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val tenantId = chooseTenantId(
            trustedTenantId = tenantIdHint,
            derivedTenantId = resolveSingleActiveTenantIdOrNull(),
        )
        val namespaceIdValue = ensureNamespace(event.cluster, event.environment, event.namespace, now, tenantId)

        SloConfigs.upsert(
            SloConfigs.namespaceId,
            SloConfigs.sloConfigName,
            onUpdateExclude = listOf(SloConfigs.createdAt),
        ) {
            it[SloConfigs.namespaceId] = namespaceIdValue
            it[SloConfigs.tenantId] = tenantId
            it[SloConfigs.sloConfigName] = event.sloName
            it[SloConfigs.sloType] = event.sloType
            it[SloConfigs.timeframe] = event.timeframe
            it[SloConfigs.target] = event.target.toBigDecimal()
            it[SloConfigs.warning] = event.warning?.toBigDecimal()
            it[SloConfigs.autoDetectFramework] = event.autoDetectFramework
            it[SloConfigs.appFramework] = event.appFramework
            it[SloConfigs.detectedFramework] = event.detectedFramework
            it[SloConfigs.detectionSource] = event.detectionSource
            it[SloConfigs.k8sResourceUid] = event.k8sResourceUid ?: event.sloConfigId
            it[SloConfigs.datadogSloId] = event.datadogSloId
            it[SloConfigs.datadogSloState] = event.datadogSloState
            it[SloConfigs.syncError] = event.syncError
            it[SloConfigs.lastSyncAt] = now
            it[SloConfigs.createdAt] = now
            it[SloConfigs.updatedAt] = now
        }

        val resolvedSloConfigId = SloConfigs
            .select(SloConfigs.sloConfigId)
            .where {
                (SloConfigs.namespaceId eq namespaceIdValue) and
                    (SloConfigs.sloConfigName eq event.sloName)
            }
            .single()[SloConfigs.sloConfigId]

        SloComplianceHistory.insert {
            it[SloComplianceHistory.sloConfigId] = resolvedSloConfigId
            it[SloComplianceHistory.namespaceId] = namespaceIdValue
            it[SloComplianceHistory.tenantId] = tenantId
            it[SloComplianceHistory.sloConfigName] = event.sloName
            it[SloComplianceHistory.sloType] = event.sloType
            it[SloComplianceHistory.timeframe] = event.timeframe
            it[SloComplianceHistory.target] = event.target.toBigDecimal()
            it[SloComplianceHistory.actualValue] = event.actualValue?.toBigDecimal()
            it[SloComplianceHistory.sloState] = event.datadogSloState
            it[SloComplianceHistory.syncAction] = event.syncAction
            it[SloComplianceHistory.syncError] = event.syncError
            it[SloComplianceHistory.datadogSloId] = event.datadogSloId
            it[SloComplianceHistory.detectedFramework] = event.detectedFramework
            it[SloComplianceHistory.detectionSource] = event.detectionSource
            it[SloComplianceHistory.recordedAt] = now
        }
    }

    suspend fun getByName(namespace: String, name: String, tenantId: Long): Map<String, Any?>? = dbQuery {
        (SloConfigs innerJoin Namespaces)
            .select(SloConfigs.columns)
            .where {
                (SloConfigs.sloConfigName eq name) and
                    (Namespaces.namespaceName eq namespace) and
                    (SloConfigs.tenantId eq tenantId) and
                    (Namespaces.isExcluded eq false)
            }
            .singleOrNull()
            ?.let { row ->
                mapOf(
                    "slo_config_id"     to row[SloConfigs.sloConfigId],
                    "slo_type"          to row[SloConfigs.sloType],
                    "timeframe"         to row[SloConfigs.timeframe],
                    "target"            to row[SloConfigs.target],
                    "datadog_slo_id"    to row[SloConfigs.datadogSloId],
                    "datadog_slo_state" to row[SloConfigs.datadogSloState],
                    "detected_framework" to row[SloConfigs.detectedFramework],
                    "detection_source"  to row[SloConfigs.detectionSource],
                    "last_sync_at"      to row[SloConfigs.lastSyncAt]?.toString(),
                )
            }
    }

    suspend fun list(tenantId: Long, namespace: String?, cluster: String?): List<Map<String, Any?>> = dbQuery {
        val query = (SloConfigs innerJoin Namespaces innerJoin Clusters)
            .selectAll()
            .apply {
                andWhere { SloConfigs.tenantId eq tenantId }
                andWhere { Namespaces.isExcluded eq false }
                if (!namespace.isNullOrBlank()) {
                    andWhere { Namespaces.namespaceName eq namespace }
                }
                if (!cluster.isNullOrBlank()) {
                    andWhere { Clusters.clusterName eq cluster }
                }
            }
            .orderBy(SloConfigs.lastSyncAt, SortOrder.DESC)

        query.map { row ->
            mapOf(
                "slo_config_id"      to row[SloConfigs.sloConfigId],
                "name"               to row[SloConfigs.sloConfigName],
                "namespace"          to row[Namespaces.namespaceName],
                "cluster"            to row[Clusters.clusterName],
                "environment"        to row[Clusters.environment],
                "slo_type"           to row[SloConfigs.sloType],
                "timeframe"          to row[SloConfigs.timeframe],
                "target"             to row[SloConfigs.target],
                "warning"            to row[SloConfigs.warning],
                "datadog_slo_id"     to row[SloConfigs.datadogSloId],
                "datadog_slo_state"  to row[SloConfigs.datadogSloState],
                "detected_framework" to row[SloConfigs.detectedFramework],
                "detection_source"   to row[SloConfigs.detectionSource],
                "last_sync_at"       to row[SloConfigs.lastSyncAt]?.toString(),
                "sync_error"         to row[SloConfigs.syncError],
                "auto_detect_framework" to row[SloConfigs.autoDetectFramework],
            )
        }
    }

    suspend fun listPendingChanges(tenantId: Long): List<Map<String, Any?>> = dbQuery {
        SloConfigPendingChanges
            .selectAll()
            .where {
                (SloConfigPendingChanges.tenantId eq tenantId) and
                    (SloConfigPendingChanges.status eq "pending")
            }
            .orderBy(SloConfigPendingChanges.createdAt, SortOrder.ASC)
            .map { row ->
                mapOf(
                    "id"              to row[SloConfigPendingChanges.id].toString(),
                    "slo_config_name" to row[SloConfigPendingChanges.sloConfigName],
                    "namespace"       to row[SloConfigPendingChanges.namespace],
                    "field"           to row[SloConfigPendingChanges.field],
                    "old_value"       to row[SloConfigPendingChanges.oldValue],
                    "new_value"       to row[SloConfigPendingChanges.newValue],
                    "requested_by"    to row[SloConfigPendingChanges.requestedBy],
                    "status"          to row[SloConfigPendingChanges.status],
                    "created_at"      to row[SloConfigPendingChanges.createdAt].toString(),
                )
            }
    }

    suspend fun markChangeApplied(id: String, tenantId: Long): Boolean = dbQuery {
        val changeId = runCatching { UUID.fromString(id) }.getOrNull() ?: return@dbQuery false
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        SloConfigPendingChanges.update({
            (SloConfigPendingChanges.id eq changeId) and
                (SloConfigPendingChanges.tenantId eq tenantId) and
                (SloConfigPendingChanges.status eq "pending")
        }) {
            it[status] = "applied"
            it[appliedAt] = now
        } > 0
    }

    suspend fun markChangeFailed(id: String, errorMsg: String, tenantId: Long): Boolean = dbQuery {
        val changeId = runCatching { UUID.fromString(id) }.getOrNull() ?: return@dbQuery false
        SloConfigPendingChanges.update({
            (SloConfigPendingChanges.id eq changeId) and
                (SloConfigPendingChanges.tenantId eq tenantId) and
                (SloConfigPendingChanges.status eq "pending")
        }) {
            it[status] = "failed"
            it[error] = errorMsg
        } > 0
    }

    suspend fun proposeChange(
        sloConfigId: Long,
        tenantId: Long,
        field: String,
        oldValue: String,
        newValue: String,
        requestedBy: String,
    ): Map<String, Any?>? = dbQuery {
        val sloConfig = (SloConfigs innerJoin Namespaces)
            .select(SloConfigs.sloConfigName, Namespaces.namespaceName)
            .where {
                (SloConfigs.sloConfigId eq sloConfigId) and
                    (SloConfigs.tenantId eq tenantId)
            }
            .singleOrNull() ?: return@dbQuery null

        val configName = sloConfig[SloConfigs.sloConfigName]
        val namespaceName = sloConfig[Namespaces.namespaceName]
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val newId = UUID.randomUUID()

        SloConfigPendingChanges.insert {
            it[SloConfigPendingChanges.id] = newId
            it[SloConfigPendingChanges.tenantId] = tenantId
            it[SloConfigPendingChanges.sloConfigName] = configName
            it[SloConfigPendingChanges.namespace] = namespaceName
            it[SloConfigPendingChanges.field] = field
            it[SloConfigPendingChanges.oldValue] = oldValue
            it[SloConfigPendingChanges.newValue] = newValue
            it[SloConfigPendingChanges.requestedBy] = requestedBy
            it[SloConfigPendingChanges.status] = "pending"
            it[SloConfigPendingChanges.createdAt] = now
        }

        mapOf(
            "id"              to newId.toString(),
            "slo_config_name" to configName,
            "namespace"       to namespaceName,
            "field"           to field,
            "old_value"       to oldValue,
            "new_value"       to newValue,
            "requested_by"    to requestedBy,
            "status"          to "pending",
            "created_at"      to now.toString(),
        )
    }

    private fun ensureNamespace(
        clusterNameValue: String,
        environmentValue: String,
        namespaceNameValue: String,
        now: OffsetDateTime,
        tenantIdHint: Long?,
    ): Long {
        val tenantId = chooseTenantId(
            trustedTenantId = tenantIdHint,
            derivedTenantId = resolveSingleActiveTenantIdOrNull(),
        ) ?: error("Não foi possível resolver tenant_id para o evento slo_reconciled")
        Clusters.upsert(
            Clusters.clusterName,
            Clusters.tenantId,
            onUpdateExclude = listOf(Clusters.createdAt),
        ) {
            it[Clusters.clusterName] = clusterNameValue
            it[Clusters.tenantId] = tenantId
            it[Clusters.environment] = environmentValue
            it[Clusters.isActive] = true
            it[Clusters.createdAt] = now
            it[Clusters.updatedAt] = now
        }
        val clusterIdValue = Clusters
            .select(Clusters.clusterId)
            .where { (Clusters.clusterName eq clusterNameValue) and (Clusters.tenantId eq tenantId) }
            .single()[Clusters.clusterId]

        Namespaces.upsert(
            Namespaces.clusterId,
            Namespaces.namespaceName,
            onUpdateExclude = listOf(Namespaces.createdAt),
        ) {
            it[Namespaces.clusterId] = clusterIdValue
            it[Namespaces.namespaceName] = namespaceNameValue
            it[Namespaces.createdAt] = now
            it[Namespaces.updatedAt] = now
        }

        return Namespaces
            .select(Namespaces.namespaceId)
            .where {
                (Namespaces.clusterId eq clusterIdValue) and
                    (Namespaces.namespaceName eq namespaceNameValue)
            }
            .single()[Namespaces.namespaceId]
    }

    /**
     * Returns coverage data for all active workloads of a tenant.
     * Each workload is classified as:
     *   WITH_SLO   — has at least one slo_config entry
     *   CANDIDATE  — dd_git_repository_url != null (registered in Datadog), but no slo_config
     *   NO_DATADOG — dd_git_repository_url = null and no slo_config
     *
     * Ordered: workloads without SLO first (CANDIDATE before NO_DATADOG), then alphabetically.
     */
    suspend fun coverage(tenantId: Long): List<Map<String, Any?>> = dbQuery {
        Workloads
            .join(Namespaces, JoinType.INNER, Workloads.namespaceId, Namespaces.namespaceId)
            .join(Clusters, JoinType.INNER, Namespaces.clusterId, Clusters.clusterId)
            .join(SloConfigs, JoinType.LEFT,
                onColumn = Workloads.namespaceId,
                otherColumn = SloConfigs.namespaceId,
                additionalConstraint = { SloConfigs.tenantId eq tenantId },
            )
            .select(
                Workloads.workloadId,
                Workloads.workloadName,
                Workloads.k8sUid,
                Workloads.ddGitRepositoryUrl,
                Namespaces.namespaceName,
                Clusters.clusterName,
                Clusters.environment,
                SloConfigs.sloConfigId,
                SloConfigs.datadogSloState,
                SloConfigs.lastSyncAt,
            )
            .where { (Clusters.tenantId eq tenantId) and (Workloads.isActive eq true) }
            .orderBy(SloConfigs.sloConfigId, SortOrder.ASC_NULLS_FIRST)
            .orderBy(Workloads.workloadName, SortOrder.ASC)
            .map { row ->
                // LEFT JOIN: sloConfigId is null when no SLO config exists for this namespace.
                // row.getOrNull() avoids the "condition always true" warning from the compiler.
                val sloConfigId = runCatching { row[SloConfigs.sloConfigId] }.getOrNull()
                val hasSlo      = sloConfigId != null
                val hasDatadog  = row[Workloads.ddGitRepositoryUrl] != null
                val sloStatus   = when {
                    hasSlo     -> "WITH_SLO"
                    hasDatadog -> "CANDIDATE"
                    else       -> "NO_DATADOG"
                }
                mapOf(
                    "workload_id"           to row[Workloads.workloadId].toString(),
                    "name"                  to row[Workloads.workloadName],
                    "k8s_uid"               to row[Workloads.k8sUid],
                    "namespace"             to row[Namespaces.namespaceName],
                    "cluster"               to row[Clusters.clusterName],
                    "environment"           to row[Clusters.environment],
                    "slo_status"            to sloStatus,
                    "slo_config_id"         to sloConfigId?.toString(),
                    "datadog_slo_state"     to row.getOrNull(SloConfigs.datadogSloState),
                    "last_sync_at"          to row.getOrNull(SloConfigs.lastSyncAt)?.toString(),
                    "dd_git_repository_url" to row[Workloads.ddGitRepositoryUrl],
                )
            }
            .distinctBy { it["k8s_uid"] } // deduplicate when workload has multiple SLOs
    }

    /**
     * Returns a pair of (hasSlo, sloHealthy) for the given namespace.
     * hasSlo = at least one slo_config row exists for the namespace.
     * sloHealthy = at least one slo_config has datadog_slo_state = 'synced' (not 'error').
     * Returns (false, false) quickly when the namespace_id is unknown (workload not yet registered).
     */
    suspend fun sloPresenceForNamespace(
        clusterName: String,
        namespaceName: String,
        tenantId: Long,
    ): Pair<Boolean, Boolean> = dbQuery {
        val clusterId = Clusters
            .select(Clusters.clusterId)
            .where { (Clusters.clusterName eq clusterName) and (Clusters.tenantId eq tenantId) }
            .singleOrNull()
            ?.get(Clusters.clusterId) ?: return@dbQuery Pair(false, false)

        val namespaceId = Namespaces
            .select(Namespaces.namespaceId)
            .where { (Namespaces.clusterId eq clusterId) and (Namespaces.namespaceName eq namespaceName) }
            .singleOrNull()
            ?.get(Namespaces.namespaceId) ?: return@dbQuery Pair(false, false)

        val rows = SloConfigs
            .select(SloConfigs.datadogSloState)
            .where { (SloConfigs.namespaceId eq namespaceId) and (SloConfigs.tenantId eq tenantId) }
            .toList()

        val hasSlo = rows.isNotEmpty()
        val sloHealthy = rows.any { it[SloConfigs.datadogSloState] == "synced" }
        Pair(hasSlo, sloHealthy)
    }
}
