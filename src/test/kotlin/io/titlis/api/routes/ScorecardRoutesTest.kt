package io.titlis.api.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.repository.ScorecardRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class ScorecardRoutesTest {

    @Test
    fun `GET dashboard returns 200`() = testApplication {
        val repo = mockk<ScorecardRepository>()
        coEvery { repo.getDashboard(any(), any()) } returns listOf(
            mapOf("workload_id" to "abc", "overall_score" to 90.0)
        )
        application {
            scorecardRoutes(repo)
        }
        val response = client.get("/v1/dashboard")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET dashboard returns 401 when auth is required and principal is missing`() = testApplication {
        val repo = mockk<ScorecardRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            scorecardRoutes(repo, authenticator)
        }

        val response = client.get("/v1/dashboard")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET dashboard forwards tenant id from dev bypass`() = testApplication {
        val repo = mockk<ScorecardRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.getDashboard(42, null) } returns listOf(
            mapOf("workload_id" to "abc", "overall_score" to 90.0)
        )

        application {
            installTestSecurity(authenticator)
            scorecardRoutes(repo, authenticator)
        }

        val response = client.get("/v1/dashboard") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-User", "tenant-admin@company.com")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { repo.getDashboard(42, null) }
    }
}
