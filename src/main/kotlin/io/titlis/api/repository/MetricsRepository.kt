package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.ResourceMetrics
import io.titlis.api.database.tables.Workloads
import io.titlis.api.domain.ResourceMetricsEvent
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MetricsRepository {
    suspend fun insertResourceMetrics(event: ResourceMetricsEvent, tenantIdHint: Long? = null) = dbQuery {
        val workloadId = Workloads
            .select(Workloads.workloadId)
            .where { Workloads.k8sUid eq event.workloadId }
            .singleOrNull()
            ?.get(Workloads.workloadId)
            ?: error("Workload não encontrado para k8s_uid=${event.workloadId}")
        val tenantId = chooseTenantId(
            trustedTenantId = tenantIdHint,
            derivedTenantId = resolveTenantIdByWorkloadId(workloadId) ?: resolveSingleActiveTenantIdOrNull(),
        )

        ResourceMetrics.insert {
            it[ResourceMetrics.workloadId] = workloadId
            it[ResourceMetrics.tenantId] = tenantId
            it[ResourceMetrics.containerName] = event.containerName
            it[ResourceMetrics.cpuAvgMillicores] =
                event.cpuAvgMillicores?.toBigDecimal()
            it[ResourceMetrics.cpuP95Millicores] =
                event.cpuP95Millicores?.toBigDecimal()
            it[ResourceMetrics.memAvgMib] = event.memAvgMib?.toBigDecimal()
            it[ResourceMetrics.memP95Mib] = event.memP95Mib?.toBigDecimal()
            it[ResourceMetrics.suggestedCpuRequest] = event.suggestedCpuRequest
            it[ResourceMetrics.suggestedCpuLimit] = event.suggestedCpuLimit
            it[ResourceMetrics.suggestedMemRequest] = event.suggestedMemRequest
            it[ResourceMetrics.suggestedMemLimit] = event.suggestedMemLimit
            it[ResourceMetrics.sampleWindow] = event.sampleWindow
            it[ResourceMetrics.collectedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }
}
