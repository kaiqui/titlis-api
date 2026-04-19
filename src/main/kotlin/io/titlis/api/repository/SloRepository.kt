package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.Clusters
import io.titlis.api.database.tables.Namespaces
import io.titlis.api.database.tables.SloComplianceHistory
import io.titlis.api.database.tables.SloConfigPendingChanges
import io.titlis.api.database.tables.SloConfigs
import io.titlis.api.domain.SloReconciledEvent
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
                    (SloConfigs.tenantId eq tenantId)
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
                "slo_config_id" to row[SloConfigs.sloConfigId],
                "name" to row[SloConfigs.sloConfigName],
                "namespace" to row[Namespaces.namespaceName],
                "cluster" to row[Clusters.clusterName],
                "environment" to row[Clusters.environment],
                "slo_type" to row[SloConfigs.sloType],
                "timeframe" to row[SloConfigs.timeframe],
                "target" to row[SloConfigs.target],
                "warning" to row[SloConfigs.warning],
                "datadog_slo_id" to row[SloConfigs.datadogSloId],
                "datadog_slo_state" to row[SloConfigs.datadogSloState],
                "detected_framework" to row[SloConfigs.detectedFramework],
                "detection_source" to row[SloConfigs.detectionSource],
                "last_sync_at" to row[SloConfigs.lastSyncAt]?.toString(),
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
}
