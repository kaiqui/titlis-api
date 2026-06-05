package io.titlis.api.routes

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.WorkloadGithubLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class GitHubLinkRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun authenticatedHeaders(tenantId: Long = 1L, role: String = "titlis.admin"): Map<String, String> = mapOf(
        "X-Dev-Auth"      to "true",
        "X-Dev-Tenant-Id" to tenantId.toString(),
        "X-Dev-Roles"     to role,
    )

    private fun sampleLink(repoUrl: String = "https://github.com/org/repo") = WorkloadGithubLink(
        repoUrl         = repoUrl,
        serviceYamlPath = ".titlis/service.yaml",
    )

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test
    fun `GET github-link retorna linked true com repo_url quando vinculado`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo  = mockk<AiConfigRepository>(relaxed = true)
        val authenticator = testRequestAuthenticator()

        coEvery { scorecardRepo.getGithubLink("uid-abc", 1L) } returns sampleLink()

        application {
            installTestSecurity(authenticator)
            gitHubLinkRoutes(scorecardRepo, aiConfigRepo)
        }

        val response = client.get("/v1/workloads/uid-abc/github-link") {
            authenticatedHeaders().forEach { (k, v) -> header(k, v) }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["linked"]!!.jsonPrimitive.boolean, "linked deve ser true")
        assertEquals("https://github.com/org/repo", body["repo_url"]!!.jsonPrimitive.content)
        assertEquals(".titlis/service.yaml", body["service_yaml_path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET github-link retorna linked false quando nao vinculado`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo  = mockk<AiConfigRepository>(relaxed = true)
        val authenticator = testRequestAuthenticator()

        coEvery { scorecardRepo.getGithubLink("uid-no-link", 1L) } returns null

        application {
            installTestSecurity(authenticator)
            gitHubLinkRoutes(scorecardRepo, aiConfigRepo)
        }

        val response = client.get("/v1/workloads/uid-no-link/github-link") {
            authenticatedHeaders().forEach { (k, v) -> header(k, v) }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertFalse(body["linked"]!!.jsonPrimitive.boolean, "linked deve ser false quando nao vinculado")
    }

    @Test
    fun `GET github-link retorna 401 sem autenticacao`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo  = mockk<AiConfigRepository>(relaxed = true)
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            gitHubLinkRoutes(scorecardRepo, aiConfigRepo)
        }

        val response = client.get("/v1/workloads/uid-abc/github-link")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    @Test
    fun `POST github-link salva vinculo e retorna linked true`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo  = mockk<AiConfigRepository>(relaxed = true)
        val authenticator = testRequestAuthenticator()

        coEvery { aiConfigRepo.getByTenant(1L)?.githubTokenEnc } returns "ghp_test_token"
        coEvery { aiConfigRepo.getByTenant(1L) } returns mockk {
            coEvery { githubTokenEnc } returns "ghp_test_token"
        }
        coEvery { scorecardRepo.setGithubLink(any(), any(), any(), any()) } returns Unit

        application {
            installTestSecurity(authenticator)
            gitHubLinkRoutes(scorecardRepo, aiConfigRepo)
        }

        val response = client.post("/v1/workloads/uid-abc/github-link") {
            authenticatedHeaders().forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody("""{"repoUrl":"https://github.com/org/repo","serviceYamlPath":".titlis/service.yaml"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["linked"]!!.jsonPrimitive.boolean, "linked deve ser true no response do POST")
        assertEquals("https://github.com/org/repo", body["repo_url"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { scorecardRepo.setGithubLink("uid-abc", 1L, "https://github.com/org/repo", ".titlis/service.yaml") }
    }

    // ── DELETE ───────────────────────────────────────────────────────────────

    @Test
    fun `DELETE github-link remove vinculo e retorna 204`() = testApplication {
        val scorecardRepo = mockk<ScorecardRepository>()
        val aiConfigRepo  = mockk<AiConfigRepository>(relaxed = true)
        val authenticator = testRequestAuthenticator()

        coEvery { scorecardRepo.setGithubLink("uid-abc", 1L, null, null) } returns Unit

        application {
            installTestSecurity(authenticator)
            gitHubLinkRoutes(scorecardRepo, aiConfigRepo)
        }

        val response = client.delete("/v1/workloads/uid-abc/github-link") {
            authenticatedHeaders().forEach { (k, v) -> header(k, v) }
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify(exactly = 1) { scorecardRepo.setGithubLink("uid-abc", 1L, null, null) }
    }
}
