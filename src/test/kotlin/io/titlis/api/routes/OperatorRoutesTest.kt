package io.titlis.api.routes

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
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.SloRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

private const val VALID_API_KEY = "tls_k_valid123"
private const val TENANT_ID = 7L

class OperatorRoutesGetPendingChangesTest {

    @Test
    fun `GET pending-slo-changes returns list on valid api key`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_API_KEY) } returns TENANT_ID
        coEvery { sloRepo.listPendingChanges(TENANT_ID) } returns listOf(
            mapOf(
                "id" to "550e8400-e29b-41d4-a716-446655440000",
                "slo_config_name" to "payments-slo",
                "namespace" to "payments",
                "field" to "target",
                "old_value" to "99.9",
                "new_value" to "99.5",
                "requested_by" to "titlis-ai",
                "status" to "pending",
                "created_at" to "2026-04-17T10:00:00Z",
            ),
        )

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.get("/v1/operator/pending-slo-changes") {
            header("X-Api-Key", VALID_API_KEY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { sloRepo.listPendingChanges(TENANT_ID) }
        assertContains(response.bodyAsText(), "payments-slo")
    }

    @Test
    fun `GET pending-slo-changes returns 401 when api key is missing`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.get("/v1/operator/pending-slo-changes")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET pending-slo-changes returns 401 on invalid api key`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(any()) } returns null

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.get("/v1/operator/pending-slo-changes") {
            header("X-Api-Key", "bad-key")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET pending-slo-changes returns empty list when nothing pending`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_API_KEY) } returns TENANT_ID
        coEvery { sloRepo.listPendingChanges(TENANT_ID) } returns emptyList()

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.get("/v1/operator/pending-slo-changes") {
            header("X-Api-Key", VALID_API_KEY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }
}

class OperatorRoutesMarkAppliedTest {

    @Test
    fun `POST applied returns 200 on success`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        val changeId = "550e8400-e29b-41d4-a716-446655440001"
        coEvery { apiKeyRepo.resolveByToken(VALID_API_KEY) } returns TENANT_ID
        coEvery { sloRepo.markChangeApplied(changeId, TENANT_ID) } returns true

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/operator/pending-slo-changes/$changeId/applied") {
            header("X-Api-Key", VALID_API_KEY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { sloRepo.markChangeApplied(changeId, TENANT_ID) }
    }

    @Test
    fun `POST applied returns 404 when change not found`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        val changeId = "550e8400-e29b-41d4-a716-446655440002"
        coEvery { apiKeyRepo.resolveByToken(VALID_API_KEY) } returns TENANT_ID
        coEvery { sloRepo.markChangeApplied(changeId, TENANT_ID) } returns false

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/operator/pending-slo-changes/$changeId/applied") {
            header("X-Api-Key", VALID_API_KEY)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST applied returns 401 when api key is missing`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/operator/pending-slo-changes/some-id/applied")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}

class OperatorRoutesMarkFailedTest {

    @Test
    fun `POST failed returns 200 on success`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        val changeId = "550e8400-e29b-41d4-a716-446655440003"
        coEvery { apiKeyRepo.resolveByToken(VALID_API_KEY) } returns TENANT_ID
        coEvery { sloRepo.markChangeFailed(changeId, "patch rejected by K8s", TENANT_ID) } returns true

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/operator/pending-slo-changes/$changeId/failed") {
            header("X-Api-Key", VALID_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"error":"patch rejected by K8s"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { sloRepo.markChangeFailed(changeId, "patch rejected by K8s", TENANT_ID) }
    }

    @Test
    fun `POST failed returns 404 when change not found or already applied`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        val changeId = "550e8400-e29b-41d4-a716-446655440004"
        coEvery { apiKeyRepo.resolveByToken(VALID_API_KEY) } returns TENANT_ID
        coEvery { sloRepo.markChangeFailed(changeId, any(), TENANT_ID) } returns false

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/operator/pending-slo-changes/$changeId/failed") {
            header("X-Api-Key", VALID_API_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"error":"timeout"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST failed returns 401 when api key is missing`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/operator/pending-slo-changes/some-id/failed") {
            contentType(ContentType.Application.Json)
            setBody("""{"error":"x"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}

class OperatorRoutesProposeChangeTest {

    @Test
    fun `POST propose-change returns 201 with dev bypass`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { sloRepo.proposeChange(10L, 42L, "target", "99.9", "99.5", "dev@titlis.local") } returns mapOf(
            "id" to "550e8400-e29b-41d4-a716-446655440005",
            "slo_config_name" to "payments-slo",
            "namespace" to "payments",
            "field" to "target",
            "old_value" to "99.9",
            "new_value" to "99.5",
            "requested_by" to "dev@titlis.local",
            "status" to "pending",
            "created_at" to "2026-04-17T10:00:00Z",
        )

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/slo-configs/10/propose-change") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody("""{"field":"target","newValue":"99.5","oldValue":"99.9"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        coVerify(exactly = 1) { sloRepo.proposeChange(10L, 42L, "target", "99.9", "99.5", "dev@titlis.local") }
    }

    @Test
    fun `POST propose-change returns 404 when slo-config not found or wrong tenant`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { sloRepo.proposeChange(99L, any(), any(), any(), any(), any()) } returns null

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/slo-configs/99/propose-change") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.viewer")
            contentType(ContentType.Application.Json)
            setBody("""{"field":"warning","newValue":"99.0","oldValue":"98.5"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST propose-change returns 400 on invalid field`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/slo-configs/10/propose-change") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody("""{"field":"unknown_field","newValue":"99.5","oldValue":"99.9"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "field must be")
    }

    @Test
    fun `POST propose-change returns 400 on non-numeric slo-config id`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/slo-configs/not-a-number/propose-change") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody("""{"field":"target","newValue":"99.5","oldValue":"99.9"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST propose-change returns 401 when unauthenticated`() = testApplication {
        val sloRepo = mockk<SloRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()

        application {
            installTestSecurity(testRequestAuthenticator())
            operatorRoutes(sloRepo, apiKeyRepo, testRequestAuthenticator())
        }

        val response = client.post("/v1/slo-configs/10/propose-change") {
            contentType(ContentType.Application.Json)
            setBody("""{"field":"target","newValue":"99.5","oldValue":"99.9"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
