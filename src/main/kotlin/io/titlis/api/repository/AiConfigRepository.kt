package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.TenantAiConfigs
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class TenantAiConfigRecord(
    val tenantId: Long,
    val provider: String,
    val model: String,
    val apiKeyEnc: String,
    val githubTokenEnc: String?,
    val githubBaseBranch: String,
    val monthlyTokenBudget: Int?,
    val tokensUsedMonth: Int,
    val isActive: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

val SUPPORTED_PROVIDERS = setOf("openai", "anthropic", "google", "gemini", "mistral", "cohere", "azure", "ollama")

class AiConfigRepository {

    suspend fun getByTenant(tenantId: Long): TenantAiConfigRecord? = dbQuery {
        TenantAiConfigs
            .select(TenantAiConfigs.columns)
            .where { TenantAiConfigs.tenantId eq tenantId }
            .singleOrNull()
            ?.let { mapRow(it) }
    }

    suspend fun upsert(
        tenantId: Long,
        provider: String,
        model: String,
        apiKeyEnc: String,
        githubTokenEnc: String?,
        githubBaseBranch: String,
        monthlyTokenBudget: Int?,
    ): TenantAiConfigRecord = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        TenantAiConfigs.upsert(
            TenantAiConfigs.tenantId,
            onUpdateExclude = listOf(TenantAiConfigs.createdAt, TenantAiConfigs.tokensUsedMonth),
        ) {
            it[TenantAiConfigs.tenantId]           = tenantId
            it[TenantAiConfigs.provider]            = provider
            it[TenantAiConfigs.model]               = model
            it[TenantAiConfigs.apiKeyEnc]           = apiKeyEnc
            it[TenantAiConfigs.githubTokenEnc]      = githubTokenEnc
            it[TenantAiConfigs.githubBaseBranch]    = githubBaseBranch
            it[TenantAiConfigs.monthlyTokenBudget]  = monthlyTokenBudget
            it[TenantAiConfigs.isActive]            = true
            it[TenantAiConfigs.createdAt]           = now
            it[TenantAiConfigs.updatedAt]           = now
        }

        TenantAiConfigs
            .select(TenantAiConfigs.columns)
            .where { TenantAiConfigs.tenantId eq tenantId }
            .single()
            .let { mapRow(it) }
    }

    suspend fun incrementTokensUsed(tenantId: Long, delta: Int) = dbQuery {
        TenantAiConfigs.update({ TenantAiConfigs.tenantId eq tenantId }) {
            with(SqlExpressionBuilder) {
                it[tokensUsedMonth] = tokensUsedMonth + delta
            }
            it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    private fun mapRow(row: ResultRow) = TenantAiConfigRecord(
        tenantId          = row[TenantAiConfigs.tenantId],
        provider          = row[TenantAiConfigs.provider],
        model             = row[TenantAiConfigs.model],
        apiKeyEnc         = row[TenantAiConfigs.apiKeyEnc],
        githubTokenEnc    = row[TenantAiConfigs.githubTokenEnc],
        githubBaseBranch  = row[TenantAiConfigs.githubBaseBranch],
        monthlyTokenBudget = row[TenantAiConfigs.monthlyTokenBudget],
        tokensUsedMonth   = row[TenantAiConfigs.tokensUsedMonth],
        isActive          = row[TenantAiConfigs.isActive],
        createdAt         = row[TenantAiConfigs.createdAt],
        updatedAt         = row[TenantAiConfigs.updatedAt],
    )
}
