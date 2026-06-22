package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.AssetRelations
import io.titlis.api.database.tables.CoverageScorecards
import io.titlis.api.database.tables.DiscoveredAssets
import io.titlis.api.database.tables.ServiceDefinitions
import io.titlis.api.database.tables.Workloads
import io.titlis.api.domain.CoverageDimensionDTO
import io.titlis.api.domain.CoverageFindingDTO
import io.titlis.api.domain.CoverageFoundDTO
import io.titlis.api.domain.CoverageNatureDTO
import io.titlis.api.domain.CoverageResultDTO
import io.titlis.api.domain.CoverageScorecardDTO
import io.titlis.api.domain.CoverageSnapshotDTO
import io.titlis.api.domain.ServiceMapDTO
import io.titlis.api.domain.ServiceMapProduct
import io.titlis.api.domain.ServiceMapService
import io.titlis.api.domain.ServiceMapSquad
import io.titlis.api.domain.ServiceMapWorkload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

// D5b/D5e: monta o CoverageSnapshot por serviço a partir do grafo (titlis_oltp) e persiste o
// resultado do scoreops. Determinístico; o scoreops é quem aplica as regras/templates.
class CoverageRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val workloadKinds = listOf("deployment", "statefulset", "daemonset", "cronjob")

    suspend fun buildSnapshots(tenantId: Long): List<CoverageSnapshotDTO> = dbQuery {
        val rows = DiscoveredAssets
            .select(
                DiscoveredAssets.discoveredAssetId, DiscoveredAssets.kind, DiscoveredAssets.name,
                DiscoveredAssets.namespace, DiscoveredAssets.clusterName, DiscoveredAssets.attributes,
                DiscoveredAssets.externalId,
            )
            .where {
                (DiscoveredAssets.tenantId eq tenantId) and
                    (DiscoveredAssets.provider eq "kubernetes") and
                    (DiscoveredAssets.kind inList workloadKinds) and
                    (DiscoveredAssets.isActive eq true)
            }
            .toList()

        // Capacidade "monitor" = a descoberta de monitores Datadog rodou (há dd_monitor no tenant).
        // Só então sabemos distinguir "tem monitor" de "não tem"; senão a dimensão fica N/A.
        val monitorCapable = assetExists(tenantId, "datadog", "dd_monitor")

        rows.map { row ->
            val id = row[DiscoveredAssets.discoveredAssetId]
            val kind = row[DiscoveredAssets.kind]
            val ns = row[DiscoveredAssets.namespace] ?: ""
            val cluster = row[DiscoveredAssets.clusterName] ?: ""
            val attrs = parseAttrs(row[DiscoveredAssets.attributes])

            // Capacidades de observabilidade vindas dos serviços Datadog correlacionados (describes).
            // tracing/metrics/logs ficam nos attributes do dd_service quando o operator os descobre.
            val ddCaps = datadogServiceCapabilities(id)
            val capabilities = buildList {
                if (monitorCapable) add("monitor")
                addAll(ddCaps)
            }.distinct()

            val nature = CoverageNatureDTO(
                language = "", // detecção de linguagem: enriquecimento futuro
                httpFacing = relationExists(targetId = id, type = "selects"),
                stateful = kind == "statefulset",
                scheduled = kind == "cronjob",
                criticality = "standard",
                hasQueueDep = false,
            )
            val found = CoverageFoundDTO(
                hasMonitor = hasMonitorVia2Hop(id),
                hasTracing = "tracing" in ddCaps,
                metricCategories = ddMetricCategories(id),
                cpuRequestSet = attrBool(attrs, "cpuRequestSet"),
                cpuLimitSet = attrBool(attrs, "cpuLimitSet"),
                memoryRequestSet = attrBool(attrs, "memoryRequestSet"),
                memoryLimitSet = attrBool(attrs, "memoryLimitSet"),
                hasProbes = attrBool(attrs, "hasLivenessProbe") && attrBool(attrs, "hasReadinessProbe"),
                hasHpa = relationExists(sourceId = id, type = "scaled_by"),
                hasPdb = relationExists(sourceId = id, type = "protected_by"),
                hasNetworkPolicy = netpolInNamespace(tenantId, cluster, ns),
            )
            CoverageSnapshotDTO(
                tenantId = tenantId,
                workloadUid = row[DiscoveredAssets.externalId],
                serviceName = attrStr(attrs, "ddService") ?: row[DiscoveredAssets.name],
                namespace = ns,
                cluster = cluster,
                nature = nature,
                found = found,
                capabilities = capabilities,
            )
        }
    }

    suspend fun upsertResult(result: CoverageResultDTO): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val dimsJson = json.encodeToString(result.dimensions)
        val findsJson = json.encodeToString(result.findings)
        val existing = CoverageScorecards
            .select(CoverageScorecards.coverageScorecardId)
            .where {
                (CoverageScorecards.tenantId eq result.tenantId) and
                    (CoverageScorecards.workloadUid eq result.workloadUid)
            }
            .singleOrNull()

        if (existing == null) {
            CoverageScorecards.insert {
                it[tenantId] = result.tenantId
                it[workloadUid] = result.workloadUid
                it[serviceName] = result.serviceName
                it[clusterName] = result.cluster
                it[trustScore] = BigDecimal.valueOf(result.trustScore)
                it[coverageJson] = dimsJson
                it[findingsJson] = findsJson
                it[evaluatedAt] = now
            }
        } else {
            CoverageScorecards.update({ CoverageScorecards.coverageScorecardId eq existing[CoverageScorecards.coverageScorecardId] }) {
                it[serviceName] = result.serviceName
                it[clusterName] = result.cluster
                it[trustScore] = BigDecimal.valueOf(result.trustScore)
                it[coverageJson] = dimsJson
                it[findingsJson] = findsJson
                it[evaluatedAt] = now
            }
        }
    }

    suspend fun listForTenant(tenantId: Long): List<CoverageScorecardDTO> = dbQuery {
        val active = activeWorkloadUids(tenantId)
        CoverageScorecards
            .selectAll()
            .where { CoverageScorecards.tenantId eq tenantId }
            .map(::mapRow)
            .filter { it.workloadUid in active }
    }

    // Top-N riscos do tenant: menor Trust Score primeiro (MVP §9 "Top-10 riscos").
    suspend fun topRisks(tenantId: Long, limit: Int): List<CoverageScorecardDTO> = dbQuery {
        val active = activeWorkloadUids(tenantId)
        CoverageScorecards
            .selectAll()
            .where { CoverageScorecards.tenantId eq tenantId }
            .orderBy(CoverageScorecards.trustScore to SortOrder.ASC_NULLS_LAST)
            .map(::mapRow)
            .filter { it.workloadUid in active }
            .take(limit)
    }

    // H1 — service-map do hub: produto → squad → serviço → workload (+ órfãos). A estrutura vem do
    // .titlis/service.yaml (service_definitions, correlação materializada em workloads.service_definition_id);
    // o score é o do Coverage (trustScore por ora; vira overall 0–100 com U1). Workloads descobertos sem
    // service_definition caem no bucket de órfãos = driver de adoção do service.yaml.
    suspend fun buildServiceMap(tenantId: Long): ServiceMapDTO = dbQuery {
        val active = activeWorkloadUids(tenantId)
        val scored = CoverageScorecards
            .selectAll()
            .where { CoverageScorecards.tenantId eq tenantId }
            .map(::mapRow)
            .filter { it.workloadUid in active }
        if (scored.isEmpty()) return@dbQuery ServiceMapDTO(emptyList(), emptyList())

        val uids = scored.map { it.workloadUid }
        val uidToSvc: Map<String, Long?> = Workloads
            .select(Workloads.k8sUid, Workloads.serviceDefinitionId)
            .where { Workloads.k8sUid inList uids }
            .mapNotNull { row -> row[Workloads.k8sUid]?.let { it to row[Workloads.serviceDefinitionId] } }
            .toMap()

        val svcIds = uidToSvc.values.filterNotNull().toSet()
        val svcDefs: Map<Long, SvcDef> = if (svcIds.isEmpty()) emptyMap() else ServiceDefinitions
            .select(
                ServiceDefinitions.serviceDefinitionId, ServiceDefinitions.serviceName,
                ServiceDefinitions.team, ServiceDefinitions.product, ServiceDefinitions.repoUrl,
            )
            .where { (ServiceDefinitions.tenantId eq tenantId) and (ServiceDefinitions.serviceDefinitionId inList svcIds) }
            .associate {
                it[ServiceDefinitions.serviceDefinitionId] to SvcDef(
                    it[ServiceDefinitions.serviceName],
                    it[ServiceDefinitions.team],
                    it[ServiceDefinitions.product],
                    it[ServiceDefinitions.repoUrl],
                )
            }

        val orphans = mutableListOf<ServiceMapWorkload>()
        val byService = LinkedHashMap<Long, MutableList<ServiceMapWorkload>>()
        for (sc in scored) {
            val wl = ServiceMapWorkload(sc.workloadUid, sc.serviceName ?: sc.workloadUid, sc.cluster, sc.trustScore, sc.maturity)
            val svcId = uidToSvc[sc.workloadUid]
            if (svcId == null || svcDefs[svcId] == null) { orphans.add(wl); continue }
            byService.getOrPut(svcId) { mutableListOf() }.add(wl)
        }

        val products = LinkedHashMap<String, LinkedHashMap<String, MutableList<ServiceMapService>>>()
        for ((svcId, wls) in byService) {
            val def = svcDefs.getValue(svcId)
            val service = ServiceMapService(svcId, def.serviceName, def.repoUrl, avgScore(wls.mapNotNull { it.score }), wls)
            products.getOrPut(def.product ?: "(sem produto)") { LinkedHashMap() }
                .getOrPut(def.team) { mutableListOf() }
                .add(service)
        }

        val productNodes = products.map { (product, squads) ->
            val squadNodes = squads.map { (team, services) ->
                ServiceMapSquad(team, avgScore(services.mapNotNull { it.score }), services)
            }
            ServiceMapProduct(product, avgScore(squadNodes.mapNotNull { it.score }), squadNodes)
        }
        ServiceMapDTO(productNodes, orphans)
    }

    private data class SvcDef(val serviceName: String, val team: String, val product: String?, val repoUrl: String?)

    private fun avgScore(scores: List<Double>): Double? =
        if (scores.isEmpty()) null else Math.round(scores.sum() / scores.size * 10.0) / 10.0

    // Workloads (K8s) ainda ativos no grafo — consistência com soft-delete (regra 13): um scorecard
    // de workload soft-deletado não deve mais aparecer na leitura.
    private fun activeWorkloadUids(tenantId: Long): Set<String> =
        DiscoveredAssets
            .select(DiscoveredAssets.externalId)
            .where {
                (DiscoveredAssets.tenantId eq tenantId) and
                    (DiscoveredAssets.provider eq "kubernetes") and
                    (DiscoveredAssets.kind inList workloadKinds) and
                    (DiscoveredAssets.isActive eq true)
            }
            .map { it[DiscoveredAssets.externalId] }
            .toSet()

    private fun mapRow(row: ResultRow): CoverageScorecardDTO {
        val dims = runCatching {
            json.decodeFromString<List<CoverageDimensionDTO>>(row[CoverageScorecards.coverageJson])
        }.getOrDefault(emptyList())
        // Maturidade geral = elo mais fraco entre dimensões avaliáveis (nível > 0).
        val maturity = dims.mapNotNull { it.maturityLevel.takeIf { lvl -> lvl > 0 } }.minOrNull() ?: 0
        return CoverageScorecardDTO(
            workloadUid = row[CoverageScorecards.workloadUid],
            serviceName = row[CoverageScorecards.serviceName],
            cluster = row[CoverageScorecards.clusterName],
            trustScore = row[CoverageScorecards.trustScore]?.toDouble(),
            maturity = maturity,
            dimensions = dims,
            findings = runCatching {
                json.decodeFromString<List<CoverageFindingDTO>>(row[CoverageScorecards.findingsJson])
            }.getOrDefault(emptyList()),
            evaluatedAt = row[CoverageScorecards.evaluatedAt].toString(),
        )
    }

    private fun relationExists(sourceId: Long? = null, targetId: Long? = null, type: String): Boolean {
        var cond: Op<Boolean> = (AssetRelations.relationType eq type) and (AssetRelations.isActive eq true)
        if (sourceId != null) cond = cond and (AssetRelations.sourceDiscoveredAssetId eq sourceId)
        if (targetId != null) cond = cond and (AssetRelations.targetDiscoveredAssetId eq targetId)
        return AssetRelations.select(AssetRelations.assetRelationId).where { cond }.count() > 0L
    }

    private fun netpolInNamespace(tenantId: Long, cluster: String, ns: String): Boolean =
        DiscoveredAssets.select(DiscoveredAssets.discoveredAssetId)
            .where {
                (DiscoveredAssets.tenantId eq tenantId) and (DiscoveredAssets.kind eq "networkpolicy") and
                    (DiscoveredAssets.clusterName eq cluster) and (DiscoveredAssets.namespace eq ns) and
                    (DiscoveredAssets.isActive eq true)
            }
            .count() > 0L

    private fun assetExists(tenantId: Long, provider: String, kind: String): Boolean =
        DiscoveredAssets.select(DiscoveredAssets.discoveredAssetId)
            .where {
                (DiscoveredAssets.tenantId eq tenantId) and (DiscoveredAssets.provider eq provider) and
                    (DiscoveredAssets.kind eq kind) and (DiscoveredAssets.isActive eq true)
            }
            .count() > 0L

    // dd_service ids que descrevem este workload (edge `describes`, criada pelo Correlator no operator).
    private fun describesServiceIds(workloadId: Long): List<Long> =
        AssetRelations.select(AssetRelations.sourceDiscoveredAssetId)
            .where {
                (AssetRelations.targetDiscoveredAssetId eq workloadId) and
                    (AssetRelations.relationType eq "describes") and (AssetRelations.isActive eq true)
            }
            .map { it[AssetRelations.sourceDiscoveredAssetId] }

    // 2-hop: workload ←describes← dd_service ←monitors← dd_monitor.
    private fun hasMonitorVia2Hop(workloadId: Long): Boolean {
        val svcIds = describesServiceIds(workloadId)
        if (svcIds.isEmpty()) return false
        return AssetRelations.select(AssetRelations.assetRelationId)
            .where {
                (AssetRelations.relationType eq "monitors") and (AssetRelations.isActive eq true) and
                    (AssetRelations.targetDiscoveredAssetId inList svcIds)
            }
            .count() > 0L
    }

    private fun ddServiceAttrs(workloadId: Long): List<JsonObject> {
        val svcIds = describesServiceIds(workloadId)
        if (svcIds.isEmpty()) return emptyList()
        return DiscoveredAssets.select(DiscoveredAssets.attributes)
            .where { DiscoveredAssets.discoveredAssetId inList svcIds }
            .map { parseAttrs(it[DiscoveredAssets.attributes]) }
    }

    // Capacidades de observabilidade que o operator anotou no dd_service (tracing/metrics/logs).
    private fun datadogServiceCapabilities(workloadId: Long): List<String> =
        ddServiceAttrs(workloadId).flatMap { strArray(it, "capabilities") }.distinct()

    private fun ddMetricCategories(workloadId: Long): List<String> =
        ddServiceAttrs(workloadId).flatMap { strArray(it, "metricCategories") }.distinct()

    private fun strArray(o: JsonObject, k: String): List<String> =
        (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

    private fun parseAttrs(s: String): JsonObject =
        runCatching { Json.parseToJsonElement(s).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun attrBool(o: JsonObject, k: String): Boolean =
        (o[k] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun attrStr(o: JsonObject, k: String): String? =
        (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
