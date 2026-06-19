package io.titlis.api.services

import io.titlis.api.auth.ClerkIdentity
import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.PlatformUsers
import io.titlis.api.database.tables.Tenants
import io.titlis.api.repository.TeamInviteRepository
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class ProvisionResult(
    val tenantId: Long,
    val tenantSlug: String,
    val tenantName: String,
    val role: String,
    val email: String?,
    val isNewTenant: Boolean,
)

class ClerkProvisionService(
    private val teamInviteRepo: TeamInviteRepository,
) {
    private val log = LoggerFactory.getLogger(ClerkProvisionService::class.java)

    suspend fun provision(identity: ClerkIdentity): ProvisionResult {
        val existing = findExistingUser(identity.clerkUserId)
        if (existing != null) {
            log.debug("clerk_provision existing clerk_user_id={}", identity.clerkUserId)
            return existing
        }

        val email = identity.email?.trim()?.lowercase()

        if (email != null) {
            val invite = teamInviteRepo.findByEmail(email)
            if (invite != null) {
                log.info("clerk_provision member email={} tenant_id={}", email, invite.tenantId)
                val result = createUser(identity.clerkUserId, email, invite.tenantId, invite.titlisRole)
                teamInviteRepo.markProvisioned(invite.tenantTeamInviteId)
                return result
            }
        }

        log.info("clerk_provision new_tenant clerk_user_id={}", identity.clerkUserId)
        return createTenantAndAdmin(identity.clerkUserId, email)
    }

    private suspend fun findExistingUser(clerkUserId: String): ProvisionResult? = dbQuery {
        PlatformUsers
            .join(Tenants, JoinType.INNER, PlatformUsers.tenantId, Tenants.tenantId)
            .select(
                PlatformUsers.platformUserId,
                PlatformUsers.tenantId,
                PlatformUsers.email,
                PlatformUsers.platformRole,
                Tenants.slug,
                Tenants.tenantName,
            )
            .where {
                (PlatformUsers.clerkUserId eq clerkUserId) and
                PlatformUsers.deletedAt.isNull() and
                (Tenants.isActive eq true)
            }
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                ProvisionResult(
                    tenantId = row[PlatformUsers.tenantId],
                    tenantSlug = row[Tenants.slug],
                    tenantName = row[Tenants.tenantName],
                    role = row[PlatformUsers.platformRole],
                    email = row[PlatformUsers.email],
                    isNewTenant = false,
                )
            }
    }

    private suspend fun createUser(
        clerkUserId: String,
        email: String,
        tenantId: Long,
        role: String,
    ): ProvisionResult = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        PlatformUsers.insert {
            it[PlatformUsers.clerkUserId] = clerkUserId
            it[PlatformUsers.tenantId] = tenantId
            it[PlatformUsers.email] = email
            it[PlatformUsers.platformRole] = role
            it[PlatformUsers.isActive] = true
            it[PlatformUsers.createdAt] = now
            it[PlatformUsers.updatedAt] = now
        }
        val tenant = Tenants
            .select(Tenants.slug, Tenants.tenantName)
            .where { Tenants.tenantId eq tenantId }
            .single()
        ProvisionResult(
            tenantId = tenantId,
            tenantSlug = tenant[Tenants.slug],
            tenantName = tenant[Tenants.tenantName],
            role = role,
            email = email,
            isNewTenant = false,
        )
    }

    private suspend fun createTenantAndAdmin(
        clerkUserId: String,
        email: String?,
    ): ProvisionResult = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val slug = uniqueSlug(email)
        val name = email
            ?.substringBefore("@")
            ?.replace(Regex("[^a-zA-Z0-9 ]"), " ")
            ?.trim()
            ?.split(" ")
            ?.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            ?.ifBlank { null }
            ?: "Novo Tenant"

        Tenants.insert {
            it[Tenants.tenantName] = name
            it[Tenants.slug] = slug
            it[Tenants.isActive] = true
            it[Tenants.tenantPlan] = "free"
            it[Tenants.createdAt] = now
            it[Tenants.updatedAt] = now
        }

        val tenantId = Tenants
            .select(Tenants.tenantId)
            .where { Tenants.slug eq slug }
            .single()[Tenants.tenantId]

        PlatformUsers.insert {
            it[PlatformUsers.clerkUserId] = clerkUserId
            it[PlatformUsers.tenantId] = tenantId
            it[PlatformUsers.email] = email ?: "$clerkUserId@clerk"
            it[PlatformUsers.platformRole] = "admin"
            it[PlatformUsers.isActive] = true
            it[PlatformUsers.createdAt] = now
            it[PlatformUsers.updatedAt] = now
        }

        ProvisionResult(
            tenantId = tenantId,
            tenantSlug = slug,
            tenantName = name,
            role = "admin",
            email = email,
            isNewTenant = true,
        )
    }

    // Chamado dentro de dbQuery — sem suspend
    private fun uniqueSlug(email: String?): String {
        val base = email
            ?.substringBefore("@")
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            ?.take(50)
            ?.ifBlank { null }
            ?: "tenant"

        val exists = Tenants
            .select(Tenants.tenantId)
            .where { Tenants.slug eq base }
            .singleOrNull() != null

        return if (!exists) base else "$base-${System.currentTimeMillis() % 100000}"
    }
}
