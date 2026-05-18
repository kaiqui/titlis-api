package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.Clusters
import io.titlis.api.database.tables.Namespaces
import io.titlis.api.database.tables.ResourceTags
import io.titlis.api.database.tables.SloConfigs
import io.titlis.api.database.tables.Workloads
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class ClusterItem(val id: Long, val name: String, val environment: String)

@Serializable
data class NamespaceItem(val id: Long, val name: String, val clusterId: Long, val clusterName: String)

@Serializable
data class WorkloadItem(val id: Long, val name: String, val namespaceId: Long, val namespaceName: String, val clusterName: String)

class TagRepository {

    suspend fun listTagsForResource(
        tenantId: Long,
        resourceType: String,
        resourceId: Long,
    ): List<String> = dbQuery {
        ResourceTags
            .select(ResourceTags.tag)
            .where {
                (ResourceTags.tenantId eq tenantId) and
                (ResourceTags.resourceType eq resourceType) and
                (ResourceTags.resourceId eq resourceId)
            }
            .map { it[ResourceTags.tag] }
    }

    suspend fun addTag(
        tenantId: Long,
        resourceType: String,
        resourceId: Long,
        tag: String,
        createdBy: String?,
    ) {
        dbQuery {
            ResourceTags.insert {
                it[ResourceTags.tenantId]     = tenantId
                it[ResourceTags.resourceType] = resourceType
                it[ResourceTags.resourceId]   = resourceId
                it[ResourceTags.tag]          = tag
                it[ResourceTags.createdBy]    = createdBy
                it[ResourceTags.createdAt]    = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    suspend fun removeTag(
        tenantId: Long,
        resourceType: String,
        resourceId: Long,
        tag: String,
    ): Boolean = dbQuery {
        ResourceTags.deleteWhere {
            (ResourceTags.tenantId eq tenantId) and
            (ResourceTags.resourceType eq resourceType) and
            (ResourceTags.resourceId eq resourceId) and
            (ResourceTags.tag eq tag)
        } > 0
    }

    suspend fun listResourcesWithTags(
        tenantId: Long,
        resourceType: String,
    ): Map<Long, List<String>> = dbQuery {
        ResourceTags
            .select(ResourceTags.resourceId, ResourceTags.tag)
            .where {
                (ResourceTags.tenantId eq tenantId) and
                (ResourceTags.resourceType eq resourceType)
            }
            .groupBy({ it[ResourceTags.resourceId] }, { it[ResourceTags.tag] })
    }

    suspend fun validateOwnership(tenantId: Long, resourceType: String, resourceId: Long): Boolean = dbQuery {
        when (resourceType) {
            "cluster" -> Clusters
                .select(Clusters.clusterId)
                .where { (Clusters.clusterId eq resourceId) and (Clusters.tenantId eq tenantId) }
                .singleOrNull() != null

            "namespace" -> (Namespaces innerJoin Clusters)
                .select(Namespaces.namespaceId)
                .where { (Namespaces.namespaceId eq resourceId) and (Clusters.tenantId eq tenantId) }
                .singleOrNull() != null

            "workload" -> (Workloads innerJoin Namespaces innerJoin Clusters)
                .select(Workloads.workloadId)
                .where { (Workloads.workloadId eq resourceId) and (Clusters.tenantId eq tenantId) }
                .singleOrNull() != null

            "tenant" -> resourceId == tenantId

            "slo" -> (SloConfigs innerJoin Namespaces innerJoin Clusters)
                .select(SloConfigs.sloConfigId)
                .where { (SloConfigs.sloConfigId eq resourceId) and (Clusters.tenantId eq tenantId) }
                .singleOrNull() != null

            else -> false
        }
    }

    suspend fun findClusterIdByName(tenantId: Long, clusterName: String): Long? = dbQuery {
        Clusters
            .select(Clusters.clusterId)
            .where { (Clusters.tenantId eq tenantId) and (Clusters.clusterName eq clusterName) }
            .singleOrNull()
            ?.get(Clusters.clusterId)
    }

    suspend fun findNamespaceIdByName(clusterId: Long, namespaceName: String): Long? = dbQuery {
        Namespaces
            .select(Namespaces.namespaceId)
            .where { (Namespaces.clusterId eq clusterId) and (Namespaces.namespaceName eq namespaceName) }
            .singleOrNull()
            ?.get(Namespaces.namespaceId)
    }

    suspend fun listClusters(tenantId: Long): List<ClusterItem> = dbQuery {
        Clusters
            .select(Clusters.clusterId, Clusters.clusterName, Clusters.environment)
            .where { (Clusters.tenantId eq tenantId) and (Clusters.isActive eq true) }
            .orderBy(Clusters.clusterName)
            .map { ClusterItem(it[Clusters.clusterId], it[Clusters.clusterName], it[Clusters.environment]) }
    }

    suspend fun listWorkloads(tenantId: Long, clusterId: Long?, namespaceId: Long?): List<WorkloadItem> = dbQuery {
        (Workloads innerJoin Namespaces innerJoin Clusters)
            .select(Workloads.workloadId, Workloads.workloadName, Namespaces.namespaceId, Namespaces.namespaceName, Clusters.clusterName)
            .where {
                var cond = (Clusters.tenantId eq tenantId) and (Workloads.isActive eq true)
                if (namespaceId != null) cond = cond and (Workloads.namespaceId eq namespaceId)
                else if (clusterId != null) cond = cond and (Namespaces.clusterId eq clusterId)
                cond
            }
            .orderBy(Clusters.clusterName to SortOrder.ASC, Namespaces.namespaceName to SortOrder.ASC, Workloads.workloadName to SortOrder.ASC)
            .map { WorkloadItem(it[Workloads.workloadId], it[Workloads.workloadName], it[Namespaces.namespaceId], it[Namespaces.namespaceName], it[Clusters.clusterName]) }
    }

    suspend fun listNamespaces(tenantId: Long, clusterId: Long?): List<NamespaceItem> = dbQuery {
        (Namespaces innerJoin Clusters)
            .select(Namespaces.namespaceId, Namespaces.namespaceName, Namespaces.clusterId, Clusters.clusterName)
            .where {
                val base = (Clusters.tenantId eq tenantId) and (Namespaces.isExcluded eq false)
                if (clusterId != null) base and (Namespaces.clusterId eq clusterId) else base
            }
            .orderBy(Clusters.clusterName to SortOrder.ASC, Namespaces.namespaceName to SortOrder.ASC)
            .map { NamespaceItem(it[Namespaces.namespaceId], it[Namespaces.namespaceName], it[Namespaces.clusterId], it[Clusters.clusterName]) }
    }
}
