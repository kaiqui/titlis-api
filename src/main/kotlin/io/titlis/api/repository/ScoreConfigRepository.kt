package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.EngineRules
import io.titlis.api.database.tables.PillarWeightsConfig
import io.titlis.api.database.tables.ScoringEngines
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and

@Serializable
data class ScoreConfigRule(
    @SerialName("engine_id") val engineId: Int,
    @SerialName("rule_id") val ruleId: String,
    val pillar: String,
    val name: String,
    val severity: String,
    @SerialName("enabled_by_default") val enabledByDefault: Boolean,
)

@Serializable
data class PillarWeightRecord(
    @SerialName("engine_id") val engineId: Int,
    val pillar: String,
    val weight: Int,
)

class ScoreConfigRepository {

    // Lista regras de uma engine — usada pela UI via settings.
    suspend fun listRules(engineSlug: String): List<ScoreConfigRule> = dbQuery {
        EngineRules
            .join(ScoringEngines, JoinType.INNER, EngineRules.engineId, ScoringEngines.id)
            .select(EngineRules.engineId, EngineRules.ruleId, EngineRules.pillar, EngineRules.name, EngineRules.severity, EngineRules.enabledByDefault)
            .where { ScoringEngines.slug eq engineSlug }
            .orderBy(EngineRules.pillar to SortOrder.ASC, EngineRules.ruleId to SortOrder.ASC)
            .map {
                ScoreConfigRule(
                    engineId = it[EngineRules.engineId],
                    ruleId = it[EngineRules.ruleId],
                    pillar = it[EngineRules.pillar],
                    name = it[EngineRules.name],
                    severity = it[EngineRules.severity],
                    enabledByDefault = it[EngineRules.enabledByDefault],
                )
            }
    }

    // Retorna pesos dos pilares para o tenant/engine — usada pela UI via settings.
    suspend fun getPillarWeights(tenantId: Long, engineSlug: String): List<PillarWeightRecord> =
        dbQuery {
            PillarWeightsConfig
                .join(ScoringEngines, JoinType.INNER, PillarWeightsConfig.engineId, ScoringEngines.id)
                .select(PillarWeightsConfig.engineId, PillarWeightsConfig.pillar, PillarWeightsConfig.weight)
                .where {
                    (PillarWeightsConfig.tenantId eq tenantId.toInt()) and
                    (ScoringEngines.slug eq engineSlug)
                }
                .map { PillarWeightRecord(
                    engineId = it[PillarWeightsConfig.engineId],
                    pillar = it[PillarWeightsConfig.pillar],
                    weight = it[PillarWeightsConfig.weight].toInt(),
                ) }
        }
}
