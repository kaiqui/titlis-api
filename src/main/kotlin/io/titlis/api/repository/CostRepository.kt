package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.Clusters
import io.titlis.api.database.tables.NamespaceCostMetrics
import io.titlis.api.database.tables.Namespaces
import io.titlis.api.database.tables.ResourceMetrics
import io.titlis.api.database.tables.WorkloadCostMetrics
import io.titlis.api.database.tables.Workloads
import kotlinx.serialization.Serializable
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class WorkloadCostIngest(
    val workloadId: Long,
    val name: String,
    val namespace: String,
    val clusterName: String,
    val team: String? = null,
    val computeCost: Double,
    val storageCost: Double = 0.0,
    val networkCost: Double = 0.0,
    val totalCost: Double,
    val currency: String = "USD",
)

@Serializable
data class NamespaceCostIngest(
    val namespace: String,
    val clusterName: String,
    val totalCost: Double,
    val rawClusterCost: Double,
    val workloadCount: Int,
    val currency: String = "USD",
)

@Serializable
data class WorkloadRefDTO(
    val workloadId: Long,
    val name: String,
    val namespace: String,
    val clusterName: String,
    val team: String?,
    val cpuRequestMillicores: Double,
    val memRequestMib: Double,
)

class CostRepository {

    suspend fun insertWorkloadCostBatch(
        tenantId: Long,
        date: LocalDate,
        provider: String,
        entries: List<WorkloadCostIngest>,
    ): Int = dbQuery {
        var inserted = 0
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        for (e in entries) {
            WorkloadCostMetrics.upsert(
                WorkloadCostMetrics.workloadId,
                WorkloadCostMetrics.collectedDate,
                WorkloadCostMetrics.provider,
                onUpdateExclude = listOf(WorkloadCostMetrics.collectedAt),
            ) {
                it[WorkloadCostMetrics.workloadId]       = e.workloadId
                it[WorkloadCostMetrics.tenantId]         = tenantId
                it[WorkloadCostMetrics.namespace]        = e.namespace
                it[WorkloadCostMetrics.clusterName]      = e.clusterName
                it[WorkloadCostMetrics.workloadName]     = e.name
                it[WorkloadCostMetrics.team]             = e.team
                it[WorkloadCostMetrics.collectedDate]    = date
                it[WorkloadCostMetrics.provider]         = provider
                it[WorkloadCostMetrics.currency]         = e.currency
                it[WorkloadCostMetrics.computeCost]      = e.computeCost.toBigDecimal()
                it[WorkloadCostMetrics.storageCost]      = e.storageCost.toBigDecimal()
                it[WorkloadCostMetrics.networkCost]      = e.networkCost.toBigDecimal()
                it[WorkloadCostMetrics.totalCost]        = e.totalCost.toBigDecimal()
                it[WorkloadCostMetrics.collectedAt]      = now
            }
            inserted++
        }
        inserted
    }

    suspend fun insertNamespaceCostBatch(
        tenantId: Long,
        date: LocalDate,
        provider: String,
        entries: List<NamespaceCostIngest>,
    ): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        for (e in entries) {
            NamespaceCostMetrics.upsert(
                NamespaceCostMetrics.tenantId,
                NamespaceCostMetrics.namespace,
                NamespaceCostMetrics.clusterName,
                NamespaceCostMetrics.collectedDate,
                NamespaceCostMetrics.provider,
                onUpdateExclude = listOf(NamespaceCostMetrics.collectedAt),
            ) {
                it[NamespaceCostMetrics.tenantId]       = tenantId
                it[NamespaceCostMetrics.namespace]      = e.namespace
                it[NamespaceCostMetrics.clusterName]    = e.clusterName
                it[NamespaceCostMetrics.collectedDate]  = date
                it[NamespaceCostMetrics.provider]       = provider
                it[NamespaceCostMetrics.currency]       = e.currency
                it[NamespaceCostMetrics.totalCost]      = e.totalCost.toBigDecimal()
                it[NamespaceCostMetrics.rawClusterCost] = e.rawClusterCost.toBigDecimal()
                it[NamespaceCostMetrics.workloadCount]  = e.workloadCount
                it[NamespaceCostMetrics.collectedAt]    = now
            }
        }
    }

    suspend fun getWorkloadRefs(tenantId: Long): List<WorkloadRefDTO> = dbQuery {
        val workloads = (Workloads innerJoin Namespaces innerJoin Clusters)
            .select(
                Workloads.workloadId,
                Workloads.workloadName,
                Namespaces.namespaceName,
                Clusters.clusterName,
                Workloads.ownerTeam,
                Workloads.team,
            )
            .where {
                (Clusters.tenantId eq tenantId) and
                (Workloads.isActive eq true) and
                (Namespaces.isExcluded eq false)
            }
            .toList()

        if (workloads.isEmpty()) return@dbQuery emptyList()

        val workloadIds = workloads.map { it[Workloads.workloadId] }.toSet()

        // Fetch latest metric per workload (one query, order by desc + group in memory)
        val metricsMap = ResourceMetrics
            .select(ResourceMetrics.workloadId, ResourceMetrics.cpuAvgMillicores, ResourceMetrics.memAvgMib, ResourceMetrics.collectedAt)
            .where { ResourceMetrics.workloadId inList workloadIds }
            .orderBy(ResourceMetrics.collectedAt, SortOrder.DESC)
            .fold(mutableMapOf<Long, Pair<Double, Double>>()) { acc, row ->
                val wId = row[ResourceMetrics.workloadId]
                if (!acc.containsKey(wId)) {
                    acc[wId] = Pair(
                        row[ResourceMetrics.cpuAvgMillicores]?.toDouble() ?: 0.0,
                        row[ResourceMetrics.memAvgMib]?.toDouble() ?: 0.0,
                    )
                }
                acc
            }

        workloads.map { row ->
            val workloadId = row[Workloads.workloadId]
            val (cpu, mem) = metricsMap[workloadId] ?: Pair(0.0, 0.0)
            WorkloadRefDTO(
                workloadId           = workloadId,
                name                 = row[Workloads.workloadName],
                namespace            = row[Namespaces.namespaceName],
                clusterName          = row[Clusters.clusterName],
                team                 = row[Workloads.team] ?: row[Workloads.ownerTeam],
                cpuRequestMillicores = cpu,
                memRequestMib        = mem,
            )
        }
    }
}
