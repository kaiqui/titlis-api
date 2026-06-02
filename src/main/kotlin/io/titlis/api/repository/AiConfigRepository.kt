package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.TenantAiConfigs
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
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
    val githubAuthMode: String,
    val githubAppIdEnc: String?,
    val githubAppPrivKeyEnc: String?,
    val githubAppInstallIdEnc: String?,
    val monthlyTokenBudget: Int?,
    val tokensUsedMonth: Int,
    val isActive: Boolean,
    val ddApiKeyEnc: String?,
    val ddAppKeyEnc: String?,
    val ddSite: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class DDCredentials(
    val ddApiKey: String,
    val ddAppKey: String,
    val ddSite: String,
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
        githubAuthMode: String,
        githubAppIdEnc: String?,
        githubAppPrivKeyEnc: String?,
        githubAppInstallIdEnc: String?,
        monthlyTokenBudget: Int?,
    ): TenantAiConfigRecord = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        TenantAiConfigs.upsert(
            TenantAiConfigs.tenantId,
            onUpdateExclude = listOf(TenantAiConfigs.createdAt, TenantAiConfigs.tokensUsedMonth),
        ) {
            it[TenantAiConfigs.tenantId]                = tenantId
            it[TenantAiConfigs.provider]                = provider
            it[TenantAiConfigs.model]                   = model
            it[TenantAiConfigs.apiKeyEnc]               = apiKeyEnc
            it[TenantAiConfigs.githubTokenEnc]          = githubTokenEnc
            it[TenantAiConfigs.githubBaseBranch]        = githubBaseBranch
            it[TenantAiConfigs.githubAuthMode]          = githubAuthMode
            it[TenantAiConfigs.githubAppIdEnc]          = githubAppIdEnc
            it[TenantAiConfigs.githubAppPrivKeyEnc]     = githubAppPrivKeyEnc
            it[TenantAiConfigs.githubAppInstallIdEnc]   = githubAppInstallIdEnc
            it[TenantAiConfigs.monthlyTokenBudget]      = monthlyTokenBudget
            it[TenantAiConfigs.isActive]                = true
            it[TenantAiConfigs.createdAt]               = now
            it[TenantAiConfigs.updatedAt]               = now
        }

        TenantAiConfigs
            .select(TenantAiConfigs.columns)
            .where { TenantAiConfigs.tenantId eq tenantId }
            .single()
            .let { mapRow(it) }
    }

    suspend fun upsertDatadogCreds(
        tenantId: Long,
        ddApiKeyEnc: String,
        ddAppKeyEnc: String?,
        ddSite: String,
    ): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        // If no AI config row exists yet, create a minimal placeholder so the DD
        // creds have a home. The provider/model/apiKeyEnc placeholders indicate an
        // incomplete config — the wizard will complete them in Step 3.
        val existing = TenantAiConfigs
            .select(TenantAiConfigs.columns)
            .where { TenantAiConfigs.tenantId eq tenantId }
            .singleOrNull()
        if (existing == null) {
            TenantAiConfigs.insert {
                it[TenantAiConfigs.tenantId]    = tenantId
                it[TenantAiConfigs.provider]    = "pending"
                it[TenantAiConfigs.model]       = "pending"
                it[TenantAiConfigs.apiKeyEnc]   = ""
                it[TenantAiConfigs.isActive]    = false
                it[TenantAiConfigs.ddApiKeyEnc] = ddApiKeyEnc
                it[TenantAiConfigs.ddAppKeyEnc] = ddAppKeyEnc
                it[TenantAiConfigs.ddSite]      = ddSite
                it[TenantAiConfigs.createdAt]   = now
                it[TenantAiConfigs.updatedAt]   = now
            }
        } else {
            TenantAiConfigs.update({ TenantAiConfigs.tenantId eq tenantId }) {
                it[TenantAiConfigs.ddApiKeyEnc] = ddApiKeyEnc
                it[TenantAiConfigs.ddAppKeyEnc] = ddAppKeyEnc
                it[TenantAiConfigs.ddSite]      = ddSite
                it[TenantAiConfigs.updatedAt]   = now
            }
        }
    }

    suspend fun getDDCredentials(tenantId: Long): DDCredentials? = dbQuery {
        TenantAiConfigs
            .select(TenantAiConfigs.ddApiKeyEnc, TenantAiConfigs.ddAppKeyEnc, TenantAiConfigs.ddSite)
            .where { TenantAiConfigs.tenantId eq tenantId }
            .singleOrNull()
            ?.let { row ->
                val apiKey = row[TenantAiConfigs.ddApiKeyEnc]
                if (apiKey.isNullOrBlank()) null
                else DDCredentials(
                    ddApiKey = apiKey,
                    ddAppKey = row[TenantAiConfigs.ddAppKeyEnc] ?: "",
                    ddSite   = row[TenantAiConfigs.ddSite],
                )
            }
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
        tenantId              = row[TenantAiConfigs.tenantId],
        provider              = row[TenantAiConfigs.provider],
        model                 = row[TenantAiConfigs.model],
        apiKeyEnc             = row[TenantAiConfigs.apiKeyEnc],
        githubTokenEnc        = row[TenantAiConfigs.githubTokenEnc],
        githubBaseBranch      = row[TenantAiConfigs.githubBaseBranch],
        githubAuthMode        = row[TenantAiConfigs.githubAuthMode],
        githubAppIdEnc        = row[TenantAiConfigs.githubAppIdEnc],
        githubAppPrivKeyEnc   = row[TenantAiConfigs.githubAppPrivKeyEnc],
        githubAppInstallIdEnc = row[TenantAiConfigs.githubAppInstallIdEnc],
        monthlyTokenBudget    = row[TenantAiConfigs.monthlyTokenBudget],
        tokensUsedMonth       = row[TenantAiConfigs.tokensUsedMonth],
        isActive              = row[TenantAiConfigs.isActive],
        ddApiKeyEnc           = row[TenantAiConfigs.ddApiKeyEnc],
        ddAppKeyEnc           = row[TenantAiConfigs.ddAppKeyEnc],
        ddSite                = row[TenantAiConfigs.ddSite],
        createdAt             = row[TenantAiConfigs.createdAt],
        updatedAt             = row[TenantAiConfigs.updatedAt],
    )
}
