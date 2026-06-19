package io.titlis.api.domain

import kotlinx.serialization.Serializable

// Nó da árvore de confiabilidade. RI (0..100) é a temperatura; debt é a moeda aditiva que soma
// na árvore inteira. ri é null quando nada abaixo do nó foi avaliado ainda.
@Serializable
data class ReliabilityNodeDTO(
    val path: String,
    val kind: String, // estate | product | team | service
    val name: String,
    val ri: Double? = null,
    val debt: Double = 0.0,
    val weight: Double = 0.0,
    val coverage: Double = 0.0,
    val scoredLeaves: Int = 0,
    val totalLeaves: Int = 0,
    val criticalBreach: Boolean = false,
    val hasChildren: Boolean = false,
    val children: List<ReliabilityNodeDTO> = emptyList(),
)

// Finding de uma folha do serviço, com os pontos de confiabilidade recuperáveis ao corrigi-lo.
@Serializable
data class ReliabilityFindingDTO(
    val leafKind: String, // workload | queue
    val leafName: String,
    val workloadUid: String? = null, // k8s_uid — deep-link p/ remediação (null em fila)
    val ruleId: String,
    val pillar: String? = null,
    val severity: String? = null,
    val message: String? = null,
    val actualValue: String? = null,
    val debt: Double = 0.0,
    val riGainService: Double = 0.0,
    val remediable: Boolean = false,
)

// Ponto da série de tendência de RI (um por dia) para um nó da árvore.
@Serializable
data class ReliabilityTrendPointDTO(
    val date: String,
    val ri: Double,
)
