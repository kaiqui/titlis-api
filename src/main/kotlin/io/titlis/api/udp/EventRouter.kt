package io.titlis.api.udp

import io.titlis.api.domain.*
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.MetricsRepository
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.SloRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory

class EventRouter(
    private val scorecardRepo: ScorecardRepository,
    private val remediationRepo: RemediationRepository,
    private val sloRepo: SloRepository,
    private val metricsRepo: MetricsRepository,
    private val apiKeyRepo: ApiKeyRepository,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(EventRouter::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun route(payload: ByteArray) {
        val raw = payload.decodeToString()
        val envelope = runCatching { json.decodeFromString<UdpEnvelope>(raw) }
            .getOrElse {
                logger.warn("Invalid UDP envelope: ${raw.take(200)}")
                return
            }

        if (envelope.v != 1) {
            logger.warn("Unsupported protocol version ${envelope.v}")
            return
        }

        if (envelope.apiKey == null) {
            logger.warn("UDP evento descartado: envelope sem api_key [tipo=${envelope.t}]")
            return
        }

        val tenantId = apiKeyRepo.resolveByToken(envelope.apiKey)
        if (tenantId == null) {
            logger.warn("UDP evento descartado: api_key inválida ou revogada [prefix=${envelope.apiKey.take(12)}]")
            return
        }

        scope.launch(Dispatchers.IO) {
            apiKeyRepo.updateLastUsedAtAsync(envelope.apiKey)
        }

        when (envelope.t) {
            "scorecard_evaluated" -> {
                val event = json.decodeFromJsonElement<ScorecardEvaluatedEvent>(envelope.data)
                scorecardRepo.upsertScorecard(event, tenantId)
                if (event.validationResults.isNotEmpty()) {
                    val passedRuleIds = event.validationResults
                        .filter { it.passed }
                        .map { it.ruleId }
                        .toSet()
                    remediationRepo.autoResolveIfAllFixed(event.workloadId, tenantId, passedRuleIds)
                }
            }
            "remediation_started", "remediation_updated" -> {
                val event = json.decodeFromJsonElement<RemediationEvent>(envelope.data)
                remediationRepo.upsertRemediation(event, tenantId)
            }
            "slo_reconciled" -> {
                val event = json.decodeFromJsonElement<SloReconciledEvent>(envelope.data)
                sloRepo.upsertSloConfig(event, tenantId)
            }
            "notification_sent" -> {
                val event = json.decodeFromJsonElement<NotificationSentEvent>(envelope.data)
                scorecardRepo.insertNotificationLog(event, tenantId)
            }
            "resource_metrics" -> {
                val event = json.decodeFromJsonElement<ResourceMetricsEvent>(envelope.data)
                metricsRepo.insertResourceMetrics(event, tenantId)
            }
            else -> logger.warn("Unknown event type: ${envelope.t}")
        }
    }
}
