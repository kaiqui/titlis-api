package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.ServiceDefinitions
import io.titlis.api.database.tables.ServiceQueuePatterns
import io.titlis.api.database.tables.ServiceWorkloadPatterns
import io.titlis.api.database.tables.Workloads
import io.titlis.api.database.tables.Namespaces
import io.titlis.api.database.tables.Clusters
import io.titlis.api.domain.QueueIntegrationSpec
import io.titlis.api.domain.WorkloadMatchSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
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
    // Fase 0 (service-yaml-discovery): workloadMatch é usado na correlação por pattern (Fase 1);
    // gitops/remediation chegam como JSON serializado e são persistidos como JSONB.
    val workloadMatch: WorkloadMatchSpec? = null,
    val gitopsPathsJson: String? = null,
    val remediationJson: String? = null,
)

class ServiceDefinitionRepository {

    private val logger = LoggerFactory.getLogger(ServiceDefinitionRepository::class.java)

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
            it[ServiceDefinitions.gitopsPaths] = event.gitopsPathsJson
            it[ServiceDefinitions.remediation] = event.remediationJson
            it[ServiceDefinitions.lastSeenAt]  = now
            it[ServiceDefinitions.isStale]     = false
            it[ServiceDefinitions.syncedAt]    = now
            it[ServiceDefinitions.createdAt]   = now
        }

        val serviceDefinitionId = ServiceDefinitions
            .select(ServiceDefinitions.serviceDefinitionId)
            .where { (ServiceDefinitions.tenantId eq tenantId) and (ServiceDefinitions.serviceName eq event.serviceName) }
            .single()[ServiceDefinitions.serviceDefinitionId]

        // Correlação workload↔service_definition. Caminho preferencial: workload_match
        // (namespaces + name_pattern regex). Sem workload_match, mantém o legado (nome exato).
        val match = event.workloadMatch
        if (match != null && !match.namePattern.isNullOrBlank()) {
            correlateWorkloadsByPattern(tenantId, event.team, serviceDefinitionId, match, now)
        } else {
            ServiceWorkloadPatterns.deleteWhere { ServiceWorkloadPatterns.serviceDefinitionId eq serviceDefinitionId }
            val workloadNames = (event.workloads + event.serviceName).distinct()
            propagateToWorkloads(tenantId, event.team, serviceDefinitionId, workloadNames)
        }

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

    private data class WorkloadRow(
        val workloadId: Long,
        val workloadName: String,
        val namespaceName: String,
        val currentServiceDefinitionId: Long?,
    )

    private fun correlateWorkloadsByPattern(
        tenantId: Long,
        team: String,
        serviceDefinitionId: Long,
        match: WorkloadMatchSpec,
        now: OffsetDateTime,
    ) {
        val namePattern = match.namePattern ?: return
        val regex = runCatching { Regex(namePattern) }.getOrElse {
            logger.warn("workload_match.name_pattern inválido (svc_def={}): '{}'", serviceDefinitionId, namePattern)
            ServiceWorkloadPatterns.deleteWhere { ServiceWorkloadPatterns.serviceDefinitionId eq serviceDefinitionId }
            return
        }

        // 1. Re-sincroniza a linha de pattern deste service_definition (delete + insert).
        ServiceWorkloadPatterns.deleteWhere { ServiceWorkloadPatterns.serviceDefinitionId eq serviceDefinitionId }
        ServiceWorkloadPatterns.insert {
            it[ServiceWorkloadPatterns.tenantId]            = tenantId
            it[ServiceWorkloadPatterns.serviceDefinitionId] = serviceDefinitionId
            it[ServiceWorkloadPatterns.namespaces]          = Json.encodeToString(match.namespaces)
            it[ServiceWorkloadPatterns.namePattern]         = namePattern
            it[ServiceWorkloadPatterns.createdAt]           = now
        }

        // 2. Carrega workloads ativos do tenant (id, nome, namespace, svc_def atual).
        val candidates = loadTenantWorkloads(tenantId)
        val nsFilter = match.namespaces.toSet()
        val matched = candidates.filter {
            (nsFilter.isEmpty() || it.namespaceName in nsFilter) && regex.matches(it.workloadName)
        }

        // 3. Atribui com resolução de conflito — pattern mais específico vence.
        val thisSpec = patternSpecificity(match.namespaces, namePattern)
        val assignedIds = mutableListOf<Long>()
        for (w in matched) {
            val current = w.currentServiceDefinitionId
            if (current == null || current == serviceDefinitionId) {
                assignWorkload(w.workloadId, team, serviceDefinitionId)
                assignedIds += w.workloadId
            } else if (thisSpec > specificityOfDefinition(current)) {
                assignWorkload(w.workloadId, team, serviceDefinitionId)
                assignedIds += w.workloadId
            } else {
                logger.info(
                    "Conflito de correlação: workload={} mantido em svc_def={} (mais específico) vs svc_def={}",
                    w.workloadId, current, serviceDefinitionId,
                )
            }
        }

        // 5. Limpeza: workloads ainda ligados a este svc_def mas que não casam mais → desliga (soft).
        Workloads.update({
            val base = Workloads.serviceDefinitionId eq serviceDefinitionId
            if (assignedIds.isEmpty()) base else base and (Workloads.workloadId notInList assignedIds)
        }) {
            it[Workloads.serviceDefinitionId] = null
            it[Workloads.team] = null
        }
    }

    private fun loadTenantWorkloads(tenantId: Long): List<WorkloadRow> =
        (Workloads innerJoin Namespaces innerJoin Clusters)
            .select(
                Workloads.workloadId,
                Workloads.workloadName,
                Namespaces.namespaceName,
                Workloads.serviceDefinitionId,
            )
            .where { (Clusters.tenantId eq tenantId) and (Workloads.isActive eq true) }
            .map {
                WorkloadRow(
                    workloadId                 = it[Workloads.workloadId],
                    workloadName               = it[Workloads.workloadName],
                    namespaceName              = it[Namespaces.namespaceName],
                    currentServiceDefinitionId = it[Workloads.serviceDefinitionId],
                )
            }

    private fun assignWorkload(workloadId: Long, team: String, serviceDefinitionId: Long) {
        Workloads.update({ Workloads.workloadId eq workloadId }) {
            it[Workloads.team] = team
            it[Workloads.serviceDefinitionId] = serviceDefinitionId
        }
    }

    // Especificidade de uma definition já persistida (para resolver conflitos).
    // Sem pattern (ex.: legado nome-exato) → -1 (menos específico que qualquer pattern).
    private fun specificityOfDefinition(serviceDefinitionId: Long): Int {
        val row = ServiceWorkloadPatterns
            .select(ServiceWorkloadPatterns.namespaces, ServiceWorkloadPatterns.namePattern)
            .where { ServiceWorkloadPatterns.serviceDefinitionId eq serviceDefinitionId }
            .firstOrNull() ?: return -1
        val namespaces = row[ServiceWorkloadPatterns.namespaces]
            ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
        return patternSpecificity(namespaces, row[ServiceWorkloadPatterns.namePattern])
    }

    // Heurística: namespaces declarados (escopo) pesam mais; depois, mais caracteres literais
    // (menos curingas) = mais específico. Empate é resolvido por ordem (primeiro vence) + log.
    private fun patternSpecificity(namespaces: List<String>, namePattern: String): Int {
        val literal = namePattern.count { it.isLetterOrDigit() || it == '-' || it == '_' }
        val nsBonus = if (namespaces.isNotEmpty()) 1000 else 0
        return nsBonus + literal
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

    // Marca como stale (soft) as definitions do tenant não vistas no scan e desliga seus workloads.
    // Lista vazia é no-op proposital: um scan que não viu nada provavelmente falhou — não desliga tudo.
    suspend fun markStaleExcept(tenantId: Long, seenServiceNames: List<String>): Int = dbQuery {
        if (seenServiceNames.isEmpty()) {
            logger.info("scan-complete sem service_names — staleness ignorada (tenant={})", tenantId)
            return@dbQuery 0
        }

        val staleIds = ServiceDefinitions
            .select(ServiceDefinitions.serviceDefinitionId)
            .where {
                (ServiceDefinitions.tenantId eq tenantId) and
                (ServiceDefinitions.serviceName notInList seenServiceNames)
            }
            .map { it[ServiceDefinitions.serviceDefinitionId] }

        if (staleIds.isEmpty()) return@dbQuery 0

        ServiceDefinitions.update({ ServiceDefinitions.serviceDefinitionId inList staleIds }) {
            it[ServiceDefinitions.isStale] = true
        }
        Workloads.update({ Workloads.serviceDefinitionId inList staleIds }) {
            it[Workloads.serviceDefinitionId] = null
            it[Workloads.team] = null
        }
        logger.info("scan-complete: {} definitions marcadas stale (tenant={})", staleIds.size, tenantId)
        staleIds.size
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
