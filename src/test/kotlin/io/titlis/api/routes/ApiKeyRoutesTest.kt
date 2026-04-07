package io.titlis.api.routes

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.repository.ApiKeyRecord
import io.titlis.api.repository.ApiKeyRepository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiKeyRoutesTest {

    private fun sampleRecord(id: Long = 1L) = ApiKeyRecord(
        apiKeyId    = id,
        tenantId    = 42L,
        keyPrefix   = "tls_k_abc123",
        description = "Operator key",
        isActive    = true,
        lastUsedAt  = null,
        createdAt   = OffsetDateTime.now(ZoneOffset.UTC),
    )

    @Test
    fun `GET api-keys returns 401 when auth is missing`() = testApplication {
        val repo = mockk<ApiKeyRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            apiKeyRoutes(repo)
        }

        val response = client.get("/v1/settings/api-keys")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET api-keys returns 403 for non-admin role`() = testApplication {
        val repo = mockk<ApiKeyRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            apiKeyRoutes(repo)
        }

        val response = client.get("/v1/settings/api-keys") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.viewer")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET api-keys returns list for admin`() = testApplication {
        val repo = mockk<ApiKeyRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.listByTenant(42L) } returns listOf(sampleRecord(id = 7))

        application {
            installTestSecurity(authenticator)
            apiKeyRoutes(repo)
        }

        val response = client.get("/v1/settings/api-keys") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"id\":7"))
        coVerify(exactly = 1) { repo.listByTenant(42L) }
    }

    @Test
    fun `POST api-keys returns 401 when auth is missing`() = testApplication {
        val repo = mockk<ApiKeyRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            apiKeyRoutes(repo)
        }

        val response = client.post("/v1/settings/api-keys") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST api-keys creates key and returns 201 with rawToken for admin`() = testApplication {
        val repo = mockk<ApiKeyRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery {
            repo.create(tenantId = 42L, description = "My key", createdByUserId = any())
        } returns Pair(sampleRecord(), "tls_k_rawtoken1234567890abcdef1234567890abcd")

        application {
            installTestSecurity(authenticator)
            apiKeyRoutes(repo)
        }

        val response = client.post("/v1/settings/api-keys") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody("""{"description":"My key"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("rawToken"))
        assertTrue(body.contains("tls_k_rawtoken"))
    }

    @Test
    fun `DELETE api-keys returns 204 on successful revoke`() = testApplication {
        val repo = mockk<ApiKeyRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.revoke(apiKeyId = 5L, tenantId = 42L) } returns true

        application {
            installTestSecurity(authenticator)
            apiKeyRoutes(repo)
        }

        val response = client.delete("/v1/settings/api-keys/5") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify(exactly = 1) { repo.revoke(5L, 42L) }
    }

    @Test
    fun `DELETE api-keys returns 404 when key not found`() = testApplication {
        val repo = mockk<ApiKeyRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.revoke(apiKeyId = 99L, tenantId = 42L) } returns false

        application {
            installTestSecurity(authenticator)
            apiKeyRoutes(repo)
        }

        val response = client.delete("/v1/settings/api-keys/99") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE api-keys returns 400 for non-numeric id`() = testApplication {
        val repo = mockk<ApiKeyRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            apiKeyRoutes(repo)
        }

        val response = client.delete("/v1/settings/api-keys/notanumber") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
