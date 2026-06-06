package io.titlis.api.routes

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.auth.AuthenticatedUser
import io.titlis.api.auth.PlatformRole
import io.titlis.api.repository.AuthRepository
import io.titlis.api.repository.FavoriteRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteRoutesTest {

    private fun authenticatedSetup(userId: Long, tenantId: Long): Pair<io.titlis.api.auth.RequestAuthenticator, String> {
        val user = AuthenticatedUser(
            id = userId,
            tenantId = tenantId,
            tenantSlug = "test-tenant",
            tenantName = "Test Tenant",
            email = "dev@titlis.local",
            displayName = "Dev User",
            role = PlatformRole.ADMIN,
            authProvider = "local",
            onboardingCompleted = true,
        )
        val authRepo = mockk<AuthRepository>()
        coEvery { authRepo.getUser(userId) } returns user

        val config = testAuthConfig()
        val authenticator = testRequestAuthenticator(config, authRepo)
        val token = testTokenService(config).issue(user).value
        return Pair(authenticator, token)
    }

    @Test
    fun `POST favorite retorna 401 sem autenticacao`() = testApplication {
        val repo = mockk<FavoriteRepository>(relaxed = true)
        val authenticator = testRequestAuthenticator()
        application {
            installTestSecurity(authenticator)
            favoriteRoutes(repo, authenticator)
        }

        val response = client.post("/v1/workloads/uid-123/favorite")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST favorite retorna 401 com dev bypass pois userId e nulo`() = testApplication {
        val repo = mockk<FavoriteRepository>(relaxed = true)
        val authenticator = testRequestAuthenticator()
        application {
            installTestSecurity(authenticator)
            favoriteRoutes(repo, authenticator)
        }

        val response = client.post("/v1/workloads/uid-123/favorite") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST favorite chama repo add e retorna 200`() = testApplication {
        val repo = mockk<FavoriteRepository>(relaxed = true)
        val (authenticator, token) = authenticatedSetup(userId = 99L, tenantId = 1L)

        application {
            installTestSecurity(authenticator)
            favoriteRoutes(repo, authenticator)
        }

        val response = client.post("/v1/workloads/uid-xyz/favorite") {
            header("Authorization", "Bearer $token")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify(exactly = 1) { repo.add(99L, 1L, "uid-xyz") }
    }

    @Test
    fun `DELETE favorite chama repo remove e retorna 204`() = testApplication {
        val repo = mockk<FavoriteRepository>(relaxed = true)
        val (authenticator, token) = authenticatedSetup(userId = 99L, tenantId = 1L)

        application {
            installTestSecurity(authenticator)
            favoriteRoutes(repo, authenticator)
        }

        val response = client.delete("/v1/workloads/uid-xyz/favorite") {
            header("Authorization", "Bearer $token")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify(exactly = 1) { repo.remove(99L, 1L, "uid-xyz") }
    }

    @Test
    fun `DELETE favorite retorna 401 sem autenticacao`() = testApplication {
        val repo = mockk<FavoriteRepository>(relaxed = true)
        val authenticator = testRequestAuthenticator()
        application {
            installTestSecurity(authenticator)
            favoriteRoutes(repo, authenticator)
        }

        val response = client.delete("/v1/workloads/uid-123/favorite")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST favorite isolamento de tenant — userId e tenantId corretos passados ao repo`() = testApplication {
        val repo = mockk<FavoriteRepository>(relaxed = true)
        val (authenticator, token) = authenticatedSetup(userId = 55L, tenantId = 7L)

        application {
            installTestSecurity(authenticator)
            favoriteRoutes(repo, authenticator)
        }

        client.post("/v1/workloads/uid-abc/favorite") {
            header("Authorization", "Bearer $token")
        }

        coVerify(exactly = 1) { repo.add(55L, 7L, "uid-abc") }
    }
}
