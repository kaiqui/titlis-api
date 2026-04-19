package io.titlis.api.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.TenantAiConfigRecord
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

private val SAMPLE_RECORD = TenantAiConfigRecord(
    tenantId = 1L,
    provider = "openai",
    model = "gpt-4o",
    apiKeyEnc = "sk-test-key",
    githubTokenEnc = null,
    githubBaseBranch = "main",
    monthlyTokenBudget = 100_000,
    tokensUsedMonth = 5_000,
    isActive = true,
    createdAt = OffsetDateTime.now(ZoneOffset.UTC),
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC),
)

class AiConfigRoutesGetTest {

    @Test
    fun `GET ai-config returns 200 with masked key when configured`() = testApplication {
        val repo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.getByTenant(1L) } returns SAMPLE_RECORD

        application {
            installTestSecurity(authenticator)
            aiConfigRoutes(repo, authenticator)
        }

        val response = client.get("/v1/settings/ai-config") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "\"provider\":\"openai\"")
        assertContains(body, "\"model\":\"gpt-4o\"")
        assertContains(body, "\"hasApiKey\":true")
        // raw key must never appear in response
        assert("sk-test-key" !in body)
    }

    @Test
    fun `GET ai-config returns 404 when not configured`() = testApplication {
        val repo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.getByTenant(1L) } returns null

        application {
            installTestSecurity(authenticator)
            aiConfigRoutes(repo, authenticator)
        }

        val response = client.get("/v1/settings/ai-config") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "ai_not_configured")
    }

    @Test
    fun `GET ai-config returns 401 without auth`() = testApplication {
        val repo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            aiConfigRoutes(repo, authenticator)
        }

        val response = client.get("/v1/settings/ai-config")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET ai-config returns 403 for non-admin`() = testApplication {
        val repo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            aiConfigRoutes(repo, authenticator)
        }

        val response = client.get("/v1/settings/ai-config") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.viewer")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}

class AiConfigRoutesPutTest {

    @Test
    fun `PUT ai-config creates config and returns 200`() = testApplication {
        val repo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery {
            repo.upsert(
                tenantId = 1L,
                provider = "openai",
                model = "gpt-4o",
                apiKeyEnc = "sk-newkey",
                githubTokenEnc = null,
                githubBaseBranch = "main",
                monthlyTokenBudget = 50_000,
            )
        } returns SAMPLE_RECORD.copy(monthlyTokenBudget = 50_000)

        application {
            installTestSecurity(authenticator)
            aiConfigRoutes(repo, authenticator)
        }

        val response = client.put("/v1/settings/ai-config") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody(
                """{"provider":"openai","model":"gpt-4o","apiKey":"sk-newkey","monthlyTokenBudget":50000}"""
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { repo.upsert(1L, "openai", "gpt-4o", "sk-newkey", null, "main", 50_000) }
    }

    @Test
    fun `PUT ai-config returns 400 for unsupported provider`() = testApplication {
        val repo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            aiConfigRoutes(repo, authenticator)
        }

        val response = client.put("/v1/settings/ai-config") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"unsupported","model":"x","apiKey":"key"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "unsupported_provider")
    }

    @Test
    fun `PUT ai-config returns 400 for blank model`() = testApplication {
        val repo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            aiConfigRoutes(repo, authenticator)
        }

        val response = client.put("/v1/settings/ai-config") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"openai","model":"","apiKey":"key"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "model_required")
    }
}

class AiConfigRoutesTestEndpointTest {

    @Test
    fun `POST ai-config test returns config_valid when configured`() = testApplication {
        val repo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.getByTenant(1L) } returns SAMPLE_RECORD

        application {
            installTestSecurity(authenticator)
            aiConfigRoutes(repo, authenticator)
        }

        val response = client.post("/v1/settings/ai-config/test") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "config_valid")
    }

    @Test
    fun `POST ai-config test returns 404 when not configured`() = testApplication {
        val repo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.getByTenant(1L) } returns null

        application {
            installTestSecurity(authenticator)
            aiConfigRoutes(repo, authenticator)
        }

        val response = client.post("/v1/settings/ai-config/test") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
