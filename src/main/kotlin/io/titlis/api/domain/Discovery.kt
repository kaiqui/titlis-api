package io.titlis.api.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Payload enviado pelo titlis-operator-go em POST /v1/operator/discovery/assets.
// Espelha discovery.AssetGraphSnapshot (Go). camelCase para casar com a serialização do operator.

@Serializable
data class AssetGraphRequest(
    val v: Int = 1,
    val cluster: String,
    val assets: List<DiscoveredAssetDTO> = emptyList(),
    val relations: List<AssetRelationDTO> = emptyList(),
    val syncStatus: Map<String, ProviderStatusDTO> = emptyMap(),
)

@Serializable
data class DiscoveredAssetDTO(
    val externalId: String,
    val provider: String,
    val kind: String,
    val name: String,
    val namespace: String? = null,
    val cluster: String? = null,
    val tags: Map<String, String> = emptyMap(),
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class AssetRelationDTO(
    val sourceExternalId: String,
    val sourceProvider: String,
    val targetExternalId: String,
    val targetProvider: String,
    val type: String,
)

@Serializable
data class ProviderStatusDTO(
    val status: String,
    val assetCount: Int = 0,
    val error: String? = null,
)

@Serializable
data class AssetGraphIngestResult(
    val assetsUpserted: Int,
    val relationsUpserted: Int,
    val assetsDeactivated: Int,
    val relationsDeactivated: Int,
)
