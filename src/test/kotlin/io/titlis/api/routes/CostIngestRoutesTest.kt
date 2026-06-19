package io.titlis.api.routes

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
import io.titlis.api.repository.CostRepository
import io.titlis.api.repository.GcpBillingConfigRepository
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

private const val COST_SECRET = "test-cost-secret"

private fun Application.installCostContentNegotiation() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

class CostIngestRoutesTest {

    @Test
    fun `POST cost ingest returns 403 without secret`() = testApplication {
        val costRepo = mockk<CostRepository>()
        val gcpRepo = mockk<GcpBillingConfigRepository>()
        application {
            installCostContentNegotiation()
            costIngestRoutes(costRepo, gcpRepo, COST_SECRET)
        }

        val response = client.post("/v1/cost/ingest") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":1,"date":"2026-01-01","provider":"mock","workloadCosts":[],"namespaceCosts":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST cost ingest returns 400 for invalid date`() = testApplication {
        val costRepo = mockk<CostRepository>()
        val gcpRepo = mockk<GcpBillingConfigRepository>()
        application {
            installCostContentNegotiation()
            costIngestRoutes(costRepo, gcpRepo, COST_SECRET)
        }

        val response = client.post("/v1/cost/ingest") {
            header("X-Internal-Secret", COST_SECRET)
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":1,"date":"not-a-date","provider":"mock","workloadCosts":[],"namespaceCosts":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "invalid date")
    }

    @Test
    fun `POST cost ingest returns 200 with accepted count`() = testApplication {
        val costRepo = mockk<CostRepository>()
        val gcpRepo = mockk<GcpBillingConfigRepository>()
        coEvery { costRepo.insertWorkloadCostBatch(any(), any(), any(), any()) } returns 0
        coEvery { costRepo.insertNamespaceCostBatch(any(), any(), any(), any()) } returns Unit
        coEvery { gcpRepo.markCollected(any(), any()) } returns Unit

        application {
            installCostContentNegotiation()
            costIngestRoutes(costRepo, gcpRepo, COST_SECRET)
        }

        val response = client.post("/v1/cost/ingest") {
            header("X-Internal-Secret", COST_SECRET)
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":1,"date":"2026-01-01","provider":"mock","workloadCosts":[],"namespaceCosts":[]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "accepted")
    }
}
