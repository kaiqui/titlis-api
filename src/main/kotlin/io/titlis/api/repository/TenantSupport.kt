package io.titlis.api.repository

import io.titlis.api.database.tables.Clusters
import io.titlis.api.database.tables.Namespaces
import io.titlis.api.database.tables.Tenants
import io.titlis.api.database.tables.Workloads
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select

internal fun resolveSingleActiveTenantIdOrNull(): Long? {
    val rows = Tenants
        .select(Tenants.tenantId)
        .where { Tenants.isActive eq true }
        .limit(2)
        .toList()

    return if (rows.size == 1) rows.single()[Tenants.tenantId] else null
}

internal fun chooseTenantId(
    trustedTenantId: Long?,
    derivedTenantId: Long?,
): Long? {
    val trustedExists = trustedTenantId?.let { tenantId ->
        Tenants
            .select(Tenants.tenantId)
            .where { Tenants.tenantId eq tenantId }
            .limit(1)
            .singleOrNull() != null
    } ?: false

    return if (trustedExists) trustedTenantId else derivedTenantId
}

internal fun resolveTenantIdByWorkloadId(workloadId: Long): Long? =
    (Workloads innerJoin Namespaces innerJoin Clusters)
        .select(Clusters.tenantId)
        .where { Workloads.workloadId eq workloadId }
        .singleOrNull()
        ?.get(Clusters.tenantId)

internal fun resolveTenantIdByWorkloadUid(k8sUid: String): Long? =
    (Workloads innerJoin Namespaces innerJoin Clusters)
        .select(Clusters.tenantId)
        .where { Workloads.k8sUid eq k8sUid }
        .singleOrNull()
        ?.get(Clusters.tenantId)

internal fun tenantScopedWorkloadRow(k8sUid: String, tenantId: Long): ResultRow? =
    (Workloads innerJoin Namespaces innerJoin Clusters)
        .select(Workloads.workloadId, Clusters.tenantId)
        .where {
            (Workloads.k8sUid eq k8sUid) and
                (Clusters.tenantId eq tenantId)
        }
        .singleOrNull()
