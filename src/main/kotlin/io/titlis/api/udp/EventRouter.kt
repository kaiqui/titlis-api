package io.titlis.api.udp

import io.titlis.api.domain.*
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.CampaignRepository
import io.titlis.api.repository.MetricsRepository
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.ServiceDefinitionEvent
import io.titlis.api.repository.ServiceDefinitionRepository
import io.titlis.api.repository.SloRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
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
    private val campaignRepo: CampaignRepository,
    private val serviceDefRepo: ServiceDefinitionRepository = ServiceDefinitionRepository(),
) {
    private val logger = LoggerFactory.getLogger(EventRouter::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun routeHttp(envelope: UdpEnvelope, tenantId: Long, rawApiKey: String) {
        scope.launch(Dispatchers.IO) {
            apiKeyRepo.updateLastUsedAtAsync(rawApiKey)
        }
        scope.launch(Dispatchers.IO) {
            dispatchEvent(envelope, tenantId)
        }
    }

    // routeFromPrbot dispatches an event sent via HTTP from titlis-prbot (X-Internal-Secret auth).
    // Unlike routeHttp, it does not require an operator API key.
    suspend fun routeFromPrbot(envelope: UdpEnvelope, tenantId: Long) {
        dispatchEvent(envelope, tenantId)
    }

    private suspend fun dispatchEvent(envelope: UdpEnvelope, tenantId: Long) {
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
            "namespace_exclusions_sync" -> {
                val event = json.decodeFromJsonElement<NamespaceExclusionsSyncEvent>(envelope.data)
                scorecardRepo.syncNamespaceExclusions(event, tenantId)
            }
            "rule_failed" -> {
                val event = json.decodeFromJsonElement<RuleFailedEvent>(envelope.data)
                logger.info("Rule failed event: rule=${event.ruleId} workload=${event.workloadId} tenant=$tenantId")
                // prbot picks up via GET /v1/internal/prbot/findings; no-op here for now
            }
            "campaign_started" -> {
                val event = json.decodeFromJsonElement<CampaignStartedEvent>(envelope.data)
                try {
                    campaignRepo.insert(
                        id             = event.campaignId,
                        tenantId       = tenantId,
                        workflowId     = event.workflowId,
                        actorEmail     = event.actorEmail,
                        triggerSource  = event.triggerSource,
                        ruleId         = event.ruleId,
                        title          = event.title,
                        description    = event.description,
                        status         = "RUNNING",
                        idempotencyKey = event.campaignId,
                        totalItems     = event.totalItems,
                    )
                } catch (e: Exception) {
                    logger.warn("campaign_started: insert skipped (may already exist): ${e.message}")
                }
            }
            "campaign_completed" -> {
                val event = json.decodeFromJsonElement<CampaignCompletedEvent>(envelope.data)
                campaignRepo.updateStatus(
                    id = event.campaignId,
                    tenantId = tenantId,
                    status = event.status,
                    succeededItems = event.succeededItems,
                    failedItems = event.failedItems,
                    skippedItems = event.skippedItems,
                )
                campaignRepo.appendEvent(event.campaignId, tenantId, "campaign_completed", json.encodeToString(event))
            }
            "campaign_item_completed" -> {
                val event = json.decodeFromJsonElement<CampaignItemCompletedEvent>(envelope.data)
                campaignRepo.incrementItem(event.campaignId, tenantId, event.status)
                val campaign = campaignRepo.findById(event.campaignId, tenantId)
                if (campaign != null) {
                    val processed = campaign.succeededItems + campaign.failedItems + campaign.skippedItems
                    if (campaign.totalItems > 0 && processed >= campaign.totalItems) {
                        campaignRepo.updateStatus(
                            id = campaign.id,
                            tenantId = tenantId,
                            status = if (campaign.failedItems > 0 && campaign.succeededItems == 0) "FAILED" else "COMPLETED",
                        )
                    }
                }
            }
            "discovery_completed" -> {
                val event = json.decodeFromJsonElement<DiscoveryCompletedEvent>(envelope.data)
                logger.info("Discovery completed: rule=${event.ruleId} findings=${event.totalFindings} campaigns=${event.campaignsStarted} tenant=$tenantId")
            }
            "finding_opened" -> {
                val event = json.decodeFromJsonElement<FindingOpenedEvent>(envelope.data)
                logger.info("Finding opened: rule=${event.ruleId} workload=${event.workloadId} tenant=$tenantId")
            }
            "service_definition_synced" -> {
                val event = json.decodeFromJsonElement<ServiceDefinitionSyncedEvent>(envelope.data)
                serviceDefRepo.upsert(
                    tenantId = tenantId,
                    event = ServiceDefinitionEvent(
                        serviceName  = event.serviceName,
                        team         = event.team,
                        product      = event.product,
                        tier         = event.tier,
                        description  = event.description,
                        repoUrl      = event.repoUrl,
                        workloads    = event.workloads,
                        rawYaml      = event.rawYaml,
                        integrations = event.integrations,
                    ),
                )
                logger.info("service_definition_synced: service=${event.serviceName} team=${event.team} tenant=$tenantId")
            }
            else -> logger.warn("Unknown event type: ${envelope.t}")
        }
    }
}
