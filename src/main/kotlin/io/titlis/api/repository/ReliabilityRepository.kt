package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.*
import io.titlis.api.domain.ReliabilityFindingDTO
import io.titlis.api.domain.ReliabilityNodeDTO
import io.titlis.api.domain.ReliabilityTrendPointDTO
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ReliabilityRepository {

    companion object {
        const val CRIT_SCORE = 60.0   // abaixo disso, folha tier-1 derruba o piso crítico
        const val CRIT_WEIGHT = 3.0   // peso de tier-1
        const val ORPHAN_PRODUCT = "(sem produto)"
        const val ORPHAN_TEAM = "(sem dono)"
        const val ORPHAN_SERVICE = "(sem dono)"

        fun tierWeight(tier: String?): Double {
            val t = tier?.lowercase()?.trim() ?: return 1.0
            return when {
                t.contains("1") || t == "critical" || t == "high" || t == "crit" -> 3.0
                t.contains("2") || t == "medium"   || t == "standard"            -> 2.0
                else -> 1.0
            }
        }

        private fun severityWeight(sev: String?): Double = when (sev?.lowercase()) {
            "critical" -> 3.0
            "error"    -> 2.0
            else       -> 1.0
        }

        private fun riOf(debt: Double, sumWeight: Double): Double? =
            if (sumWeight > 0.0) (100.0 - debt / sumWeight).coerceIn(0.0, 100.0) else null

        // Navega até `path` e poda os filhos além de `depth`. Pura — testável sem banco.
        fun sliceTree(root: ReliabilityNodeDTO, path: String, depth: Int): ReliabilityNodeDTO? {
            val target = if (path.isBlank()) root else findByPath(root, path) ?: return null
            return prune(target, depth)
        }

        private fun findByPath(node: ReliabilityNodeDTO, path: String): ReliabilityNodeDTO? {
            if (node.path == path) return node
            for (c in node.children) findByPath(c, path)?.let { return it }
            return null
        }

        private fun prune(node: ReliabilityNodeDTO, depth: Int): ReliabilityNodeDTO =
            if (depth <= 0) node.copy(children = emptyList())
            else node.copy(children = node.children.map { prune(it, depth - 1) })
    }

    private data class WlLeaf(val scId: Long, val name: String, val uid: String?, val score: Double)
    private data class SdInfo(val name: String, val team: String, val product: String?, val tier: String?)
    private data class Leaf(
        val product: String, val team: String, val serviceId: Long?, val serviceName: String,
        val kind: String, val name: String, val score: Double?, val weight: Double,
    )

    suspend fun getTree(tenantId: Long, rootPath: String, depth: Int): ReliabilityNodeDTO? {
        val full = dbQuery { buildTree(loadLeaves(tenantId)) }
        return sliceTree(full, rootPath, depth)
    }

    private fun loadLeaves(tenantId: Long): List<Leaf> {
        val sd = ServiceDefinitions
            .select(
                ServiceDefinitions.serviceDefinitionId, ServiceDefinitions.serviceName,
                ServiceDefinitions.team, ServiceDefinitions.product, ServiceDefinitions.tier,
            )
            .where { ServiceDefinitions.tenantId eq tenantId }
            .associate {
                it[ServiceDefinitions.serviceDefinitionId] to SdInfo(
                    name = it[ServiceDefinitions.serviceName],
                    team = it[ServiceDefinitions.team],
                    product = it[ServiceDefinitions.product],
                    tier = it[ServiceDefinitions.tier],
                )
            }

        val leaves = mutableListOf<Leaf>()

        (AppScorecards innerJoin Workloads)
            .select(Workloads.workloadName, Workloads.serviceDefinitionId, AppScorecards.overallScore)
            .where { AppScorecards.tenantId eq tenantId }
            .forEach { row ->
                val info = row[Workloads.serviceDefinitionId]?.let { sd[it] }
                leaves += Leaf(
                    product = info?.product ?: ORPHAN_PRODUCT,
                    team = info?.team ?: ORPHAN_TEAM,
                    serviceId = row[Workloads.serviceDefinitionId],
                    serviceName = info?.name ?: ORPHAN_SERVICE,
                    kind = "workload",
                    name = row[Workloads.workloadName],
                    score = row[AppScorecards.overallScore].toDouble(),
                    weight = tierWeight(info?.tier),
                )
            }

        (Queues leftJoin QueueScorecards)
            .select(Queues.displayName, Queues.serviceDefinitionId, QueueScorecards.overallScore)
            .where { (Queues.tenantId eq tenantId) and (Queues.isActive eq true) }
            .forEach { row ->
                val info = row[Queues.serviceDefinitionId]?.let { sd[it] }
                leaves += Leaf(
                    product = info?.product ?: ORPHAN_PRODUCT,
                    team = info?.team ?: ORPHAN_TEAM,
                    serviceId = row[Queues.serviceDefinitionId],
                    serviceName = info?.name ?: ORPHAN_SERVICE,
                    kind = "queue",
                    name = row[Queues.displayName],
                    score = row[QueueScorecards.overallScore]?.toDouble(),
                    weight = tierWeight(info?.tier),
                )
            }

        return leaves
    }

    private fun buildTree(leaves: List<Leaf>): ReliabilityNodeDTO {
        val productNodes = leaves.groupBy { it.product }.map { (product, pLeaves) ->
            val teamNodes = pLeaves.groupBy { it.team }.map { (team, tLeaves) ->
                val serviceNodes = tLeaves.groupBy { it.serviceId }.map { (sid, sLeaves) ->
                    serviceNode(product, team, sid, sLeaves)
                }.sortedByDescending { it.debt }
                parentNode("$product/$team", "team", team, serviceNodes)
            }.sortedByDescending { it.debt }
            parentNode(product, "product", product, teamNodes)
        }.sortedByDescending { it.debt }

        return parentNode("", "estate", "Estate", productNodes)
    }

    private fun serviceNode(product: String, team: String, sid: Long?, leaves: List<Leaf>): ReliabilityNodeDTO {
        var debt = 0.0; var sumWeight = 0.0; var scored = 0; var breach = false
        leaves.forEach { l ->
            val s = l.score ?: return@forEach
            debt += l.weight * (100.0 - s)
            sumWeight += l.weight
            scored++
            if (l.weight >= CRIT_WEIGHT && s < CRIT_SCORE) breach = true
        }
        return ReliabilityNodeDTO(
            path = "$product/$team/${sid ?: "orphan"}",
            kind = "service",
            name = leaves.first().serviceName,
            ri = riOf(debt, sumWeight),
            debt = debt,
            weight = sumWeight,
            coverage = if (leaves.isNotEmpty()) scored.toDouble() / leaves.size else 0.0,
            scoredLeaves = scored,
            totalLeaves = leaves.size,
            criticalBreach = breach,
            hasChildren = scored > 0 && sid != null,
        )
    }

    private fun parentNode(path: String, kind: String, name: String, children: List<ReliabilityNodeDTO>): ReliabilityNodeDTO {
        val debt = children.sumOf { it.debt }
        val sumWeight = children.sumOf { it.weight }
        val scored = children.sumOf { it.scoredLeaves }
        val total = children.sumOf { it.totalLeaves }
        return ReliabilityNodeDTO(
            path = path,
            kind = kind,
            name = name,
            ri = riOf(debt, sumWeight),
            debt = debt,
            weight = sumWeight,
            coverage = if (total > 0) scored.toDouble() / total else 0.0,
            scoredLeaves = scored,
            totalLeaves = total,
            criticalBreach = children.any { it.criticalBreach },
            hasChildren = children.isNotEmpty(),
            children = children,
        )
    }

    suspend fun getServiceFindings(tenantId: Long, serviceDefinitionId: Long): List<ReliabilityFindingDTO> = dbQuery {
        val sdRow = ServiceDefinitions
            .select(ServiceDefinitions.tier)
            .where { (ServiceDefinitions.serviceDefinitionId eq serviceDefinitionId) and (ServiceDefinitions.tenantId eq tenantId) }
            .singleOrNull() ?: return@dbQuery emptyList()
        val w = tierWeight(sdRow[ServiceDefinitions.tier])

        val out = mutableListOf<ReliabilityFindingDTO>()

        // --- folhas workload ---
        val wlScores = (AppScorecards innerJoin Workloads)
            .select(AppScorecards.appScorecardId, Workloads.workloadName, Workloads.k8sUid, AppScorecards.overallScore)
            .where { (AppScorecards.tenantId eq tenantId) and (Workloads.serviceDefinitionId eq serviceDefinitionId) }
            .map { WlLeaf(it[AppScorecards.appScorecardId], it[Workloads.workloadName], it[Workloads.k8sUid], it[AppScorecards.overallScore].toDouble()) }

        // --- folhas queue ---
        val qScores = (QueueScorecards innerJoin Queues)
            .select(QueueScorecards.queueScorecardId, Queues.displayName, QueueScorecards.overallScore)
            .where { (Queues.tenantId eq tenantId) and (Queues.serviceDefinitionId eq serviceDefinitionId) }
            .mapNotNull { row ->
                val score = row[QueueScorecards.overallScore]?.toDouble() ?: return@mapNotNull null
                Triple(row[QueueScorecards.queueScorecardId], row[Queues.displayName], score)
            }

        val serviceSumWeight = (wlScores.size + qScores.size) * w
        if (serviceSumWeight <= 0.0) return@dbQuery emptyList()

        wlScores.forEach { leaf ->
            val fails = (ValidationResults innerJoin ValidationRules)
                .select(
                    ValidationRules.ruleId, ValidationRules.pillar, ValidationRules.ruleSeverity,
                    ValidationRules.weight, ValidationRules.isRemediable,
                    ValidationResults.resultMessage, ValidationResults.actualValue,
                )
                .where { (ValidationResults.appScorecardId eq leaf.scId) and (ValidationResults.rulePassed eq false) }
                .toList()
            val sumRuleWeight = fails.sumOf { it[ValidationRules.weight].toDouble() }.takeIf { it > 0 }
            val leafGap = w * (100.0 - leaf.score)
            fails.forEach { f ->
                val share = if (sumRuleWeight != null) f[ValidationRules.weight].toDouble() / sumRuleWeight
                else 1.0 / fails.size
                val debt = leafGap * share
                out += ReliabilityFindingDTO(
                    leafKind = "workload", leafName = leaf.name, workloadUid = leaf.uid,
                    ruleId = f[ValidationRules.ruleId],
                    pillar = f[ValidationRules.pillar],
                    severity = f[ValidationRules.ruleSeverity],
                    message = f[ValidationResults.resultMessage],
                    actualValue = f[ValidationResults.actualValue],
                    debt = debt,
                    riGainService = debt / serviceSumWeight,
                    remediable = f[ValidationRules.isRemediable],
                )
            }
        }

        qScores.forEach { (scId, name, score) ->
            val fails = QueueValidationResults
                .select(QueueValidationResults.columns)
                .where { (QueueValidationResults.queueScorecardId eq scId) and (QueueValidationResults.rulePassed eq false) }
                .toList()
            val sumSev = fails.sumOf { severityWeight(it[QueueValidationResults.severity]) }.takeIf { it > 0 }
            val leafGap = w * (100.0 - score)
            fails.forEach { f ->
                val share = if (sumSev != null) severityWeight(f[QueueValidationResults.severity]) / sumSev
                else 1.0 / fails.size
                val debt = leafGap * share
                out += ReliabilityFindingDTO(
                    leafKind = "queue", leafName = name,
                    ruleId = f[QueueValidationResults.ruleId],
                    pillar = f[QueueValidationResults.pillar],
                    severity = f[QueueValidationResults.severity],
                    message = f[QueueValidationResults.resultMessage],
                    actualValue = f[QueueValidationResults.actualValue],
                    debt = debt,
                    riGainService = debt / serviceSumWeight,
                    remediable = false,
                )
            }
        }

        out.sortedByDescending { it.debt }
    }

    // Tendência de RI por dia para o nó (escopo por path), ponderada por tier — base titlis_ts.scorecard_scores.
    suspend fun getTrend(tenantId: Long, rootPath: String, days: Int): List<ReliabilityTrendPointDTO> = dbQuery {
        val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days.toLong())
        val scopeSds = resolveScope(tenantId, rootPath) // null = estate (todos os workloads do tenant)

        val rows: List<Triple<Long, Double, LocalDate>> = if (scopeSds == null) {
            ScorecardScores
                .select(ScorecardScores.workloadId, ScorecardScores.overallScore, ScorecardScores.recordedAt)
                .where { (ScorecardScores.tenantId eq tenantId) and (ScorecardScores.recordedAt greaterEq cutoff) }
                .map { Triple(it[ScorecardScores.workloadId], it[ScorecardScores.overallScore].toDouble(), it[ScorecardScores.recordedAt].toLocalDate()) }
        } else {
            if (scopeSds.isEmpty()) return@dbQuery emptyList()
            val wlIds = Workloads
                .select(Workloads.workloadId)
                .where { (Workloads.serviceDefinitionId inList scopeSds) and (Workloads.isActive eq true) }
                .map { it[Workloads.workloadId] }
            if (wlIds.isEmpty()) return@dbQuery emptyList()
            ScorecardScores
                .select(ScorecardScores.workloadId, ScorecardScores.overallScore, ScorecardScores.recordedAt)
                .where { (ScorecardScores.workloadId inList wlIds) and (ScorecardScores.recordedAt greaterEq cutoff) }
                .map { Triple(it[ScorecardScores.workloadId], it[ScorecardScores.overallScore].toDouble(), it[ScorecardScores.recordedAt].toLocalDate()) }
        }
        if (rows.isEmpty()) return@dbQuery emptyList()

        val involved = rows.map { it.first }.distinct()
        val tierByWl = (Workloads leftJoin ServiceDefinitions)
            .select(Workloads.workloadId, ServiceDefinitions.tier)
            .where { Workloads.workloadId inList involved }
            .associate { it[Workloads.workloadId] to tierWeight(it[ServiceDefinitions.tier]) }

        rows.groupBy { it.third }
            .toSortedMap()
            .map { (date, dayRows) ->
                var num = 0.0; var den = 0.0
                dayRows.forEach { r ->
                    val weight = tierByWl[r.first] ?: 1.0
                    num += weight * r.second; den += weight
                }
                ReliabilityTrendPointDTO(date = date.toString(), ri = if (den > 0) num / den else 0.0)
            }
    }

    private fun resolveScope(tenantId: Long, path: String): Set<Long>? {
        if (path.isBlank()) return null
        val segs = path.split('/')
        return when (segs.size) {
            1 -> sdsWhere(tenantId) { ServiceDefinitions.product eq segs[0] }
            2 -> sdsWhere(tenantId) { (ServiceDefinitions.product eq segs[0]) and (ServiceDefinitions.team eq segs[1]) }
            else -> setOf(segs[2].toLongOrNull() ?: -1L)
        }
    }

    private fun sdsWhere(tenantId: Long, cond: SqlExpressionBuilder.() -> Op<Boolean>): Set<Long> =
        ServiceDefinitions
            .select(ServiceDefinitions.serviceDefinitionId)
            .where { (ServiceDefinitions.tenantId eq tenantId) and cond() }
            .map { it[ServiceDefinitions.serviceDefinitionId] }
            .toSet()
}
