package io.titlis.api.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.repository.SloRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class SloRoutesTest {

    @Test
    fun `GET slos forwards tenant id from dev bypass`() = testApplication {
        val repo = mockk<SloRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.list(42, null, null) } returns listOf(
            mapOf(
                "slo_config_id" to 1,
                "name" to "latency",
                "namespace" to "payments",
                "cluster" to "cluster-a",
                "environment" to "prod",
                "slo_type" to "availability",
                "timeframe" to "30d",
                "target" to 99.9,
                "warning" to 99.95,
                "datadog_slo_id" to "abc",
                "datadog_slo_state" to "OK",
                "detected_framework" to "FASTAPI",
                "detection_source" to "annotation",
                "last_sync_at" to "2026-04-01T10:00:00Z",
            ),
        )

        application {
            installTestSecurity(authenticator)
            sloRoutes(repo, authenticator)
        }

        val response = client.get("/v1/slos") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.engineer")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { repo.list(42, null, null) }
    }

    @Test
    fun `GET slo by name forwards tenant id from dev bypass`() = testApplication {
        val repo = mockk<SloRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.getByName("payments", "latency", 42) } returns mapOf(
            "slo_config_id" to 1,
            "slo_type" to "availability",
            "timeframe" to "30d",
            "target" to 99.9,
            "datadog_slo_id" to "abc",
            "datadog_slo_state" to "OK",
            "detected_framework" to "FASTAPI",
            "detection_source" to "annotation",
            "last_sync_at" to "2026-04-01T10:00:00Z",
        )

        application {
            installTestSecurity(authenticator)
            sloRoutes(repo, authenticator)
        }

        val response = client.get("/v1/namespaces/payments/slos/latency") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.viewer")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { repo.getByName("payments", "latency", 42) }
    }
}
