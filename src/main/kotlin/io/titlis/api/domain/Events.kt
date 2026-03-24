package io.titlis.api.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class UdpEnvelope(
    val v: Int,
    val t: String,
    val ts: Long,
    val data: JsonObject,
)

@Serializable
data class ScorecardEvaluatedEvent(
    @SerialName("workload_id") val workloadId: String,
    val namespace: String,
    val workload: String,
    val cluster: String,
    @SerialName("k8s_event_type") val k8sEventType: String,
    @SerialName("overall_score") val overallScore: Double,
    @SerialName("compliance_status") val complianceStatus: String,
    @SerialName("total_rules") val totalRules: Int,
    @SerialName("passed_rules") val passedRules: Int,
    @SerialName("failed_rules") val failedRules: Int,
    @SerialName("critical_failures") val criticalFailures: Int,
    @SerialName("error_count") val errorCount: Int,
    @SerialName("warning_count") val warningCount: Int,
    @SerialName("scorecard_version") val scorecardVersion: Int,
    @SerialName("pillar_scores") val pillarScores: List<PillarScoreData>,
    @SerialName("evaluated_at") val evaluatedAt: String,
)

@Serializable
data class PillarScoreData(
    val pillar: String,
    val score: Double,
    @SerialName("passed_checks") val passedChecks: Int,
    @SerialName("failed_checks") val failedChecks: Int,
    @SerialName("weighted_score") val weightedScore: Double? = null,
)

@Serializable
data class RemediationEvent(
    @SerialName("workload_id") val workloadId: String,
    val namespace: String,
    val workload: String,
    val status: String,
    @SerialName("previous_status") val previousStatus: String? = null,
    val version: Int,
    @SerialName("github_pr_number") val githubPrNumber: Int? = null,
    @SerialName("github_pr_url") val githubPrUrl: String? = null,
    @SerialName("github_branch") val githubBranch: String? = null,
    @SerialName("repository_url") val repositoryUrl: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("triggered_at") val triggeredAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
)

@Serializable
data class SloReconciledEvent(
    @SerialName("slo_config_id") val sloConfigId: String,
    val namespace: String,
    @SerialName("slo_name") val sloName: String,
    @SerialName("slo_type") val sloType: String,
    val timeframe: String,
    val target: Double,
    val warning: Double? = null,
    @SerialName("datadog_slo_id") val datadogSloId: String? = null,
    @SerialName("datadog_slo_state") val datadogSloState: String? = null,
    @SerialName("sync_action") val syncAction: String,
    @SerialName("sync_error") val syncError: String? = null,
    @SerialName("actual_value") val actualValue: Double? = null,
    // Framework detection — persiste status.detected_framework do SLOController (H-13)
    @SerialName("auto_detect_framework") val autoDetectFramework: Boolean = false,
    @SerialName("detected_framework") val detectedFramework: String? = null,   // WSGI | FASTAPI | AIOHTTP
    @SerialName("detection_source") val detectionSource: String? = null,       // annotation | datadog_tag | fallback
    @SerialName("k8s_resource_uid") val k8sResourceUid: String? = null,        // para tag titlis_resource_uid
)

@Serializable
data class NotificationSentEvent(
    @SerialName("workload_id") val workloadId: String? = null,
    @SerialName("namespace_id") val namespaceId: String? = null,
    val namespace: String,
    @SerialName("notification_type") val notificationType: String,
    val severity: String,
    val channel: String? = null,
    val title: String? = null,
    @SerialName("message_preview") val messagePreview: String? = null,
    val success: Boolean,
    @SerialName("error_message") val errorMessage: String? = null,
)

@Serializable
data class ResourceMetricsEvent(
    @SerialName("workload_id") val workloadId: String,
    val namespace: String,
    val workload: String,
    @SerialName("container_name") val containerName: String? = null,
    @SerialName("cpu_avg_millicores") val cpuAvgMillicores: Double? = null,
    @SerialName("cpu_p95_millicores") val cpuP95Millicores: Double? = null,
    @SerialName("mem_avg_mib") val memAvgMib: Double? = null,
    @SerialName("mem_p95_mib") val memP95Mib: Double? = null,
    @SerialName("suggested_cpu_request") val suggestedCpuRequest: String? = null,
    @SerialName("suggested_cpu_limit") val suggestedCpuLimit: String? = null,
    @SerialName("suggested_mem_request") val suggestedMemRequest: String? = null,
    @SerialName("suggested_mem_limit") val suggestedMemLimit: String? = null,
    @SerialName("sample_window") val sampleWindow: String? = null,
)