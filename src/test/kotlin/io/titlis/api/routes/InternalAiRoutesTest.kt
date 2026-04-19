package io.titlis.api.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.SloRepository
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

private const val AI_SECRET = "test-ai-secret"

private fun Application.installContentNegotiation() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

class InternalAiScorecardTest {

    @Test
    fun `GET scorecards returns 200 with scorecard data`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val remediationRepo = mockk<RemediationRepository>()
        val sloRepo = mockk<SloRepository>()
        coEvery { scorecardRepo.getByWorkloadId("uid-abc", 1L) } returns mapOf(
            "workload" to "payment-api",
            "namespace" to "production",
            "overall_score" to 75,
        )

        application {
            installContentNegotiation()
            internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, AI_SECRET)
        }

        val response = client.get("/v1/internal/ai/scorecards?tenantId=1&k8sUid=uid-abc") {
            header("X-Internal-Secret", AI_SECRET)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "payment-api")
    }

    @Test
    fun `GET scorecards returns 403 without secret`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val remediationRepo = mockk<RemediationRepository>()
        val sloRepo = mockk<SloRepository>()

        application {
            installContentNegotiation()
            internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, AI_SECRET)
        }

        val response = client.get("/v1/internal/ai/scorecards?tenantId=1&k8sUid=uid-abc")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET scorecards returns 404 when workload not found`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val remediationRepo = mockk<RemediationRepository>()
        val sloRepo = mockk<SloRepository>()
        coEvery { scorecardRepo.getByWorkloadId(any(), any()) } returns null

        application {
            installContentNegotiation()
            internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, AI_SECRET)
        }

        val response = client.get("/v1/internal/ai/scorecards?tenantId=1&k8sUid=missing") {
            header("X-Internal-Secret", AI_SECRET)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}

class InternalAiSloTest {

    @Test
    fun `GET slos returns 200 with slo list`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val remediationRepo = mockk<RemediationRepository>()
        val sloRepo = mockk<SloRepository>()
        coEvery { sloRepo.list(1L, null, null) } returns listOf(
            mapOf("name" to "payment-slo", "target" to "99.9"),
        )

        application {
            installContentNegotiation()
            internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, AI_SECRET)
        }

        val response = client.get("/v1/internal/ai/slos?tenantId=1") {
            header("X-Internal-Secret", AI_SECRET)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "payment-slo")
    }

    @Test
    fun `POST slo-configs propose-change returns 201`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val remediationRepo = mockk<RemediationRepository>()
        val sloRepo = mockk<SloRepository>()
        coEvery { sloRepo.proposeChange(10L, 1L, "target", "99.9", "99.95", "titlis-ai") } returns mapOf(
            "id" to "uuid-1",
            "field" to "target",
            "new_value" to "99.95",
            "status" to "pending",
        )

        application {
            installContentNegotiation()
            internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, AI_SECRET)
        }

        val response = client.post("/v1/internal/ai/slo-configs/10/propose-change?tenantId=1") {
            header("X-Internal-Secret", AI_SECRET)
            contentType(ContentType.Application.Json)
            setBody("""{"field":"target","oldValue":"99.9","newValue":"99.95","requestedBy":"titlis-ai"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertContains(response.bodyAsText(), "99.95")
    }

    @Test
    fun `POST slo-configs propose-change returns 400 for invalid field`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val remediationRepo = mockk<RemediationRepository>()
        val sloRepo = mockk<SloRepository>()

        application {
            installContentNegotiation()
            internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, AI_SECRET)
        }

        val response = client.post("/v1/internal/ai/slo-configs/10/propose-change?tenantId=1") {
            header("X-Internal-Secret", AI_SECRET)
            contentType(ContentType.Application.Json)
            setBody("""{"field":"invalid_field","oldValue":"x","newValue":"y","requestedBy":"titlis-ai"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

class InternalAiRemediationHistoryTest {

    @Test
    fun `GET remediations returns 200 with history`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val remediationRepo = mockk<RemediationRepository>()
        val sloRepo = mockk<SloRepository>()
        coEvery { remediationRepo.getHistory("uid-abc", 1L) } returns listOf(
            mapOf("status" to "MERGED", "github_pr_url" to "https://github.com/org/repo/pull/1"),
        )

        application {
            installContentNegotiation()
            internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, AI_SECRET)
        }

        val response = client.get("/v1/internal/ai/remediations?tenantId=1&k8sUid=uid-abc") {
            header("X-Internal-Secret", AI_SECRET)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "MERGED")
    }
}
