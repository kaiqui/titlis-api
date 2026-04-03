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
import io.titlis.api.auth.TenantAuthIntegrationResponse
import io.titlis.api.auth.TenantAuthIntegrationValidationException
import io.titlis.api.repository.AuthRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsAuthRoutesTest {

    @Test
    fun `GET providers returns 401 when auth is missing`() = testApplication {
        val repo = mockk<AuthRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            settingsAuthRoutes(repo)
        }

        val response = client.get("/v1/settings/auth/providers")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET providers returns 403 for non admin role`() = testApplication {
        val repo = mockk<AuthRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            settingsAuthRoutes(repo)
        }

        val response = client.get("/v1/settings/auth/providers") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.viewer")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET providers returns data for admin role`() = testApplication {
        val repo = mockk<AuthRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { repo.listTenantAuthIntegrations(42) } returns listOf(
            sampleIntegration(id = 11),
        )

        application {
            installTestSecurity(authenticator)
            settingsAuthRoutes(repo)
        }

        val response = client.get("/v1/settings/auth/providers") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"id\":11"))
        coVerify(exactly = 1) { repo.listTenantAuthIntegrations(42) }
    }

    @Test
    fun `POST providers returns 400 for invalid payload domain rules`() = testApplication {
        val repo = mockk<AuthRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery {
            repo.upsertTenantAuthIntegration(
                tenantId = 42,
                configuredByUserId = null,
                request = any(),
            )
        } throws TenantAuthIntegrationValidationException("issuer_invalid")

        application {
            installTestSecurity(authenticator)
            settingsAuthRoutes(repo)
        }

        val response = client.post("/v1/settings/auth/providers") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "providerType": "okta",
                  "integrationName": "Okta SSO",
                  "issuerUrl": "bad-url",
                  "clientId": "0oa123",
                  "audience": "api://titlis",
                  "scopes": ["openid", "profile", "email"]
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("issuer_invalid"))
    }

    private fun sampleIntegration(id: Long) = TenantAuthIntegrationResponse(
        id = id,
        providerType = "okta",
        integrationKind = "sso_oidc",
        integrationName = "Okta SSO",
        isEnabled = true,
        isPrimary = false,
        issuerUrl = "https://company.okta.com/oauth2/default",
        clientId = "0oa123",
        audience = "api://titlis",
        scopes = listOf("openid", "profile", "email"),
        redirectUri = "http://localhost:13000/login/callback",
        postLogoutRedirectUri = "http://localhost:13000/login",
        verifiedAt = null,
        activatedAt = null,
        configuredByUserId = null,
        updatedAt = "2026-04-01T10:00:00Z",
    )
}
