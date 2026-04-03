package io.titlis.api.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.repository.RemediationRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class RemediationRoutesTest {

    @Test
    fun `GET remediation forwards tenant id from dev bypass`() = testApplication {
        val repo = mockk<RemediationRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.getByWorkload("workload-1", 42) } returns mapOf(
            "status" to "OPEN",
            "version" to 1,
            "github_pr_url" to null,
            "github_pr_number" to null,
            "triggered_at" to "2026-04-01T10:00:00Z",
        )

        application {
            installTestSecurity(authenticator)
            remediationRoutes(repo, authenticator)
        }

        val response = client.get("/v1/workloads/workload-1/remediation") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { repo.getByWorkload("workload-1", 42) }
    }
}
