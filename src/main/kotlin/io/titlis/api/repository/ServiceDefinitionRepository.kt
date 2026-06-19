package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.ServiceDefinitions
import io.titlis.api.database.tables.ServiceQueuePatterns
import io.titlis.api.database.tables.Workloads
import io.titlis.api.database.tables.Namespaces
import io.titlis.api.database.tables.Clusters
import io.titlis.api.domain.QueueIntegrationSpec
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class ServiceDefinitionEvent(
    val serviceName: String,
    val team: String,
    val product: String? = null,
    val tier: String? = null,
    val description: String? = null,
    val repoUrl: String? = null,
    val workloads: List<String> = emptyList(),
    val rawYaml: String? = null,
    val integrations: List<QueueIntegrationSpec> = emptyList(),
)

class ServiceDefinitionRepository {

    suspend fun upsert(tenantId: Long, event: ServiceDefinitionEvent): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        ServiceDefinitions.upsert(
            ServiceDefinitions.tenantId,
            ServiceDefinitions.serviceName,
            onUpdateExclude = listOf(ServiceDefinitions.createdAt),
        ) {
            it[ServiceDefinitions.tenantId]    = tenantId
            it[ServiceDefinitions.serviceName] = event.serviceName
            it[ServiceDefinitions.team]        = event.team
            it[ServiceDefinitions.product]     = event.product
            it[ServiceDefinitions.tier]        = event.tier
            it[ServiceDefinitions.description] = event.description
            it[ServiceDefinitions.repoUrl]     = event.repoUrl
            it[ServiceDefinitions.rawYaml]     = event.rawYaml
            it[ServiceDefinitions.syncedAt]    = now
            it[ServiceDefinitions.createdAt]   = now
        }

        val serviceDefinitionId = ServiceDefinitions
            .select(ServiceDefinitions.serviceDefinitionId)
            .where { (ServiceDefinitions.tenantId eq tenantId) and (ServiceDefinitions.serviceName eq event.serviceName) }
            .single()[ServiceDefinitions.serviceDefinitionId]

        // Liga workloads declarados (e o de nome igual ao serviço) ao team + service_definition,
        // formando a folha do nó "service" na árvore de confiabilidade.
        val workloadNames = (event.workloads + event.serviceName).distinct()
        propagateToWorkloads(tenantId, event.team, serviceDefinitionId, workloadNames)

        // Re-sincroniza os padrões de fila declarados em spec.integrations (delete + insert).
        syncQueuePatterns(tenantId, serviceDefinitionId, event.integrations, now)
    }

    private fun syncQueuePatterns(
        tenantId: Long,
        serviceDefinitionId: Long,
        integrations: List<QueueIntegrationSpec>,
        now: OffsetDateTime,
    ) {
        ServiceQueuePatterns.deleteWhere { ServiceQueuePatterns.serviceDefinitionId eq serviceDefinitionId }

        integrations.forEach { integ ->
            val provider = integ.type.ifBlank { "gcp_pubsub" }
            val field = integ.match.ifBlank { "display_name" }
            integ.queues
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { pat ->
                    ServiceQueuePatterns.insert {
                        it[ServiceQueuePatterns.tenantId]            = tenantId
                        it[ServiceQueuePatterns.serviceDefinitionId] = serviceDefinitionId
                        it[ServiceQueuePatterns.provider]            = provider
                        it[ServiceQueuePatterns.pattern]             = pat
                        it[ServiceQueuePatterns.matchType]           = matchTypeOf(pat)
                        it[ServiceQueuePatterns.matchField]          = field
                        it[ServiceQueuePatterns.createdAt]           = now
                    }
                }
        }
    }

    private fun matchTypeOf(pattern: String): String = when {
        !pattern.contains('*') -> "exact"
        pattern.indexOf('*') == pattern.length - 1 && pattern.count { it == '*' } == 1 -> "prefix"
        else -> "glob"
    }

    private fun propagateToWorkloads(tenantId: Long, team: String, serviceDefinitionId: Long, workloadNames: List<String>) {
        if (workloadNames.isEmpty()) return

        val clusterIds = Clusters
            .select(Clusters.clusterId)
            .where { Clusters.tenantId eq tenantId }
            .map { it[Clusters.clusterId] }

        if (clusterIds.isEmpty()) return

        val namespaceIds = Namespaces
            .select(Namespaces.namespaceId)
            .where { Namespaces.clusterId inList clusterIds }
            .map { it[Namespaces.namespaceId] }

        if (namespaceIds.isEmpty()) return

        Workloads.update({
            (Workloads.namespaceId inList namespaceIds) and
            (Workloads.workloadName inList workloadNames) and
            (Workloads.isActive eq true)
        }) {
            it[Workloads.team] = team
            it[Workloads.serviceDefinitionId] = serviceDefinitionId
        }
    }

    suspend fun listForTenant(tenantId: Long): List<io.titlis.api.domain.ServiceOptionDTO> = dbQuery {
        ServiceDefinitions
            .select(ServiceDefinitions.serviceDefinitionId, ServiceDefinitions.serviceName, ServiceDefinitions.team)
            .where { ServiceDefinitions.tenantId eq tenantId }
            .orderBy(ServiceDefinitions.serviceName)
            .map {
                io.titlis.api.domain.ServiceOptionDTO(
                    serviceDefinitionId = it[ServiceDefinitions.serviceDefinitionId],
                    serviceName         = it[ServiceDefinitions.serviceName],
                    team                = it[ServiceDefinitions.team],
                )
            }
    }

    suspend fun getTeamByWorkloadName(tenantId: Long, workloadName: String): String? = dbQuery {
        ServiceDefinitions
            .select(ServiceDefinitions.team)
            .where {
                (ServiceDefinitions.tenantId eq tenantId) and
                (ServiceDefinitions.serviceName eq workloadName)
            }
            .singleOrNull()
            ?.get(ServiceDefinitions.team)
    }
}
