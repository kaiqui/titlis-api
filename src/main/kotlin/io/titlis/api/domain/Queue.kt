package io.titlis.api.domain

import kotlinx.serialization.Serializable

@Serializable
data class QueueObservationRequest(
    val provider: String = "gcp_pubsub",
    val externalId: String,
    val displayName: String,
    val projectId: String? = null,
    val topicId: String? = null,
    val isDlq: Boolean = false,
    val numUndeliveredMessages: Long = 0,
    val oldestUnackedAgeSec: Long = 0,
    val pullMessageCountRate: Double = 0.0,
    val sendMessageCountRate: Double = 0.0,
    val ackMessageCountRate: Double = 0.0,
    val deadLetterMessageCount: Long = 0,
    val hasDlqConfigured: Boolean = false,
    val hasSnapshotPolicy: Boolean = false,
    val hasMonitorBacklog: Boolean = false,
    val hasMonitorAge: Boolean = false,
    val hasMonitorDlq: Boolean = false,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
data class QueueObserveResponse(
    val queueId: Long,
    val lifecycleState: String,
    val observationCount: Int,
)

@Serializable
data class QueueBatchObserveResponseItem(
    val externalId: String,
    val queueId: Long,
    val lifecycleState: String,
    val observationCount: Int,
    val learningTarget: Int = 7,
    val thresholds: QueueThresholdsDTO? = null,
)

@Serializable
data class QueueLifecycleDTO(
    val state: String,
    val observationCount: Int,
    val learningTarget: Int = 7,
    val thresholds: QueueThresholdsDTO? = null,
)

@Serializable
data class QueueThresholdsDTO(
    val backlogWarning: Long,
    val backlogCritical: Long,
    val ageWarningSec: Long,
    val ageCriticalSec: Long,
    val p50Backlog: Long? = null,
    val p75Backlog: Long? = null,
    val p95Backlog: Long? = null,
    val p50AgeSec: Long? = null,
    val p75AgeSec: Long? = null,
    val p95AgeSec: Long? = null,
    val calculatedAt: String? = null,
    val observationCount: Int? = null,
)

@Serializable
data class QueueEvaluateRequest(
    val provider: String,
    val externalId: String,
    val displayName: String,
    val isDlq: Boolean = false,
    val numUndeliveredMessages: Long = 0,
    val oldestUnackedAgeSec: Long = 0,
    val pullMessageCountRate: Double = 0.0,
    val sendMessageCountRate: Double = 0.0,
    val ackMessageCountRate: Double = 0.0,
    val deadLetterMessageCount: Long = 0,
    val hasDlqConfigured: Boolean = false,
    val hasSnapshotPolicy: Boolean = false,
    val hasMonitorBacklog: Boolean = false,
    val hasMonitorAge: Boolean = false,
    val hasMonitorDlq: Boolean = false,
    val labels: Map<String, String> = emptyMap(),
    val thresholds: QueueThresholdsDTO,
    val labelRegistry: List<LabelKeyValuesDTO> = emptyList(),
    val tenantId: Long,
)

@Serializable
data class QueueEvaluatedEvent(
    val tenantId: Long,
    val provider: String,
    val externalId: String,
    val overallScore: Double,
    val complianceStatus: String,
    val totalRules: Int,
    val passedRules: Int,
    val failedRules: Int,
    val criticalFailures: Int,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val evaluatedAt: String? = null,
    val pillarScores: List<QueuePillarScoreDTO> = emptyList(),
    val validationResults: List<QueueValidationResultDTO> = emptyList(),
    val rawMetadata: String? = null,
)

@Serializable
data class QueuePillarScoreDTO(
    val pillar: String,
    val pillarScore: Double,
    val passedChecks: Int,
    val failedChecks: Int,
    val weightedScore: Double? = null,
)

@Serializable
data class QueueValidationResultDTO(
    val ruleId: String,
    val ruleName: String? = null,
    val pillar: String? = null,
    val severity: String? = null,
    val rulePassed: Boolean,
    val resultMessage: String? = null,
    val actualValue: String? = null,
)

@Serializable
data class QueueSummaryDTO(
    val queueId: Long,
    val externalId: String,
    val displayName: String,
    val provider: String,
    val isDlq: Boolean,
    val lifecycleState: String,
    val observationCount: Int,
    val overallScore: Double? = null,
    val complianceStatus: String? = null,
    val firstSeenAt: String,
    val lastSeenAt: String,
    val serviceDefinitionId: Long? = null,
    val serviceName: String? = null,
    val team: String? = null,
    val linkSource: String? = null,
    val suggestionCount: Int = 0,
)

@Serializable
data class QueueLinkSuggestionDTO(
    val serviceDefinitionId: Long,
    val serviceName: String,
    val team: String? = null,
    val confidence: Double,
    val source: String,
)

@Serializable
data class LinkQueueRequest(
    val serviceDefinitionId: Long,
)

@Serializable
data class ServiceOptionDTO(
    val serviceDefinitionId: Long,
    val serviceName: String,
    val team: String? = null,
)

@Serializable
data class QueueNameDTO(
    val externalId: String,
    val displayName: String,
)

@Serializable
data class QueueLinkHint(
    val externalId: String? = null,
    val displayName: String? = null,
    val workloadUid: String? = null,
    val workloadName: String,
    val namespace: String? = null,
)

@Serializable
data class QueueDetailDTO(
    val queueId: Long,
    val externalId: String,
    val displayName: String,
    val provider: String,
    val isDlq: Boolean,
    val lifecycleState: String,
    val observationCount: Int,
    val overallScore: Double? = null,
    val complianceStatus: String? = null,
    val totalRules: Int? = null,
    val passedRules: Int? = null,
    val failedRules: Int? = null,
    val criticalFailures: Int? = null,
    val errorCount: Int? = null,
    val warningCount: Int? = null,
    val evaluatedAt: String? = null,
    val pillarScores: List<QueuePillarScoreDTO> = emptyList(),
    val validationResults: List<QueueValidationResultDTO> = emptyList(),
    val firstSeenAt: String,
    val lastSeenAt: String,
    val serviceDefinitionId: Long? = null,
    val serviceName: String? = null,
    val team: String? = null,
    val linkSource: String? = null,
    val suggestions: List<QueueLinkSuggestionDTO> = emptyList(),
)

@Serializable
data class LabelRegistryEntryDTO(
    val labelRegistryId: Long? = null,
    val labelKey: String,
    val labelValue: String,
    val isActive: Boolean = true,
)

@Serializable
data class LabelRegistryDTO(
    val labels: List<LabelGroupDTO>,
)

@Serializable
data class LabelGroupDTO(
    val key: String,
    val values: List<LabelRegistryEntryDTO>,
)

@Serializable
data class AddLabelValueRequest(
    val labelKey: String,
    val labelValue: String,
)

@Serializable
data class DatadogSettingsDTO(
    val hasApiKey: Boolean,
    val hasAppKey: Boolean,
    val site: String,
    val queueMonitoringEnabled: Boolean,
    val monitorCreationEnabled: Boolean,
    val queueCounts: QueueCountsDTO = QueueCountsDTO(),
)

@Serializable
data class QueueCountsDTO(
    val discovering: Int = 0,
    val learning: Int = 0,
    val monitoring: Int = 0,
)

@Serializable
data class SaveDatadogSettingsRequest(
    val ddApiKey: String? = null,
    val ddAppKey: String? = null,
    val ddSite: String = "datadoghq.com",
    val queueMonitoringEnabled: Boolean,
    val monitorCreationEnabled: Boolean = false,
)

@Serializable
data class QueueConfigDTO(
    val enabled: Boolean,
    val monitorCreationEnabled: Boolean = false,
    val providers: List<String> = listOf("gcp_pubsub"),
    val learningCycles: Int = 7,
)

@Serializable
data class DatadogConfigDTO(
    val ddApiKey: String,
    val ddAppKey: String,
    val ddSite: String,
)

@Serializable
data class LabelKeyValuesDTO(
    val key: String,
    val values: List<String>,
)

@Serializable
data class LabelRegistryOperatorDTO(
    val labels: List<LabelKeyValuesDTO>,
)
