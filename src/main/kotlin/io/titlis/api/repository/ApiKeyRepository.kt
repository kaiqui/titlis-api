package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.TenantApiKeys
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class ApiKeyRecord(
    val apiKeyId: Long,
    val tenantId: Long,
    val keyPrefix: String,
    val description: String?,
    val isActive: Boolean,
    val lastUsedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

class ApiKeyRepository {

    suspend fun create(
        tenantId: Long,
        description: String?,
        createdByUserId: Long?,
    ): Pair<ApiKeyRecord, String> = dbQuery {
        val rawToken = generateToken()
        val hash = sha256Hex(rawToken)
        val prefix = rawToken.take(12)
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        TenantApiKeys.insert {
            it[TenantApiKeys.tenantId]        = tenantId
            it[TenantApiKeys.keyPrefix]       = prefix
            it[TenantApiKeys.keyHash]         = hash
            it[TenantApiKeys.description]     = description
            it[TenantApiKeys.isActive]        = true
            it[TenantApiKeys.createdByUserId] = createdByUserId
            it[TenantApiKeys.createdAt]       = now
        }

        val row = TenantApiKeys
            .select(TenantApiKeys.columns)
            .where { TenantApiKeys.keyHash eq hash }
            .single()

        Pair(mapRow(row), rawToken)
    }

    suspend fun resolveByToken(rawToken: String): Long? = dbQuery {
        val hash = sha256Hex(rawToken)
        val row = TenantApiKeys
            .select(TenantApiKeys.apiKeyId, TenantApiKeys.tenantId)
            .where { (TenantApiKeys.keyHash eq hash) and (TenantApiKeys.isActive eq true) }
            .singleOrNull() ?: return@dbQuery null

        TenantApiKeys.update({ TenantApiKeys.apiKeyId eq row[TenantApiKeys.apiKeyId] }) {
            it[lastUsedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }

        row[TenantApiKeys.tenantId]
    }

    suspend fun listByTenant(tenantId: Long): List<ApiKeyRecord> = dbQuery {
        TenantApiKeys
            .selectAll()
            .where { (TenantApiKeys.tenantId eq tenantId) and (TenantApiKeys.isActive eq true) }
            .map { mapRow(it) }
    }

    suspend fun lastEventAt(tenantId: Long): OffsetDateTime? = dbQuery {
        TenantApiKeys
            .select(TenantApiKeys.lastUsedAt.max())
            .where { (TenantApiKeys.tenantId eq tenantId) and (TenantApiKeys.isActive eq true) }
            .singleOrNull()
            ?.get(TenantApiKeys.lastUsedAt.max())
    }

    suspend fun revoke(apiKeyId: Long, tenantId: Long): Boolean = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        TenantApiKeys.update({
            (TenantApiKeys.apiKeyId eq apiKeyId) and (TenantApiKeys.tenantId eq tenantId)
        }) {
            it[isActive]  = false
            it[revokedAt] = now
        } > 0
    }

    // ---- internal helpers -------------------------------------------------------

    internal fun generateToken(): String {
        val bytes = ByteArray(20)
        SecureRandom.getInstanceStrong().nextBytes(bytes)
        return "tls_k_" + bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun mapRow(row: ResultRow) = ApiKeyRecord(
        apiKeyId    = row[TenantApiKeys.apiKeyId],
        tenantId    = row[TenantApiKeys.tenantId],
        keyPrefix   = row[TenantApiKeys.keyPrefix],
        description = row[TenantApiKeys.description],
        isActive    = row[TenantApiKeys.isActive],
        lastUsedAt  = row[TenantApiKeys.lastUsedAt],
        createdAt   = row[TenantApiKeys.createdAt],
    )
}
