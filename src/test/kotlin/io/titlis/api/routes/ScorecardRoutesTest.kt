package io.titlis.api.routes

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.mockk
import io.titlis.api.repository.ScorecardRepository
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ScorecardRoutesTest {

    @Test
    fun `GET dashboard returns 200`() = testApplication {
        val repo = mockk<ScorecardRepository>()
        coEvery { repo.getDashboard(any()) } returns listOf(
            mapOf("workload_id" to "abc", "overall_score" to 90.0)
        )
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            scorecardRoutes(repo)
        }
        val response = client.get("/v1/dashboard")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
