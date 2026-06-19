package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.TenantTeamInvites
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class TeamInvite(
    val tenantTeamInviteId: Long,
    val tenantId: Long,
    val email: String,
    val titlisRole: String,
    val createdAt: String,
    val provisionedAt: String?,
)

class TeamInviteRepository {

    suspend fun listByTenant(tenantId: Long): List<TeamInvite> = dbQuery {
        TenantTeamInvites
            .selectAll()
            .where { TenantTeamInvites.tenantId eq tenantId }
            .orderBy(TenantTeamInvites.createdAt, SortOrder.DESC)
            .map { it.toInvite() }
    }

    suspend fun findByEmail(email: String): TeamInvite? = dbQuery {
        TenantTeamInvites
            .selectAll()
            .where { TenantTeamInvites.email eq email.trim().lowercase() }
            .limit(1)
            .singleOrNull()
            ?.toInvite()
    }

    suspend fun create(tenantId: Long, email: String, role: String): TeamInvite = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val normalizedEmail = email.trim().lowercase()
        TenantTeamInvites.insert {
            it[TenantTeamInvites.tenantId] = tenantId
            it[TenantTeamInvites.email] = normalizedEmail
            it[TenantTeamInvites.titlisRole] = role
            it[TenantTeamInvites.createdAt] = now
        }
        TenantTeamInvites
            .selectAll()
            .where {
                (TenantTeamInvites.tenantId eq tenantId) and
                (TenantTeamInvites.email eq normalizedEmail)
            }
            .single()
            .toInvite()
    }

    suspend fun updateRole(tenantId: Long, email: String, role: String): Boolean = dbQuery {
        TenantTeamInvites.update({
            (TenantTeamInvites.tenantId eq tenantId) and
            (TenantTeamInvites.email eq email.trim().lowercase())
        }) {
            it[TenantTeamInvites.titlisRole] = role
        } > 0
    }

    suspend fun delete(tenantId: Long, email: String): Boolean = dbQuery {
        TenantTeamInvites.deleteWhere {
            (TenantTeamInvites.tenantId eq tenantId) and
            (TenantTeamInvites.email eq email.trim().lowercase())
        } > 0
    }

    suspend fun markProvisioned(tenantTeamInviteId: Long) = dbQuery {
        TenantTeamInvites.update({ TenantTeamInvites.tenantTeamInviteId eq tenantTeamInviteId }) {
            it[TenantTeamInvites.provisionedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    private fun ResultRow.toInvite() = TeamInvite(
        tenantTeamInviteId = this[TenantTeamInvites.tenantTeamInviteId],
        tenantId = this[TenantTeamInvites.tenantId],
        email = this[TenantTeamInvites.email],
        titlisRole = this[TenantTeamInvites.titlisRole],
        createdAt = this[TenantTeamInvites.createdAt].toString(),
        provisionedAt = this[TenantTeamInvites.provisionedAt]?.toString(),
    )
}
