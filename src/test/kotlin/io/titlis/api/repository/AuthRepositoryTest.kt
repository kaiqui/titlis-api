package io.titlis.api.repository

import io.titlis.api.auth.OktaIdentity
import io.titlis.api.auth.PasswordHasher
import io.titlis.api.auth.PlatformRole
import io.titlis.api.database.tables.PlatformUsers
import io.titlis.api.database.tables.TenantAuthIntegrations
import io.titlis.api.database.tables.Tenants
import io.titlis.api.database.tables.UserAuthIdentities
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuthRepositoryTest {

    private lateinit var db: Database
    private lateinit var repo: AuthRepository

    @BeforeTest
    fun setup() {
        db = Database.connect(
            url = "jdbc:h2:mem:test_auth_repo_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            driver = "org.h2.Driver",
        )
        transaction(db) {
            exec("CREATE SCHEMA IF NOT EXISTS titlis_oltp")
            SchemaUtils.create(Tenants, PlatformUsers, TenantAuthIntegrations, UserAuthIdentities)

            val now = OffsetDateTime.now(ZoneOffset.UTC)
            Tenants.insert {
                it[tenantId] = 42L
                it[tenantName] = "Jeitto"
                it[slug] = "jeitto"
                it[isActive] = true
                it[tenantPlan] = "enterprise"
                it[createdAt] = now
                it[updatedAt] = now
            }

            TenantAuthIntegrations.insert {
                it[tenantAuthIntegrationId] = 7L
                it[tenantId] = 42L
                it[providerType] = "okta"
                it[integrationKind] = "sso_oidc"
                it[integrationName] = "Okta SSO"
                it[isEnabled] = true
                it[isPrimary] = true
                it[issuerUrl] = "https://jeitto.okta.com"
                it[clientId] = "client-id"
                it[audience] = "api://default"
                it[scopes] = "openid profile email groups"
                it[createdAt] = now
                it[updatedAt] = now
                it[verifiedAt] = now
                it[activatedAt] = now
            }
        }
        repo = AuthRepository(PasswordHasher())
    }

    private suspend fun <T> dbOp(block: suspend () -> T): T =
        newSuspendedTransaction(db = db) { block() }

    @Test
    fun `resolveFederatedUser provisions active platform user on first okta login`() = runTest {
        dbOp {
            val identity = OktaIdentity(
                subject = "00u1ylbq6kyxOjlDC1d8",
                email = "kaique.lima@jeitto.com.br",
                displayName = "Kaique Alves Lima",
                tenantId = null,
                groups = listOf("Jeitto Confia - Admin"),
                issuer = "https://jeitto.okta.com",
            )

            val user = repo.resolveFederatedUser(identity, "jeitto")

            assertNotNull(user)
            assertEquals("kaique.lima@jeitto.com.br", user.email)
            assertEquals("Kaique Alves Lima", user.displayName)
            assertEquals("jeitto", user.tenantSlug)
            assertEquals(PlatformRole.ADMIN, user.role)

            val storedUser = PlatformUsers.selectAll().single()
            assertEquals("kaique.lima@jeitto.com.br", storedUser[PlatformUsers.email])
            assertEquals("Kaique Alves Lima", storedUser[PlatformUsers.displayName])
            assertEquals("admin", storedUser[PlatformUsers.platformRole])
            assertEquals(true, storedUser[PlatformUsers.isActive])

            val storedIdentity = UserAuthIdentities.selectAll().single()
            assertEquals("00u1ylbq6kyxOjlDC1d8", storedIdentity[UserAuthIdentities.providerSubject])
            assertEquals("kaique.lima@jeitto.com.br", storedIdentity[UserAuthIdentities.emailSnapshot])
        }
    }
}
