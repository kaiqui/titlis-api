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
    every { httpResponse.statusCode() } returns 200
    every { httpResponse.body() } returns ByteArrayInputStream(responseBody.toByteArray())
    val client = mockk<HttpClient>()
    every { client.send(any(), any<HttpResponse.BodyHandler<java.io.InputStream>>()) } returns httpResponse
    return client
}

class AiRoutesHttpVersionTest {

    @Test
    fun `default HttpClient uses HTTP_1_1 to avoid h2c upgrade with uvicorn`() {
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        assertEquals(HttpClient.Version.HTTP_1_1, client.version())
    }
}

class AiRoutesAgentChatTest {

    @Test
    fun `POST agent chat returns 424 when AI not configured`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { aiConfigRepo.getByTenant(1L) } returns null

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/agent/chat") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.engineer")
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"sess-001","message":"Olá"}""")
        }

        assertEquals(424, response.status.value)
        assertContains(response.bodyAsText(), "ai_not_configured")
    }

    @Test
    fun `POST agent chat proxies SSE stream from titlis-ai`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { aiConfigRepo.getByTenant(1L) } returns AI_RECORD

        val ssePayload = "data: {\"type\":\"message\",\"content\":\"Olá! Sou ARIA.\"}\n\ndata: {\"type\":\"done\"}\n\n"
        val httpClient = fakeHttpClient(ssePayload)

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator, httpClient)
        }

        val response = client.post("/v1/ai/agent/chat") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.engineer")
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"sess-001","message":"Olá"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.headers["Content-Type"] ?: "", "text/event-stream")
        assertContains(response.bodyAsText(), "ARIA")
    }

    @Test
    fun `POST agent chat returns 401 without auth`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/agent/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId":"sess-001","message":"Olá"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST agent tools respond proxies SSE stream`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        val ssePayload = "data: {\"type\":\"tool_result\",\"approved\":true}\n\ndata: {\"type\":\"done\"}\n\n"
        val httpClient = fakeHttpClient(ssePayload)

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator, httpClient)
        }

        val response = client.post("/v1/ai/agent/sess-abc/tools/respond") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.engineer")
            contentType(ContentType.Application.Json)
            setBody("""{"decisions":[{"proposalId":"p-1","approved":true}]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.headers["Content-Type"] ?: "", "text/event-stream")
        assertContains(response.bodyAsText(), "tool_result")
    }
}

class AiRoutesRemediateTest {

    @Test
    fun `POST remediate returns 404 when workload not found`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { scorecardRepo.getByWorkloadId(any(), 1L) } returns null

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/workloads/unknown-uid/remediate") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.engineer")
            contentType(ContentType.Application.Json)
            setBody("""{"findingIds":["f-1"],"repoUrl":"https://github.com/org/repo"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertContains(response.bodyAsText(), "workload_not_found")
    }

    @Test
    fun `POST remediate returns 424 when AI not configured`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { scorecardRepo.getByWorkloadId(any(), 1L) } returns mapOf("workload_name" to "my-app")
        coEvery { aiConfigRepo.getByTenant(1L) } returns null

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/workloads/wl-uid/remediate") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.engineer")
            contentType(ContentType.Application.Json)
            setBody("""{"findingIds":["f-1"],"repoUrl":"https://github.com/org/repo"}""")
        }

        assertEquals(424, response.status.value)
        assertContains(response.bodyAsText(), "ai_not_configured")
    }

    @Test
    fun `POST remediate proxies SSE stream with fix_ready event`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { scorecardRepo.getByWorkloadId(any(), 1L) } returns mapOf("workload_name" to "my-app")
        coEvery { aiConfigRepo.getByTenant(1L) } returns AI_RECORD

        val ssePayload = "data: {\"type\":\"fix_ready\",\"thread_id\":\"t-1\",\"patched_manifest\":\"...\"}\n\ndata: {\"type\":\"done\"}\n\n"
        val httpClient = fakeHttpClient(ssePayload)

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator, httpClient)
        }

        val response = client.post("/v1/ai/workloads/wl-uid/remediate") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.engineer")
            contentType(ContentType.Application.Json)
            setBody("""{"findingIds":["f-1"],"repoUrl":"https://github.com/org/repo"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.headers["Content-Type"] ?: "", "text/event-stream")
        assertContains(response.bodyAsText(), "fix_ready")
    }

    @Test
    fun `POST remediate returns 401 without auth`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/workloads/wl-uid/remediate") {
            contentType(ContentType.Application.Json)
            setBody("""{"findingIds":["f-1"],"repoUrl":"https://github.com/org/repo"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST confirm remediation proxies SSE stream with pr_created event`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        val ssePayload = "data: {\"type\":\"pr_created\",\"pr_url\":\"https://github.com/org/repo/pull/42\"}\n\ndata: {\"type\":\"done\"}\n\n"
        val httpClient = fakeHttpClient(ssePayload)

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator, httpClient)
        }

        val response = client.post("/v1/ai/remediate/thread-xyz/confirm") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.engineer")
            contentType(ContentType.Application.Json)
            setBody("""{"approved":true}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.headers["Content-Type"] ?: "", "text/event-stream")
        assertContains(response.bodyAsText(), "pr_created")
    }

    @Test
    fun `POST confirm remediation returns 401 without auth`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            aiRoutes(scorecardRepo, aiConfigRepo, fakeAppConfig(), authenticator)
        }

        val response = client.post("/v1/ai/remediate/thread-xyz/confirm") {
            contentType(ContentType.Application.Json)
            setBody("""{"approved":true}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
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
