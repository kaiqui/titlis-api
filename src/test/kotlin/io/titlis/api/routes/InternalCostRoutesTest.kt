package io.titlis.api.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import io.titlis.api.repository.CostRepository
import io.titlis.api.repository.GcpBillingConfigRepository
import io.titlis.api.repository.WorkloadRefDTO
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

private const val INTERNAL_SECRET = "test-internal-secret"

private fun Application.installInternalCostContentNegotiation() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

class InternalCostRoutesTest {

    @Test
    fun `GET internal cost config returns 403 without secret`() = testApplication {
        val gcpRepo = mockk<GcpBillingConfigRepository>()
        val costRepo = mockk<CostRepository>()
        application {
            installInternalCostContentNegotiation()
            internalCostRoutes(gcpRepo, costRepo, INTERNAL_SECRET)
        }

        val response = client.get("/v1/internal/cost/config")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET internal cost config returns 200 with empty list when no configs`() = testApplication {
        val gcpRepo = mockk<GcpBillingConfigRepository>()
        val costRepo = mockk<CostRepository>()
        coEvery { gcpRepo.getAllActive() } returns emptyList()

        application {
            installInternalCostContentNegotiation()
            internalCostRoutes(gcpRepo, costRepo, INTERNAL_SECRET)
        }

        val response = client.get("/v1/internal/cost/config") {
            header("X-Internal-Secret", INTERNAL_SECRET)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }

    @Test
    fun `GET internal cost workloads returns 403 without secret`() = testApplication {
        val gcpRepo = mockk<GcpBillingConfigRepository>()
        val costRepo = mockk<CostRepository>()
        application {
            installInternalCostContentNegotiation()
            internalCostRoutes(gcpRepo, costRepo, INTERNAL_SECRET)
        }

        val response = client.get("/v1/internal/cost/workloads?tenantId=1")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET internal cost workloads returns 400 without tenantId`() = testApplication {
        val gcpRepo = mockk<GcpBillingConfigRepository>()
        val costRepo = mockk<CostRepository>()
        application {
            installInternalCostContentNegotiation()
            internalCostRoutes(gcpRepo, costRepo, INTERNAL_SECRET)
        }

        val response = client.get("/v1/internal/cost/workloads") {
            header("X-Internal-Secret", INTERNAL_SECRET)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET internal cost workloads returns 200 with workload list`() = testApplication {
        val gcpRepo = mockk<GcpBillingConfigRepository>()
        val costRepo = mockk<CostRepository>()
        coEvery { costRepo.getWorkloadRefs(1L) } returns listOf(
            WorkloadRefDTO(1L, "checkout-api", "production", "cluster-prod", "payments", 500.0, 256.0),
        )

        application {
            installInternalCostContentNegotiation()
            internalCostRoutes(gcpRepo, costRepo, INTERNAL_SECRET)
        }

        val response = client.get("/v1/internal/cost/workloads?tenantId=1") {
            header("X-Internal-Secret", INTERNAL_SECRET)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "checkout-api")
    }
}
