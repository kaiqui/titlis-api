package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

// Grafo de ativos descobertos pelo Discovery Engine do titlis-operator-go.
// Espelha a migration V24__discovery_assets.sql. Nomenclatura: regra DBA 16.

object DiscoveredAssets : Table("titlis_oltp.discovered_asset") {
    val discoveredAssetId = long("discovered_asset_id").autoIncrement()
    val tenantId          = long("tenant_id").references(Tenants.tenantId)
    val provider          = text("provider")
    val kind              = text("kind")
    val externalId        = text("external_id")
    val name              = text("name")
    val namespace         = text("namespace").nullable()
    val clusterName       = text("cluster_name").nullable()
    val tags              = jsonbText("tags")
    val attributes        = jsonbText("attributes")
    val isActive          = bool("is_active").default(true)
    val firstSeenAt       = timestampWithTimeZone("first_seen_at")
    val lastSeenAt        = timestampWithTimeZone("last_seen_at")
    override val primaryKey = PrimaryKey(discoveredAssetId)
}

object AssetRelations : Table("titlis_oltp.asset_relation") {
    val assetRelationId          = long("asset_relation_id").autoIncrement()
    val tenantId                 = long("tenant_id").references(Tenants.tenantId)
    val sourceDiscoveredAssetId  = long("source_discovered_asset_id").references(DiscoveredAssets.discoveredAssetId)
    val targetDiscoveredAssetId  = long("target_discovered_asset_id").references(DiscoveredAssets.discoveredAssetId)
    val relationType             = text("relation_type")
    val isActive                 = bool("is_active").default(true)
    val lastSeenAt               = timestampWithTimeZone("last_seen_at")
    override val primaryKey = PrimaryKey(assetRelationId)
}

// Resultado do engine "coverage" (scorecard personalizado por serviço). Espelha V25.
object CoverageScorecards : Table("titlis_oltp.coverage_scorecard") {
    val coverageScorecardId = long("coverage_scorecard_id").autoIncrement()
    val tenantId            = long("tenant_id").references(Tenants.tenantId)
    val workloadUid         = text("workload_uid")
    val serviceName         = text("service_name").nullable()
    val clusterName         = text("cluster_name").nullable()
    val trustScore          = decimal("trust_score", 5, 2).nullable()
    val coverageJson        = jsonbText("coverage_json")
    val findingsJson        = jsonbText("findings_json")
    val evaluatedAt         = timestampWithTimeZone("evaluated_at")
    override val primaryKey = PrimaryKey(coverageScorecardId)
}
