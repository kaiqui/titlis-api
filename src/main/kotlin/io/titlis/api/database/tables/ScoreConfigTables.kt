package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.postgresql.util.PGobject

private class PgEnumAsStringColumnType(private val pgTypeName: String) : VarCharColumnType(64) {
    override fun sqlType() = pgTypeName
    override fun notNullValueToDB(value: String) =
        PGobject().also { it.type = pgTypeName; it.value = value }
    override fun valueFromDB(value: Any) = when (value) {
        is PGobject -> value.value ?: ""
        is String   -> value
        else        -> value.toString()
    }
}

// Tabelas read-only: a titlis-api lê, mas não cria nem altera.
// O titlis-scoreops é dono desse schema e gerencia as migrations.

object ScoringEngines : Table("titlis_config.scoring_engines") {
    val id          = integer("id")
    val slug        = varchar("slug", 64)
    val name        = varchar("name", 128)
    val enabled     = bool("enabled")
    val createdAt   = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object EngineRules : Table("titlis_config.engine_rules") {
    val id               = integer("id")
    val engineId         = integer("engine_id")
    val ruleId           = varchar("rule_id", 128)
    val pillar           = varchar("pillar", 64)
    val name             = varchar("name", 256)
    val severity         = varchar("severity", 32)
    val enabledByDefault = bool("enabled_by_default")
    override val primaryKey = PrimaryKey(id)
}

object RuleOverrides : Table("titlis_config.rule_overrides") {
    val id          = long("id")
    val tenantId    = integer("tenant_id")
    val engineId    = integer("engine_id")
    val ruleId      = varchar("rule_id", 128)
    val scope       = registerColumn<String>("scope", PgEnumAsStringColumnType("titlis_config.scope_type"))
    val clusterName = varchar("cluster_name", 256).nullable()
    val namespace   = varchar("namespace", 256).nullable()
    val workloadUid = varchar("workload_uid", 256).nullable()
    val enabled     = bool("enabled")
    val reason      = text("reason").nullable()
    val createdBy   = varchar("created_by", 256)
    val createdAt   = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PillarWeightsConfig : Table("titlis_config.pillar_weights") {
    val tenantId  = integer("tenant_id")
    val engineId  = integer("engine_id")
    val pillar    = varchar("pillar", 64)
    val weight    = short("weight")
    val updatedBy = varchar("updated_by", 256)
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantId, engineId, pillar)
}
