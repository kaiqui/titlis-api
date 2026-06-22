package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.AssetRelations
import io.titlis.api.database.tables.DiscoveredAssets
import io.titlis.api.domain.AssetGraphIngestResult
import io.titlis.api.domain.AssetGraphRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Persiste o grafo de ativos enviado pelo titlis-operator-go. Idempotente por chave natural
// (tenant, provider, external_id) e (tenant, source, target, type). Soft-delete por sweep
// (regra 13): ativos/relações não vistos no ciclo viram is_active=false — nunca DELETE.
class DiscoveryRepository {
    private val log = LoggerFactory.getLogger(DiscoveryRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ingestAssetGraph(tenantId: Long, req: AssetGraphRequest): AssetGraphIngestResult = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val idByKey = HashMap<String, Long>(req.assets.size)

        for (a in req.assets) {
            val existing = DiscoveredAssets
                .select(DiscoveredAssets.discoveredAssetId)
                .where {
                    (DiscoveredAssets.tenantId eq tenantId) and
                        (DiscoveredAssets.provider eq a.provider) and
                        (DiscoveredAssets.externalId eq a.externalId)
                }
                .singleOrNull()

            val id = if (existing == null) {
                DiscoveredAssets.insert {
                    it[DiscoveredAssets.tenantId] = tenantId
                    it[provider]    = a.provider
                    it[kind]        = a.kind
                    it[externalId]  = a.externalId
                    it[name]        = a.name
                    it[namespace]   = a.namespace
                    it[clusterName] = a.cluster ?: req.cluster
                    it[tags]        = json.encodeToString(a.tags)
                    it[attributes]  = a.attributes.toString()
                    it[isActive]    = true
                    it[firstSeenAt] = now
                    it[lastSeenAt]  = now
                }[DiscoveredAssets.discoveredAssetId]
            } else {
                val eid = existing[DiscoveredAssets.discoveredAssetId]
                DiscoveredAssets.update({ DiscoveredAssets.discoveredAssetId eq eid }) {
                    it[kind]        = a.kind
                    it[name]        = a.name
                    it[namespace]   = a.namespace
                    it[clusterName] = a.cluster ?: req.cluster
                    it[tags]        = json.encodeToString(a.tags)
                    it[attributes]  = a.attributes.toString()
                    it[isActive]    = true
                    it[lastSeenAt]  = now
                }
                eid
            }
            idByKey[a.provider + "/" + a.externalId] = id
        }

        var relationsUpserted = 0
        for (r in req.relations) {
            val src = idByKey[r.sourceProvider + "/" + r.sourceExternalId] ?: continue
            val tgt = idByKey[r.targetProvider + "/" + r.targetExternalId] ?: continue
            val existing = AssetRelations
                .select(AssetRelations.assetRelationId)
                .where {
                    (AssetRelations.tenantId eq tenantId) and
                        (AssetRelations.sourceDiscoveredAssetId eq src) and
                        (AssetRelations.targetDiscoveredAssetId eq tgt) and
                        (AssetRelations.relationType eq r.type)
                }
                .singleOrNull()

            if (existing == null) {
                AssetRelations.insert {
                    it[AssetRelations.tenantId] = tenantId
                    it[sourceDiscoveredAssetId] = src
                    it[targetDiscoveredAssetId] = tgt
                    it[relationType]            = r.type
                    it[isActive]                = true
                    it[lastSeenAt]              = now
                }
            } else {
                AssetRelations.update({ AssetRelations.assetRelationId eq existing[AssetRelations.assetRelationId] }) {
                    it[isActive]   = true
                    it[lastSeenAt] = now
                }
            }
            relationsUpserted++
        }

        // Soft-delete deste cluster: ativos não revistos neste sweep.
        val assetsDeactivated = DiscoveredAssets.update({
            (DiscoveredAssets.tenantId eq tenantId) and
                (DiscoveredAssets.clusterName eq req.cluster) and
                (DiscoveredAssets.isActive eq true) and
                (DiscoveredAssets.lastSeenAt less now)
        }) { it[isActive] = false }

        val clusterAssetIds = DiscoveredAssets
            .select(DiscoveredAssets.discoveredAssetId)
            .where { (DiscoveredAssets.tenantId eq tenantId) and (DiscoveredAssets.clusterName eq req.cluster) }
            .map { it[DiscoveredAssets.discoveredAssetId] }

        val relationsDeactivated = if (clusterAssetIds.isEmpty()) {
            0
        } else {
            AssetRelations.update({
                (AssetRelations.tenantId eq tenantId) and
                    (AssetRelations.isActive eq true) and
                    (AssetRelations.lastSeenAt less now) and
                    (AssetRelations.sourceDiscoveredAssetId inList clusterAssetIds)
            }) { it[isActive] = false }
        }

        AssetGraphIngestResult(
            assetsUpserted = req.assets.size,
            relationsUpserted = relationsUpserted,
            assetsDeactivated = assetsDeactivated,
            relationsDeactivated = relationsDeactivated,
        )
    }
}
