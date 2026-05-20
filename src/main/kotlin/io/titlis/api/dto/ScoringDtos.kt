package io.titlis.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkloadSnapshotDTO(
    val uid: String = "",
    val name: String = "",
    val namespace: String = "",
    val cluster: String = "",
    val kind: String = "",
    val criticality: String = "standard",
    val labels: Map<String, String> = emptyMap(),
    @SerialName("tenant_id") val tenantId: Long = 0,
    @SerialName("engine_slug") val engineSlug: String = "",
    @SerialName("has_liveness_probe") val hasLivenessProbe: Boolean = false,
    @SerialName("has_readiness_probe") val hasReadinessProbe: Boolean = false,
    @SerialName("cpu_request_set") val cpuRequestSet: Boolean = false,
    @SerialName("cpu_limit_set") val cpuLimitSet: Boolean = false,
    @SerialName("memory_request_set") val memoryRequestSet: Boolean = false,
    @SerialName("memory_limit_set") val memoryLimitSet: Boolean = false,
    @SerialName("cpu_limit_ratio") val cpuLimitRatio: Double = 0.0,
    @SerialName("image_tag") val imageTag: String = "",
    @SerialName("read_only_root_fs") val readOnlyRootFs: Boolean = false,
    @SerialName("run_as_non_root") val runAsNonRoot: Boolean = false,
    @SerialName("allow_privilege_escalation") val allowPrivilegeEscalation: Boolean = false,
    @SerialName("has_drop_capabilities") val hasDropCapabilities: Boolean = false,
    @SerialName("has_pod_security_context") val hasPodSecurityContext: Boolean = false,
    val replicas: Int = 0,
    val strategy: String = "",
    @SerialName("termination_grace_period_sec") val terminationGracePeriodSec: Long = 0,
    @SerialName("has_network_policy") val hasNetworkPolicy: Boolean = false,
    @SerialName("has_hpa") val hasHpa: Boolean = false,
    @SerialName("hpa_has_metrics") val hpaHasMetrics: Boolean = false,
    @SerialName("hpa_min_replicas") val hpaMinReplicas: Int = 0,
    @SerialName("hpa_cpu_target_percent") val hpaCpuTargetPercent: Int = 0,
    @SerialName("hpa_scale_up_stabilization_sec") val hpaScaleUpStabilizationSec: Int = -1,
    @SerialName("hpa_scale_down_stabilization_sec") val hpaScaleDownStabilizationSec: Int = -1,
    @SerialName("hpa_has_behavior_policies") val hpaHasBehaviorPolicies: Boolean = false,
    @SerialName("cluster_tags") val clusterTags: List<String> = emptyList(),
    @SerialName("namespace_tags") val namespaceTags: List<String> = emptyList(),
    val environment: String = "",
) {
    fun withTenant(tenantId: Long) = copy(tenantId = tenantId)

    fun withTags(clusterTags: List<String>, namespaceTags: List<String>, environment: String) =
        copy(clusterTags = clusterTags, namespaceTags = namespaceTags, environment = environment)
}

@Serializable
data class ScoreRuleResultDTO(
    @SerialName("rule_id") val ruleId: String = "",
    @SerialName("rule_name") val ruleName: String = "",
    val passed: Boolean = false,
    val severity: String = "",
    val weight: Double = 0.0,
    val message: String = "",
    @SerialName("actual_value") val actualValue: String = "",
    @SerialName("is_remediable") val isRemediable: Boolean = false,
)

@Serializable
data class ScorePillarScoreDTO(
    val pillar: String = "",
    val score: Double = 0.0,
    @SerialName("passed_checks") val passedChecks: Int = 0,
    @SerialName("total_checks") val totalChecks: Int = 0,
    @SerialName("weighted_score") val weightedScore: Double = 0.0,
    val weight: Double = 0.0,
)

@Serializable
data class ScoreResultDTO(
    @SerialName("workload_uid") val workloadUid: String = "",
    @SerialName("workload_name") val workloadName: String = "",
    val namespace: String = "",
    val cluster: String = "",
    @SerialName("tenant_id") val tenantId: Long = 0,
    @SerialName("engine_slug") val engineSlug: String = "",
    @SerialName("overall_score") val overallScore: Double = 0.0,
    @SerialName("compliance_status") val complianceStatus: String = "",
    @SerialName("critical_issues") val criticalIssues: Int = 0,
    @SerialName("error_issues") val errorIssues: Int = 0,
    @SerialName("warning_issues") val warningIssues: Int = 0,
    @SerialName("passed_checks") val passedChecks: Int = 0,
    @SerialName("total_checks") val totalChecks: Int = 0,
    @SerialName("rules_hash") val rulesHash: String = "",
    @SerialName("pillar_scores") val pillarScores: List<ScorePillarScoreDTO> = emptyList(),
    val findings: List<ScoreRuleResultDTO> = emptyList(),
    @SerialName("calculated_at") val calculatedAt: String = "",
)
