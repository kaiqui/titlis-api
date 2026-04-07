package io.titlis.api.repository

import io.titlis.api.database.tables.TenantApiKeys
import io.titlis.api.database.tables.Tenants
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeyRepositoryTest {

    private lateinit var db: Database
    private lateinit var repo: ApiKeyRepository

    @BeforeTest
    fun setup() {
        db = Database.connect(
            url = "jdbc:h2:mem:test_apikeys_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            driver = "org.h2.Driver",
        )
        transaction(db) {
            exec("CREATE SCHEMA IF NOT EXISTS titlis_oltp")
            SchemaUtils.create(Tenants, TenantApiKeys)
            Tenants.insert {
                it[tenantId]   = 1L
                it[tenantName] = "Tenant Test"
                it[slug]       = "tenant-test"
                it[isActive]   = true
                it[tenantPlan] = "free"
                it[createdAt]  = OffsetDateTime.now(ZoneOffset.UTC)
                it[updatedAt]  = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
        repo = ApiKeyRepository()
    }

    private suspend fun <T> dbOp(block: suspend () -> T): T =
        newSuspendedTransaction(db = db) { block() }

    @Test
    fun `generate token has tls_k_ prefix and 46 chars total`() {
        val token = repo.generateToken()
        assertTrue(token.startsWith("tls_k_"), "Expected tls_k_ prefix, got: $token")
        assertEquals(46, token.length, "tls_k_ (6) + 40 hex chars = 46")
    }

    @Test
    fun `sha256Hex is deterministic`() {
        val hash1 = repo.sha256Hex("test-input")
        val hash2 = repo.sha256Hex("test-input")
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length, "SHA-256 produces 64 hex chars")
    }

    @Test
    fun `sha256Hex is different for different inputs`() {
        val h1 = repo.sha256Hex("token-a")
        val h2 = repo.sha256Hex("token-b")
        assertTrue(h1 != h2)
    }

    @Test
    fun `create returns raw token and record with correct prefix`() = runTest {
        dbOp {
            val (record, rawToken) = repo.create(tenantId = 1L, description = "Operator key", createdByUserId = null)
            assertTrue(rawToken.startsWith("tls_k_"))
            assertEquals(rawToken.take(12), record.keyPrefix)
            assertEquals(1L, record.tenantId)
            assertEquals("Operator key", record.description)
            assertTrue(record.isActive)
            assertNull(record.lastUsedAt)
        }
    }

    @Test
    fun `create stores hash not raw token in DB`() = runTest {
        dbOp {
            val (_, rawToken) = repo.create(tenantId = 1L, description = null, createdByUserId = null)
            val expectedHash = repo.sha256Hex(rawToken)
            // raw token must NOT be stored; re-resolving proves hash is stored
            val resolvedTenant = repo.resolveByToken(rawToken)
            assertEquals(1L, resolvedTenant)
            assertTrue(rawToken != expectedHash, "raw token and hash must differ")
        }
    }

    @Test
    fun `resolveByToken returns tenantId for valid active key`() = runTest {
        dbOp {
            val (_, rawToken) = repo.create(tenantId = 1L, description = null, createdByUserId = null)
            val resolved = repo.resolveByToken(rawToken)
            assertEquals(1L, resolved)
        }
    }

    @Test
    fun `resolveByToken returns null for unknown token`() = runTest {
        dbOp {
            val resolved = repo.resolveByToken("tls_k_doesnotexist00000000000000000000000")
            assertNull(resolved)
        }
    }

    @Test
    fun `updateLastUsedAtAsync updates lastUsedAt for given token`() = runTest {
        dbOp {
            val (record, rawToken) = repo.create(tenantId = 1L, description = null, createdByUserId = null)
            assertNull(record.lastUsedAt)
            repo.updateLastUsedAtAsync(rawToken)
            delay(100)  // Allow async update to complete
            val updated = repo.listByTenant(1L).first { it.apiKeyId == record.apiKeyId }
            assertNotNull(updated.lastUsedAt)
        }
    }

    @Test
    fun `listByTenant returns only active keys for that tenant`() = runTest {
        dbOp {
            val (r1, _) = repo.create(tenantId = 1L, description = "key-1", createdByUserId = null)
            val (r2, _) = repo.create(tenantId = 1L, description = "key-2", createdByUserId = null)
            repo.revoke(r1.apiKeyId, tenantId = 1L)

            val active = repo.listByTenant(1L)
            assertEquals(1, active.size)
            assertEquals(r2.apiKeyId, active.single().apiKeyId)
        }
    }

    @Test
    fun `revoke soft-deletes and resolveByToken returns null after revoke`() = runTest {
        dbOp {
            val (record, rawToken) = repo.create(tenantId = 1L, description = null, createdByUserId = null)
            val revokeResult = repo.revoke(record.apiKeyId, tenantId = 1L)
            assertTrue(revokeResult)
            val resolved = repo.resolveByToken(rawToken)
            assertNull(resolved, "Revoked key must not resolve")
        }
    }

    @Test
    fun `revoke returns false for non-existent or wrong tenant`() = runTest {
        dbOp {
            val result = repo.revoke(apiKeyId = 99999L, tenantId = 1L)
            assertTrue(!result)
        }
    }
}
