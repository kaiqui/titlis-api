package io.titlis.api.domain

import kotlinx.serialization.Serializable

// H1 — service-map hierárquico do hub: produto → squad → serviço → workload (+ bucket de órfãos).
// Estrutura vem do GitHub (.titlis/service.yaml → service_definitions) + Discovery; score = Coverage.

@Serializable
data class ServiceMapWorkload(
    val workloadUid: String,
    val name: String,
    val cluster: String?,
    val score: Double?, // Coverage trustScore (vira overall 0–100 quando U1 entregar)
    val maturity: Int,
)

@Serializable
data class ServiceMapService(
    val serviceDefinitionId: Long,
    val serviceName: String,
    val repoUrl: String?,
    val score: Double?, // média dos workloads
    val workloads: List<ServiceMapWorkload>,
)

@Serializable
data class ServiceMapSquad(
    val team: String,
    val score: Double?,
    val services: List<ServiceMapService>,
)

@Serializable
data class ServiceMapProduct(
    val product: String,
    val score: Double?,
    val squads: List<ServiceMapSquad>,
)

// orphans = workloads descobertos sem service_definition (sem .titlis/service.yaml) → driver de adoção.
@Serializable
data class ServiceMapDTO(
    val products: List<ServiceMapProduct>,
    val orphans: List<ServiceMapWorkload>,
)
