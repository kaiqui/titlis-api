package io.titlis.api.routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.titlis.api.config.AiServiceConfig
import io.titlis.api.config.AppConfig
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.TenantAiConfigRecord
import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

private val AI_RECORD = TenantAiConfigRecord(
    tenantId = 1L,
    provider = "openai",
    model = "gpt-4o",
    apiKeyEnc = "sk-secret",
    githubTokenEnc = null,
    githubBaseBranch = "main",
    monthlyTokenBudget = 100_000,
    tokensUsedMonth = 0,
    isActive = true,
    createdAt = OffsetDateTime.now(ZoneOffset.UTC),
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC),
)

private val EXPLAIN_BODY = """
    {
      "pillar": "resilience",
      "severity": "error",
      "deploymentName": "payment-api",
      "namespace": "production"
    }
""".trimIndent()

private fun fakeAppConfig(
    aiUrl: String = "http://titlis-ai:8001",
    secret: String = "test-secret",
): AppConfig = mockk(relaxed = true) {
    every { aiService } returns AiServiceConfig(url = aiUrl, internalSecret = secret)
}

private fun fakeHttpClient(responseBody: String): HttpClient {
    val httpResponse = mockk<HttpResponse<java.io.InputStream>>()
    every { httpResponse.body() } returns ByteArrayInputStream(responseBody.toByteArray())
    val client = mockk<HttpClient>()
    every { client.send(any(), any<HttpResponse.BodyHandler<java.io.InputStream>>()) } returns httpResponse
    return client
}

class AiRoutesExplainTest {

    @Test
    fun `POST explain returns 404 when workload not found`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { scorecardRepo.getByWorkloadId(any(), 1L) } returns null

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/workloads/unknown-uid/findings/RES-003/explain") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody(EXPLAIN_BODY)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "workload_not_found")
    }

    @Test
    fun `POST explain returns 424 when AI not configured`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { scorecardRepo.getByWorkloadId(any(), 1L) } returns mapOf("workload_name" to "payment-api")
        coEvery { aiConfigRepo.getByTenant(1L) } returns null

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/workloads/wl-uid/findings/RES-003/explain") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody(EXPLAIN_BODY)
        }

        assertEquals(424, response.status.value)
        assertContains(response.bodyAsText(), "ai_not_configured")
    }

    @Test
    fun `POST explain returns 429 when quota exceeded`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { scorecardRepo.getByWorkloadId(any(), 1L) } returns mapOf("workload_name" to "payment-api")
        coEvery { aiConfigRepo.getByTenant(1L) } returns AI_RECORD.copy(
            monthlyTokenBudget = 1_000,
            tokensUsedMonth = 1_000,
        )

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/workloads/wl-uid/findings/RES-003/explain") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody(EXPLAIN_BODY)
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertContains(response.bodyAsText(), "quota_exceeded")
    }

    @Test
    fun `POST explain proxies SSE stream from titlis-ai`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { scorecardRepo.getByWorkloadId(any(), 1L) } returns mapOf("workload_name" to "payment-api")
        coEvery { aiConfigRepo.getByTenant(1L) } returns AI_RECORD

        val ssePayload = "data: {\"type\":\"chunk\",\"content\":\"Explicação\"}\n\ndata: {\"type\":\"done\"}\n\n"
        val httpClient = fakeHttpClient(ssePayload)

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator, httpClient)
        }

        val response = client.post("/v1/ai/workloads/wl-uid/findings/RES-003/explain") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody(EXPLAIN_BODY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.headers["Content-Type"] ?: "", "text/event-stream")
        assertContains(response.bodyAsText(), "Explicação")
    }

    @Test
    fun `POST explain returns 401 without auth`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/workloads/wl-uid/findings/RES-003/explain") {
            contentType(ContentType.Application.Json)
            setBody(EXPLAIN_BODY)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST explain allows non-admin users`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { scorecardRepo.getByWorkloadId(any(), 1L) } returns mapOf("workload_name" to "payment-api")
        coEvery { aiConfigRepo.getByTenant(1L) } returns AI_RECORD

        val ssePayload = "data: {\"type\":\"done\"}\n\n"
        val httpClient = fakeHttpClient(ssePayload)

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator, httpClient)
        }

        val response = client.post("/v1/ai/workloads/wl-uid/findings/RES-003/explain") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.engineer")
            contentType(ContentType.Application.Json)
            setBody(EXPLAIN_BODY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
