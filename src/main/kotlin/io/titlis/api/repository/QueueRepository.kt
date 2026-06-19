package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.*
import io.titlis.api.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

class QueueRepository {
    private val log = LoggerFactory.getLogger(QueueRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private data class PatternRow(
        val serviceDefinitionId: Long,
        val pattern: String,
        val matchType: String,
        val matchField: String,
    )

    private fun encodeLabels(labels: Map<String, String>): String? =
        if (labels.isEmpty()) null else json.encodeToString(labels)

    private fun loadPatterns(tenantId: Long, provider: String): List<PatternRow> =
        ServiceQueuePatterns
            .select(
                ServiceQueuePatterns.serviceDefinitionId,
                ServiceQueuePatterns.pattern,
                ServiceQueuePatterns.matchType,
                ServiceQueuePatterns.matchField,
            )
            .where { (ServiceQueuePatterns.tenantId eq tenantId) and (ServiceQueuePatterns.provider eq provider) }
            .map {
                PatternRow(
                    serviceDefinitionId = it[ServiceQueuePatterns.serviceDefinitionId],
                    pattern             = it[ServiceQueuePatterns.pattern],
                    matchType           = it[ServiceQueuePatterns.matchType],
                    matchField          = it[ServiceQueuePatterns.matchField],
                )
            }

    private fun loadServiceDefs(tenantId: Long): List<Pair<Long, String>> =
        ServiceDefinitions
            .select(ServiceDefinitions.serviceDefinitionId, ServiceDefinitions.serviceName)
            .where { ServiceDefinitions.tenantId eq tenantId }
            .map { it[ServiceDefinitions.serviceDefinitionId] to it[ServiceDefinitions.serviceName] }

    private fun globToRegex(pattern: String): Regex =
        Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$")

    private fun matchesPattern(p: PatternRow, displayName: String, externalId: String, topicId: String?): Boolean {
        val target = when (p.matchField) {
            "external_id" -> externalId
            "topic_id"    -> topicId ?: return false
            else          -> displayName
        }
        return when (p.matchType) {
            "exact"  -> target == p.pattern
            "prefix" -> target.startsWith(p.pattern.removeSuffix("*"))
            else     -> globToRegex(p.pattern).matches(target)
        }
    }

    private fun normalizeName(name: String): Set<String> =
        name.lowercase()
            .removeSuffix("-sub").removeSuffix("-dlq").removeSuffix("-retry")
            .split('-', '_', '.', '/')
            .filter { it.isNotBlank() && it != "projects" && it != "subscriptions" && it != "topics" }
            .toSet()

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return inter / union
    }

    private fun setLink(queueId: Long, serviceDefinitionId: Long, source: String, confidence: BigDecimal, now: OffsetDateTime) {
        Queues.update({ Queues.queueId eq queueId }) {
            it[Queues.serviceDefinitionId] = serviceDefinitionId
            it[linkSource]                 = source
            it[linkConfidence]             = confidence
            it[linkedAt]                   = now
        }
    }

    private fun replaceSuggestions(
        tenantId: Long,
        queueId: Long,
        candidates: List<Pair<Long, BigDecimal>>,
        source: String,
        now: OffsetDateTime,
    ) {
        QueueLinkSuggestions.deleteWhere {
            (QueueLinkSuggestions.queueId eq queueId) and (QueueLinkSuggestions.suggestionSource eq source)
        }
        candidates.forEach { (sdId, conf) ->
            QueueLinkSuggestions.insert {
                it[QueueLinkSuggestions.tenantId]            = tenantId
                it[QueueLinkSuggestions.queueId]             = queueId
                it[QueueLinkSuggestions.serviceDefinitionId] = sdId
                it[confidence]                               = conf
                it[suggestionSource]                         = source
                it[createdAt]                                = now
            }
        }
    }

    private fun findParentLink(tenantId: Long, provider: String, projectId: String, topicId: String): Long? =
        Queues
            .select(Queues.serviceDefinitionId)
            .where {
                (Queues.tenantId eq tenantId) and (Queues.provider eq provider) and
                    (Queues.projectId eq projectId) and (Queues.topicId eq topicId) and
                    (Queues.isDlq eq false) and (Queues.serviceDefinitionId.isNotNull())
            }
            .firstOrNull()
            ?.get(Queues.serviceDefinitionId)

    // Resolução Fase 1 (padrão declarado) + Fase 2 (sugestão por nome). Roda dentro da transação
    // após o upsert da fila. Nunca sobrescreve link manual.
    private fun resolveAndPersistLink(
        tenantId: Long,
        queueId: Long,
        provider: String,
        displayName: String,
        externalId: String,
        topicId: String?,
        projectId: String?,
        isDlq: Boolean,
        patterns: List<PatternRow>,
        serviceDefs: List<Pair<Long, String>>,
        now: OffsetDateTime,
    ) {
        val current = Queues
            .select(Queues.linkSource, Queues.serviceDefinitionId)
            .where { Queues.queueId eq queueId }
            .single()
        if (current[Queues.linkSource] == "manual") return

        val matched = patterns
            .filter { matchesPattern(it, displayName, externalId, topicId) }
            .map { it.serviceDefinitionId }
            .distinct()

        when {
            matched.size == 1 -> {
                setLink(queueId, matched.first(), "pattern", BigDecimal.ONE, now)
                QueueLinkSuggestions.deleteWhere { QueueLinkSuggestions.queueId eq queueId }
            }
            matched.size > 1 -> {
                replaceSuggestions(tenantId, queueId, matched.map { it to BigDecimal.ONE }, "pattern", now)
            }
            else -> {
                val inherited = if (isDlq && projectId != null && topicId != null) {
                    findParentLink(tenantId, provider, projectId, topicId)
                } else null
                if (inherited != null) {
                    setLink(queueId, inherited, "pattern", BigDecimal.ONE, now)
                    QueueLinkSuggestions.deleteWhere { QueueLinkSuggestions.queueId eq queueId }
                } else if (current[Queues.serviceDefinitionId] == null) {
                    val nameTokens = normalizeName(displayName) + (topicId?.let { normalizeName(it) } ?: emptySet())
                    val suggestions = serviceDefs
                        .map { (sdId, svcName) -> sdId to similarity(nameTokens, normalizeName(svcName)) }
                        .filter { it.second >= 0.5 }
                        .sortedByDescending { it.second }
                        .take(3)
                        .map { it.first to BigDecimal(it.second).setScale(3, java.math.RoundingMode.HALF_UP) }
                    if (suggestions.isNotEmpty()) {
                        replaceSuggestions(tenantId, queueId, suggestions, "name", now)
                    }
                }
            }
        }
    }

    suspend fun recordObservation(tenantId: Long, req: QueueObservationRequest): QueueObserveResponse = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        val existing = Queues
            .select(Queues.queueId, Queues.lifecycleState, Queues.observationCount)
            .where { (Queues.tenantId eq tenantId) and (Queues.provider eq req.provider) and (Queues.externalId eq req.externalId) }
            .singleOrNull()

        val (queueId, newCount) = if (existing == null) {
            val id = Queues.insert {
                it[Queues.tenantId]        = tenantId
                it[provider]               = req.provider
                it[externalId]             = req.externalId
                it[displayName]            = req.displayName
                it[projectId]              = req.projectId
                it[topicId]               = req.topicId
                it[isDlq]                  = req.isDlq
                it[lifecycleState]         = "DISCOVERING"
                it[observationCount]       = 1
                it[firstSeenAt]            = now
                it[lastSeenAt]             = now
                it[labels]                 = encodeLabels(req.labels)
            }[Queues.queueId]
            id to 1
        } else {
            val qid    = existing[Queues.queueId]
            val count  = existing[Queues.observationCount] + 1
            val state  = when {
                existing[Queues.lifecycleState] == "MONITORING" -> "MONITORING"
                count > 2 -> "LEARNING"
                else -> "DISCOVERING"
            }
            Queues.update({ Queues.queueId eq qid }) {
                it[observationCount] = count
                it[lifecycleState]   = state
                it[displayName]      = req.displayName
                it[isDlq]            = req.isDlq
                it[lastSeenAt]       = now
                it[labels]           = encodeLabels(req.labels)
            }
            qid to count
        }

        QueueObservations.insert {
            it[QueueObservations.queueId]                 = queueId
            it[QueueObservations.tenantId]                = tenantId
            it[numUndeliveredMessages]   = req.numUndeliveredMessages
            it[oldestUnackedAgeSeconds]  = req.oldestUnackedAgeSec
            it[pullMessageCountRate]     = req.pullMessageCountRate
            it[sendMessageCountRate]     = req.sendMessageCountRate
            it[ackMessageCountRate]      = req.ackMessageCountRate
            it[observedAt]               = now
        }

        resolveAndPersistLink(
            tenantId, queueId, req.provider, req.displayName, req.externalId, req.topicId, req.projectId, req.isDlq,
            loadPatterns(tenantId, req.provider), loadServiceDefs(tenantId), now,
        )

        val finalState = Queues
            .select(Queues.lifecycleState)
            .where { Queues.queueId eq queueId }
            .single()[Queues.lifecycleState]

        QueueObserveResponse(queueId = queueId, lifecycleState = finalState, observationCount = newCount)
    }

    suspend fun recordObservationBatch(
        tenantId: Long,
        reqs: List<QueueObservationRequest>,
    ): List<QueueBatchObserveResponseItem> = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val serviceDefs = loadServiceDefs(tenantId)
        val patternsByProvider = mutableMapOf<String, List<PatternRow>>()

        reqs.map { req ->
            val existing = Queues
                .select(Queues.queueId, Queues.lifecycleState, Queues.observationCount)
                .where { (Queues.tenantId eq tenantId) and (Queues.provider eq req.provider) and (Queues.externalId eq req.externalId) }
                .singleOrNull()

            val (queueId, newCount, state) = if (existing == null) {
                val id = Queues.insert {
                    it[Queues.tenantId]  = tenantId
                    it[provider]         = req.provider
                    it[externalId]       = req.externalId
                    it[displayName]      = req.displayName
                    it[projectId]        = req.projectId
                    it[topicId]          = req.topicId
                    it[isDlq]            = req.isDlq
                    it[lifecycleState]   = "DISCOVERING"
                    it[observationCount] = 1
                    it[firstSeenAt]      = now
                    it[lastSeenAt]       = now
                    it[labels]           = encodeLabels(req.labels)
                }[Queues.queueId]
                Triple(id, 1, "DISCOVERING")
            } else {
                val qid   = existing[Queues.queueId]
                val count = existing[Queues.observationCount] + 1
                val s     = when {
                    existing[Queues.lifecycleState] == "MONITORING" -> "MONITORING"
                    count > 2 -> "LEARNING"
                    else -> "DISCOVERING"
                }
                Queues.update({ Queues.queueId eq qid }) {
                    it[observationCount] = count
                    it[lifecycleState]   = s
                    it[displayName]      = req.displayName
                    it[isDlq]            = req.isDlq
                    it[lastSeenAt]       = now
                    it[labels]           = encodeLabels(req.labels)
                }
                Triple(qid, count, s)
            }

            QueueObservations.insert {
                it[QueueObservations.queueId]       = queueId
                it[QueueObservations.tenantId]      = tenantId
                it[numUndeliveredMessages]          = req.numUndeliveredMessages
                it[oldestUnackedAgeSeconds]         = req.oldestUnackedAgeSec
                it[pullMessageCountRate]            = req.pullMessageCountRate
                it[sendMessageCountRate]            = req.sendMessageCountRate
                it[ackMessageCountRate]             = req.ackMessageCountRate
                it[observedAt]                      = now
            }

            resolveAndPersistLink(
                tenantId, queueId, req.provider, req.displayName, req.externalId, req.topicId, req.projectId, req.isDlq,
                patternsByProvider.getOrPut(req.provider) { loadPatterns(tenantId, req.provider) },
                serviceDefs, now,
            )

            val thresholds = if (state == "MONITORING") {
                QueueThresholds
                    .select(QueueThresholds.columns)
                    .where { QueueThresholds.queueId eq queueId }
                    .singleOrNull()
                    ?.let { t ->
                        QueueThresholdsDTO(
                            backlogWarning   = t[QueueThresholds.backlogWarning],
                            backlogCritical  = t[QueueThresholds.backlogCritical],
                            ageWarningSec    = t[QueueThresholds.ageWarningSec],
                            ageCriticalSec   = t[QueueThresholds.ageCriticalSec],
                            p50Backlog       = t[QueueThresholds.p50Backlog],
                            p75Backlog       = t[QueueThresholds.p75Backlog],
                            p95Backlog       = t[QueueThresholds.p95Backlog],
                            p50AgeSec        = t[QueueThresholds.p50AgeSec],
                            p75AgeSec        = t[QueueThresholds.p75AgeSec],
                            p95AgeSec        = t[QueueThresholds.p95AgeSec],
                            calculatedAt     = t[QueueThresholds.calculatedAt].toString(),
                            observationCount = t[QueueThresholds.observationCount],
                        )
                    }
            } else null

            QueueBatchObserveResponseItem(
                externalId       = req.externalId,
                queueId          = queueId,
                lifecycleState   = state,
                observationCount = newCount,
                thresholds       = thresholds,
            )
        }
    }

    suspend fun getLifecycle(tenantId: Long, externalId: String, provider: String = "gcp_pubsub"): QueueLifecycleDTO? = dbQuery {
        val row = Queues
            .select(Queues.queueId, Queues.lifecycleState, Queues.observationCount)
            .where { (Queues.tenantId eq tenantId) and (Queues.provider eq provider) and (Queues.externalId eq externalId) }
            .singleOrNull() ?: return@dbQuery null

        val queueId = row[Queues.queueId]
        val state   = row[Queues.lifecycleState]
        val count   = row[Queues.observationCount]

        val thresholds = if (state == "MONITORING") {
            QueueThresholds
                .select(QueueThresholds.columns)
                .where { QueueThresholds.queueId eq queueId }
                .singleOrNull()
                ?.let { t ->
                    QueueThresholdsDTO(
                        backlogWarning   = t[QueueThresholds.backlogWarning],
                        backlogCritical  = t[QueueThresholds.backlogCritical],
                        ageWarningSec    = t[QueueThresholds.ageWarningSec],
                        ageCriticalSec   = t[QueueThresholds.ageCriticalSec],
                        p50Backlog       = t[QueueThresholds.p50Backlog],
                        p75Backlog       = t[QueueThresholds.p75Backlog],
                        p95Backlog       = t[QueueThresholds.p95Backlog],
                        p50AgeSec        = t[QueueThresholds.p50AgeSec],
                        p75AgeSec        = t[QueueThresholds.p75AgeSec],
                        p95AgeSec        = t[QueueThresholds.p95AgeSec],
                        calculatedAt     = t[QueueThresholds.calculatedAt].toString(),
                        observationCount = t[QueueThresholds.observationCount],
                    )
                }
        } else null

        QueueLifecycleDTO(state = state, observationCount = count, thresholds = thresholds)
    }

    suspend fun promoteToMonitoring(tenantId: Long, externalId: String, provider: String = "gcp_pubsub"): QueueThresholdsDTO? = dbQuery {
        val row = Queues
            .select(Queues.queueId, Queues.lifecycleState, Queues.observationCount)
            .where { (Queues.tenantId eq tenantId) and (Queues.provider eq provider) and (Queues.externalId eq externalId) }
            .singleOrNull() ?: return@dbQuery null

        val queueId = row[Queues.queueId]
        val count   = row[Queues.observationCount]
        val now     = OffsetDateTime.now(ZoneOffset.UTC)

        data class Percentiles(
            val p50b: Long, val p75b: Long, val p95b: Long,
            val p50a: Long, val p75a: Long, val p95a: Long,
        )

        val pcts = TransactionManager.current().exec(
            """
            SELECT
                COALESCE(PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY num_undelivered_messages), 0)::BIGINT as p50b,
                COALESCE(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY num_undelivered_messages), 0)::BIGINT as p75b,
                COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY num_undelivered_messages), 0)::BIGINT as p95b,
                COALESCE(PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY oldest_unacked_age_seconds), 0)::BIGINT as p50a,
                COALESCE(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY oldest_unacked_age_seconds), 0)::BIGINT as p75a,
                COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY oldest_unacked_age_seconds), 0)::BIGINT as p95a
            FROM titlis_oltp.queue_observations
            WHERE queue_id = ?
            """.trimIndent(),
            args = listOf(LongColumnType() to queueId),
        ) { rs ->
            if (rs.next()) Percentiles(
                p50b = rs.getLong("p50b"), p75b = rs.getLong("p75b"), p95b = rs.getLong("p95b"),
                p50a = rs.getLong("p50a"), p75a = rs.getLong("p75a"), p95a = rs.getLong("p95a"),
            ) else null
        } ?: return@dbQuery null

        val backlogWarning  = maxOf(1L, (pcts.p75b * 1.2).toLong())
        val backlogCritical = maxOf(1L, (pcts.p95b * 1.5).toLong())
        val ageWarning      = maxOf(1L, (pcts.p75a * 1.2).toLong())
        val ageCritical     = maxOf(1L, (pcts.p95a * 1.5).toLong())

        QueueThresholds.upsert(QueueThresholds.queueId) {
            it[QueueThresholds.queueId]        = queueId
            it[QueueThresholds.tenantId]       = tenantId
            it[p50Backlog]      = pcts.p50b
            it[p75Backlog]      = pcts.p75b
            it[p95Backlog]      = pcts.p95b
            it[p50AgeSec]       = pcts.p50a
            it[p75AgeSec]       = pcts.p75a
            it[p95AgeSec]       = pcts.p95a
            it[QueueThresholds.backlogWarning]   = backlogWarning
            it[QueueThresholds.backlogCritical]  = backlogCritical
            it[QueueThresholds.ageWarningSec]    = ageWarning
            it[QueueThresholds.ageCriticalSec]   = ageCritical
            it[QueueThresholds.observationCount] = count
            it[calculatedAt]    = now
        }

        Queues.update({ Queues.queueId eq queueId }) {
            it[lifecycleState] = "MONITORING"
        }

        QueueThresholdsDTO(
            backlogWarning   = backlogWarning,
            backlogCritical  = backlogCritical,
            ageWarningSec    = ageWarning,
            ageCriticalSec   = ageCritical,
            p50Backlog       = pcts.p50b,
            p75Backlog       = pcts.p75b,
            p95Backlog       = pcts.p95b,
            p50AgeSec        = pcts.p50a,
            p75AgeSec        = pcts.p75a,
            p95AgeSec        = pcts.p95a,
            calculatedAt     = now.toString(),
            observationCount = count,
        )
    }

    suspend fun upsertQueueScorecard(event: QueueEvaluatedEvent) = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        val queueId = Queues
            .select(Queues.queueId)
            .where {
                (Queues.tenantId eq event.tenantId) and
                (Queues.provider eq event.provider) and
                (Queues.externalId eq event.externalId)
            }
            .singleOrNull()
            ?.get(Queues.queueId) ?: run {
                log.warn("queue-evaluated: queue not found provider={} externalId={} tenant={}", event.provider, event.externalId, event.tenantId)
                return@dbQuery
            }

        val evaluatedAt = runCatching { OffsetDateTime.parse(event.evaluatedAt) }.getOrNull() ?: now

        QueueScorecards.upsert(
            QueueScorecards.queueId, QueueScorecards.tenantId,
            onUpdateExclude = emptyList(),
        ) {
            it[QueueScorecards.queueId]      = queueId
            it[QueueScorecards.tenantId]     = event.tenantId
            it[overallScore]                 = event.overallScore.toBigDecimal()
            it[complianceStatus]             = event.complianceStatus.uppercase()
            it[totalRules]                   = event.totalRules
            it[passedRules]                  = event.passedRules
            it[failedRules]                  = event.failedRules
            it[criticalFailures]             = event.criticalFailures
            it[errorCount]                   = event.errorCount
            it[warningCount]                 = event.warningCount
            it[QueueScorecards.evaluatedAt]  = evaluatedAt
            it[rawMetadata]                  = event.rawMetadata
        }

        val scorecardId = QueueScorecards
            .select(QueueScorecards.queueScorecardId)
            .where { (QueueScorecards.queueId eq queueId) and (QueueScorecards.tenantId eq event.tenantId) }
            .single()[QueueScorecards.queueScorecardId]

        QueuePillarScores.deleteWhere { queueScorecardId eq scorecardId }
        event.pillarScores.forEach { p ->
            QueuePillarScores.insert {
                it[queueScorecardId] = scorecardId
                it[pillar]           = p.pillar
                it[pillarScore]      = p.pillarScore.toBigDecimal()
                it[passedChecks]     = p.passedChecks
                it[failedChecks]     = p.failedChecks
                it[weightedScore]    = p.weightedScore?.toBigDecimal()
            }
        }

        QueueValidationResults.deleteWhere { queueScorecardId eq scorecardId }
        event.validationResults.forEach { v ->
            QueueValidationResults.insert {
                it[queueScorecardId] = scorecardId
                it[ruleId]                             = v.ruleId
                it[ruleName]                           = v.ruleName
                it[pillar]                             = v.pillar
                it[severity]                           = v.severity
                it[rulePassed]                         = v.rulePassed
                it[resultMessage]                      = v.resultMessage
                it[actualValue]                        = v.actualValue
                it[QueueValidationResults.evaluatedAt] = evaluatedAt
            }
        }
    }

    suspend fun listQueues(tenantId: Long): List<QueueSummaryDTO> = dbQuery {
        val scores = QueueScorecards
            .select(
                QueueScorecards.queueId,
                QueueScorecards.overallScore,
                QueueScorecards.complianceStatus,
            )
            .where { QueueScorecards.tenantId eq tenantId }
            .associate { it[QueueScorecards.queueId] to it }

        val svcInfo = serviceInfoMap(tenantId)

        val suggCounts = QueueLinkSuggestions
            .select(QueueLinkSuggestions.queueId, QueueLinkSuggestions.queueId.count())
            .where { QueueLinkSuggestions.tenantId eq tenantId }
            .groupBy(QueueLinkSuggestions.queueId)
            .associate { it[QueueLinkSuggestions.queueId] to it[QueueLinkSuggestions.queueId.count()].toInt() }

        Queues
            .select(Queues.columns)
            .where { (Queues.tenantId eq tenantId) and (Queues.isActive eq true) }
            .orderBy(Queues.displayName)
            .map { row ->
                val sc = scores[row[Queues.queueId]]
                val sdId = row[Queues.serviceDefinitionId]
                val info = sdId?.let { svcInfo[it] }
                QueueSummaryDTO(
                    queueId             = row[Queues.queueId],
                    externalId          = row[Queues.externalId],
                    displayName         = row[Queues.displayName],
                    provider            = row[Queues.provider],
                    isDlq               = row[Queues.isDlq],
                    lifecycleState      = row[Queues.lifecycleState],
                    observationCount    = row[Queues.observationCount],
                    overallScore        = sc?.get(QueueScorecards.overallScore)?.toDouble(),
                    complianceStatus    = sc?.get(QueueScorecards.complianceStatus),
                    firstSeenAt         = row[Queues.firstSeenAt].toString(),
                    lastSeenAt          = row[Queues.lastSeenAt].toString(),
                    serviceDefinitionId = sdId,
                    serviceName         = info?.first,
                    team                = info?.second,
                    linkSource          = row[Queues.linkSource],
                    suggestionCount     = suggCounts[row[Queues.queueId]] ?: 0,
                )
            }
    }

    private fun serviceInfoMap(tenantId: Long): Map<Long, Pair<String, String?>> =
        ServiceDefinitions
            .select(ServiceDefinitions.serviceDefinitionId, ServiceDefinitions.serviceName, ServiceDefinitions.team)
            .where { ServiceDefinitions.tenantId eq tenantId }
            .associate { it[ServiceDefinitions.serviceDefinitionId] to (it[ServiceDefinitions.serviceName] to it[ServiceDefinitions.team]) }

    private fun suggestionsFor(tenantId: Long, queueId: Long, svcInfo: Map<Long, Pair<String, String?>>): List<QueueLinkSuggestionDTO> =
        QueueLinkSuggestions
            .select(QueueLinkSuggestions.columns)
            .where { (QueueLinkSuggestions.tenantId eq tenantId) and (QueueLinkSuggestions.queueId eq queueId) }
            .mapNotNull { row ->
                val sdId = row[QueueLinkSuggestions.serviceDefinitionId]
                val info = svcInfo[sdId] ?: return@mapNotNull null
                QueueLinkSuggestionDTO(
                    serviceDefinitionId = sdId,
                    serviceName         = info.first,
                    team                = info.second,
                    confidence          = row[QueueLinkSuggestions.confidence].toDouble(),
                    source              = row[QueueLinkSuggestions.suggestionSource],
                )
            }
            .sortedByDescending { it.confidence }

    suspend fun getSuggestions(tenantId: Long, queueId: Long): List<QueueLinkSuggestionDTO> = dbQuery {
        suggestionsFor(tenantId, queueId, serviceInfoMap(tenantId))
    }

    suspend fun linkQueue(tenantId: Long, queueId: Long, serviceDefinitionId: Long): Boolean = dbQuery {
        val queueExists = Queues
            .select(Queues.queueId)
            .where { (Queues.queueId eq queueId) and (Queues.tenantId eq tenantId) }
            .singleOrNull() != null
        if (!queueExists) return@dbQuery false

        val svcOk = ServiceDefinitions
            .select(ServiceDefinitions.serviceDefinitionId)
            .where { (ServiceDefinitions.serviceDefinitionId eq serviceDefinitionId) and (ServiceDefinitions.tenantId eq tenantId) }
            .singleOrNull() != null
        if (!svcOk) return@dbQuery false

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        setLink(queueId, serviceDefinitionId, "manual", BigDecimal.ONE, now)
        QueueLinkSuggestions.deleteWhere { QueueLinkSuggestions.queueId eq queueId }
        true
    }

    suspend fun getQueueScorecard(tenantId: Long, queueId: Long): QueueDetailDTO? = dbQuery {
        val queueRow = Queues
            .select(Queues.columns)
            .where { (Queues.queueId eq queueId) and (Queues.tenantId eq tenantId) }
            .singleOrNull() ?: return@dbQuery null

        val sc = QueueScorecards
            .select(QueueScorecards.columns)
            .where { (QueueScorecards.queueId eq queueId) and (QueueScorecards.tenantId eq tenantId) }
            .singleOrNull()

        val scorecardId = sc?.get(QueueScorecards.queueScorecardId)

        val pillarScores = if (scorecardId != null) {
            QueuePillarScores
                .select(QueuePillarScores.columns)
                .where { QueuePillarScores.queueScorecardId eq scorecardId }
                .map { p ->
                    QueuePillarScoreDTO(
                        pillar        = p[QueuePillarScores.pillar],
                        pillarScore   = p[QueuePillarScores.pillarScore]?.toDouble() ?: 0.0,
                        passedChecks  = p[QueuePillarScores.passedChecks] ?: 0,
                        failedChecks  = p[QueuePillarScores.failedChecks] ?: 0,
                        weightedScore = p[QueuePillarScores.weightedScore]?.toDouble(),
                    )
                }
        } else emptyList()

        val validationResults = if (scorecardId != null) {
            QueueValidationResults
                .select(QueueValidationResults.columns)
                .where { QueueValidationResults.queueScorecardId eq scorecardId }
                .map { v ->
                    QueueValidationResultDTO(
                        ruleId        = v[QueueValidationResults.ruleId],
                        ruleName      = v[QueueValidationResults.ruleName],
                        pillar        = v[QueueValidationResults.pillar],
                        severity      = v[QueueValidationResults.severity],
                        rulePassed    = v[QueueValidationResults.rulePassed],
                        resultMessage = v[QueueValidationResults.resultMessage],
                        actualValue   = v[QueueValidationResults.actualValue],
                    )
                }
        } else emptyList()

        val svcInfo = serviceInfoMap(tenantId)
        val sdId = queueRow[Queues.serviceDefinitionId]
        val info = sdId?.let { svcInfo[it] }

        QueueDetailDTO(
            queueId          = queueRow[Queues.queueId],
            externalId       = queueRow[Queues.externalId],
            displayName      = queueRow[Queues.displayName],
            provider         = queueRow[Queues.provider],
            isDlq            = queueRow[Queues.isDlq],
            lifecycleState   = queueRow[Queues.lifecycleState],
            observationCount = queueRow[Queues.observationCount],
            overallScore     = sc?.get(QueueScorecards.overallScore)?.toDouble(),
            complianceStatus = sc?.get(QueueScorecards.complianceStatus),
            totalRules       = sc?.get(QueueScorecards.totalRules),
            passedRules      = sc?.get(QueueScorecards.passedRules),
            failedRules      = sc?.get(QueueScorecards.failedRules),
            criticalFailures = sc?.get(QueueScorecards.criticalFailures),
            errorCount       = sc?.get(QueueScorecards.errorCount),
            warningCount     = sc?.get(QueueScorecards.warningCount),
            evaluatedAt      = sc?.get(QueueScorecards.evaluatedAt)?.toString(),
            pillarScores     = pillarScores,
            validationResults = validationResults,
            firstSeenAt      = queueRow[Queues.firstSeenAt].toString(),
            lastSeenAt       = queueRow[Queues.lastSeenAt].toString(),
            serviceDefinitionId = sdId,
            serviceName      = info?.first,
            team             = info?.second,
            linkSource       = queueRow[Queues.linkSource],
            suggestions      = suggestionsFor(tenantId, queueId, svcInfo),
        )
    }

    suspend fun getQueueThresholds(tenantId: Long, queueId: Long): QueueThresholdsDTO? = dbQuery {
        Queues
            .select(Queues.queueId)
            .where { (Queues.queueId eq queueId) and (Queues.tenantId eq tenantId) }
            .singleOrNull() ?: return@dbQuery null

        QueueThresholds
            .select(QueueThresholds.columns)
            .where { QueueThresholds.queueId eq queueId }
            .singleOrNull()
            ?.let { t ->
                QueueThresholdsDTO(
                    backlogWarning   = t[QueueThresholds.backlogWarning],
                    backlogCritical  = t[QueueThresholds.backlogCritical],
                    ageWarningSec    = t[QueueThresholds.ageWarningSec],
                    ageCriticalSec   = t[QueueThresholds.ageCriticalSec],
                    p50Backlog       = t[QueueThresholds.p50Backlog],
                    p75Backlog       = t[QueueThresholds.p75Backlog],
                    p95Backlog       = t[QueueThresholds.p95Backlog],
                    p50AgeSec        = t[QueueThresholds.p50AgeSec],
                    p75AgeSec        = t[QueueThresholds.p75AgeSec],
                    p95AgeSec        = t[QueueThresholds.p95AgeSec],
                    calculatedAt     = t[QueueThresholds.calculatedAt].toString(),
                    observationCount = t[QueueThresholds.observationCount],
                )
            }
    }

    suspend fun countsByLifecycle(tenantId: Long): Map<String, Int> = dbQuery {
        Queues
            .select(Queues.lifecycleState, Queues.lifecycleState.count())
            .where { (Queues.tenantId eq tenantId) and (Queues.isActive eq true) }
            .groupBy(Queues.lifecycleState)
            .associate { it[Queues.lifecycleState] to it[Queues.lifecycleState.count()].toInt() }
    }

    // Fase 3 — nomes de fila conhecidos para o operator casar localmente contra env vars.
    suspend fun queueNamesFor(tenantId: Long): List<QueueNameDTO> = dbQuery {
        Queues
            .select(Queues.externalId, Queues.displayName)
            .where { (Queues.tenantId eq tenantId) and (Queues.isActive eq true) }
            .map { QueueNameDTO(externalId = it[Queues.externalId], displayName = it[Queues.displayName]) }
    }

    // Fase 3 — hints de env var: queue (por externalId/displayName) → service (por serviceName==workloadName).
    suspend fun recordLinkHints(tenantId: Long, hints: List<QueueLinkHint>): Int = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val byQueue = mutableMapOf<Long, MutableSet<Long>>()

        hints.forEach { hint ->
            val queueId = resolveQueueId(tenantId, hint.externalId, hint.displayName) ?: return@forEach
            val sdId = ServiceDefinitions
                .select(ServiceDefinitions.serviceDefinitionId)
                .where { (ServiceDefinitions.tenantId eq tenantId) and (ServiceDefinitions.serviceName eq hint.workloadName) }
                .firstOrNull()
                ?.get(ServiceDefinitions.serviceDefinitionId) ?: return@forEach
            byQueue.getOrPut(queueId) { mutableSetOf() }.add(sdId)
        }

        var count = 0
        byQueue.forEach { (queueId, sdIds) ->
            val source = Queues.select(Queues.linkSource).where { Queues.queueId eq queueId }.single()[Queues.linkSource]
            if (source == "manual") return@forEach
            replaceSuggestions(tenantId, queueId, sdIds.map { it to BigDecimal("0.700") }, "env", now)
            count += sdIds.size
        }
        count
    }

    private fun resolveQueueId(tenantId: Long, externalId: String?, displayName: String?): Long? {
        if (!externalId.isNullOrBlank()) {
            Queues.select(Queues.queueId)
                .where { (Queues.tenantId eq tenantId) and (Queues.externalId eq externalId) }
                .firstOrNull()?.let { return it[Queues.queueId] }
        }
        if (!displayName.isNullOrBlank()) {
            Queues.select(Queues.queueId)
                .where { (Queues.tenantId eq tenantId) and (Queues.displayName eq displayName) }
                .firstOrNull()?.let { return it[Queues.queueId] }
        }
        return null
    }
}
