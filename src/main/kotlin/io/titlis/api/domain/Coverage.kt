package io.titlis.api.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Contrato com o engine "coverage" do titlis-scoreops.
// Request (camelCase = json tags do Go); response (snake_case via @SerialName).

@Serializable
data class CoverageNatureDTO(
    val language: String = "",
    val httpFacing: Boolean = false,
    val stateful: Boolean = false,
    val scheduled: Boolean = false,
    val criticality: String = "standard",
    val hasQueueDep: Boolean = false,
)

@Serializable
data class CoverageFoundDTO(
    val hasSlo: Boolean = false,
    val sloHealthy: Boolean = false,
    val hasMonitor: Boolean = false,
    val monitorCount: Int = 0,
    val hasTracing: Boolean = false,
    val hasLogs: Boolean = false,
    val metricCategories: List<String> = emptyList(),
    val cpuRequestSet: Boolean = false,
    val cpuLimitSet: Boolean = false,
    val memoryRequestSet: Boolean = false,
    val memoryLimitSet: Boolean = false,
    val hasProbes: Boolean = false,
    val hasHpa: Boolean = false,
    val hasPdb: Boolean = false,
    val hasNetworkPolicy: Boolean = false,
)

@Serializable
data class CoverageSnapshotDTO(
    val tenantId: Long,
    val workloadUid: String,
    val serviceName: String,
    val namespace: String,
    val cluster: String,
    val nature: CoverageNatureDTO,
    val found: CoverageFoundDTO,
    // Capacidades de observabilidade realmente mensuráveis para este serviço (monitor, tracing,
    // http_metrics, jvm_metrics, logs). Templates sem a capacidade viram N/A, nunca falso vermelho.
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class CoverageFindingDTO(
    val code: String = "",
    val pillar: String = "",
    val severity: String = "",
    val weight: Double = 0.0,
    @SerialName("is_remediable") val isRemediable: Boolean = false,
    val outcome: String = "",
    val message: String = "",
)

@Serializable
data class CoverageDimensionDTO(
    val pillar: String = "",
    val evaluable: Int = 0,
    val passed: Int = 0,
    val na: Int = 0,
    val pct: Double = 0.0,
    @SerialName("maturity_level") val maturityLevel: Int = 0,
)

@Serializable
data class CoverageResultDTO(
    @SerialName("workload_uid") val workloadUid: String = "",
    @SerialName("service_name") val serviceName: String = "",
    val namespace: String = "",
    val cluster: String = "",
    @SerialName("tenant_id") val tenantId: Long = 0,
    @SerialName("engine_slug") val engineSlug: String = "",
    @SerialName("trust_score") val trustScore: Double = 0.0,
    val maturity: Int = 0,
    val findings: List<CoverageFindingDTO> = emptyList(),
    val dimensions: List<CoverageDimensionDTO> = emptyList(),
    @SerialName("evaluated_at") val evaluatedAt: String = "",
)

// Read model da UI (GET /v1/coverage).
@Serializable
data class CoverageScorecardDTO(
    val workloadUid: String,
    val serviceName: String?,
    val cluster: String?,
    val trustScore: Double?,
    val maturity: Int,
    val dimensions: List<CoverageDimensionDTO>,
    val findings: List<CoverageFindingDTO>,
    val evaluatedAt: String,
)
