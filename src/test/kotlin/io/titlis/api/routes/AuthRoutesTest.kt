package io.titlis.api.routes

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.titlis.api.auth.AuthenticatedUser
import io.titlis.api.auth.OktaIdentity
import io.titlis.api.auth.OktaTokenVerifier
import io.titlis.api.auth.PlatformRole
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.AuthRepository
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AuthRoutesTest {

    @Test
    fun `POST okta exchange returns local titlis session when id token is valid`() = testApplication {
        val authRepository = mockk<AuthRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>(relaxed = true)
        val oktaVerifier = mockk<OktaTokenVerifier>()
        val identity = OktaIdentity(
            subject = "00u123",
            email = "user@jeitto.com",
            tenantId = null,
            groups = listOf("Jeitto Confia - Viewer"),
            issuer = "https://jeitto.okta.com",
        )
        val user = AuthenticatedUser(
            id = 7,
            tenantId = 42,
            tenantSlug = "jeitto",
            tenantName = "Jeitto",
            email = "user@jeitto.com",
            displayName = "User",
            role = PlatformRole.VIEWER,
            authProvider = "okta",
            onboardingCompleted = true,
        )
        every { oktaVerifier.verifyIdToken("okta-id-token") } returns identity
        coEvery { authRepository.resolveFederatedUser(identity, "jeitto") } returns user
        val authenticator = testRequestAuthenticator(authRepository = authRepository)

        application {
            installTestSecurity(authenticator, authRepository)
            authRoutes(
                repo = authRepository,
                tokenService = testTokenService(),
                requestAuthenticator = authenticator,
                apiKeyRepo = apiKeyRepo,
                oktaTokenVerifier = oktaVerifier,
            )
        }

        val response = client.post("/v1/auth/okta/exchange") {
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"okta-id-token","tenantSlug":"jeitto"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "\"authProvider\":\"okta\"")
        assertContains(response.bodyAsText(), "\"accessToken\":\"")
    }

    @Test
    fun `POST okta exchange returns 401 when id token is invalid`() = testApplication {
        val authRepository = mockk<AuthRepository>(relaxed = true)
        val apiKeyRepo = mockk<ApiKeyRepository>(relaxed = true)
        val oktaVerifier = mockk<OktaTokenVerifier>()
        every { oktaVerifier.verifyIdToken(any()) } returns null
        val authenticator = testRequestAuthenticator(authRepository = authRepository)

        application {
            installTestSecurity(authenticator, authRepository)
            authRoutes(
                repo = authRepository,
                tokenService = testTokenService(),
                requestAuthenticator = authenticator,
                apiKeyRepo = apiKeyRepo,
                oktaTokenVerifier = oktaVerifier,
            )
        }

        val response = client.post("/v1/auth/okta/exchange") {
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"bad-token","tenantSlug":"jeitto"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertContains(response.bodyAsText(), "invalid_okta_id_token")
    }
}
