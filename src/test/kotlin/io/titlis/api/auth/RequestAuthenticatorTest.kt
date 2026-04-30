package io.titlis.api.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.titlis.api.repository.AuthRepository
import io.titlis.api.routes.installTestSecurity
import io.titlis.api.routes.testAuthConfig
import io.titlis.api.routes.testRequestAuthenticator
import io.titlis.api.routes.testTokenService
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestAuthenticatorTest {

    @Test
    fun `returns auth disabled principal when auth mode is disabled`() = testApplication {
        val authenticator = testRequestAuthenticator(
            config = testAuthConfig(
                authMode = "disabled",
                appEnv = "local",
            ),
        )

        application {
            installTestSecurity(authenticator)
            routing {
                authenticate("app-auth", "okta-jwt") {
                    get("/protected") {
                        val principal = call.principal<AppPrincipal>()
                        call.respond("${principal?.source}:${principal?.tenantId}")
                    }
                }
            }
        }

        val response = client.get("/protected")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("AUTH_DISABLED:1", response.bodyAsText())
    }

    @Test
    fun `accepts dev bypass only in local environment`() = testApplication {
        val localAuthenticator = testRequestAuthenticator(
            config = testAuthConfig(
                authMode = "mixed",
                appEnv = "local",
            ),
        )

        application {
            installTestSecurity(localAuthenticator)
            routing {
                authenticate("app-auth", "okta-jwt") {
                    get("/protected") {
                        val principal = call.principal<AppPrincipal>()
                        call.respond("${principal?.source}:${principal?.tenantId}:${principal?.role?.dbValue}")
                    }
                }
            }
        }

        val response = client.get("/protected") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
            header("X-Dev-User", "qa@titlis.local")
            header("X-Dev-Roles", "titlis.viewer")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("DEV_BYPASS:42:viewer", response.bodyAsText())
    }

    @Test
    fun `rejects dev bypass outside local environment`() = testApplication {
        val authenticator = testRequestAuthenticator(
            config = testAuthConfig(
                authMode = "mixed",
                appEnv = "dev",
            ),
        )

        application {
            installTestSecurity(authenticator)
            routing {
                authenticate("app-auth", "okta-jwt") {
                    get("/protected") {
                        call.respond("ok")
                    }
                }
            }
        }

        val response = client.get("/protected") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "42")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `accepts local token and resolves stored user`() = testApplication {
        val authConfig = testAuthConfig(authMode = "mixed")
        val tokenService = testTokenService(authConfig)
        val authRepository = mockk<AuthRepository>()
        val user = AuthenticatedUser(
            id = 99,
            tenantId = 7,
            tenantSlug = "tenant-7",
            tenantName = "Tenant 7",
            email = "local@titlis.dev",
            displayName = "Local User",
            role = PlatformRole.ADMIN,
            authProvider = "local",
            onboardingCompleted = true,
        )
        coEvery { authRepository.getUser(99) } returns user

        val authenticator = testRequestAuthenticator(authConfig, authRepository)

        application {
            installTestSecurity(authenticator, authRepository)
            routing {
                authenticate("app-auth", "okta-jwt") {
                    get("/protected") {
                        val principal = call.principal<AppPrincipal>()
                        call.respond("${principal?.source}:${principal?.tenantId}:${principal?.email}")
                    }
                }
            }
        }

        val token = tokenService.issue(user).value
        val response = client.get("/protected") {
            header("Authorization", "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("LOCAL:7:local@titlis.dev", response.bodyAsText())
    }

    @Test
    fun `falls back to viewer when okta token has no accepted groups`() = testApplication {
        val authConfig = testAuthConfig(authMode = "okta")
        val authRepository = mockk<AuthRepository>()
        val oktaTokenVerifier = mockk<OktaTokenVerifier>()
        val identity = OktaIdentity(
            subject = "okta-user",
            email = "user@jeitto.com",
            tenantId = 42,
            groups = emptyList(),
            issuer = "https://example.okta.com/oauth2/default",
        )
        val user = AuthenticatedUser(
            id = 7,
            tenantId = 42,
            tenantSlug = "tenant-42",
            tenantName = "Tenant 42",
            email = "user@jeitto.com",
            displayName = "Federated User",
            role = PlatformRole.ADMIN,
            authProvider = "okta",
            onboardingCompleted = true,
        )

        every { oktaTokenVerifier.verify("okta-token") } returns identity
        coEvery { authRepository.resolveFederatedUser(identity, any()) } returns user

        val authenticator = RequestAuthenticator(
            config = authConfig,
            authRepository = authRepository,
            localTokenService = testTokenService(authConfig),
            oktaTokenVerifier = oktaTokenVerifier,
        )

        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Authentication) {
                appAuth(authenticator)
            }
            routing {
                authenticate("app-auth") {
                    get("/protected") {
                        val principal = call.principal<AppPrincipal>()
                        call.respond("${principal?.source}:${principal?.tenantId}:${principal?.role?.dbValue}")
                    }
                }
            }
        }

        val response = client.get("/protected") {
            header("Authorization", "Bearer okta-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OKTA:42:viewer", response.bodyAsText())
        coVerify(exactly = 1) { authRepository.resolveFederatedUser(identity, null) }
    }

    @Test
    fun `uses okta group claim to override principal role`() = testApplication {
        val authConfig = testAuthConfig(authMode = "okta")
        val authRepository = mockk<AuthRepository>()
        val oktaTokenVerifier = mockk<OktaTokenVerifier>()
        val identity = OktaIdentity(
            subject = "okta-user",
            email = "user@jeitto.com",
            tenantId = 42,
            groups = listOf("Jeitto Confia - Admin"),
            issuer = "https://example.okta.com/oauth2/default",
        )
        val user = AuthenticatedUser(
            id = 7,
            tenantId = 42,
            tenantSlug = "tenant-42",
            tenantName = "Tenant 42",
            email = "user@jeitto.com",
            displayName = "Federated User",
            role = PlatformRole.VIEWER,
            authProvider = "okta",
            onboardingCompleted = true,
        )

        every { oktaTokenVerifier.verify("okta-token") } returns identity
        coEvery { authRepository.resolveFederatedUser(identity, any()) } returns user

        val authenticator = RequestAuthenticator(
            config = authConfig,
            authRepository = authRepository,
            localTokenService = testTokenService(authConfig),
            oktaTokenVerifier = oktaTokenVerifier,
        )

        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Authentication) {
                appAuth(authenticator)
            }
            routing {
                authenticate("app-auth") {
                    get("/protected") {
                        val principal = call.principal<AppPrincipal>()
                        call.respond("${principal?.source}:${principal?.tenantId}:${principal?.role?.dbValue}")
                    }
                }
            }
        }

        val response = client.get("/protected") {
            header("Authorization", "Bearer okta-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OKTA:42:admin", response.bodyAsText())
        coVerify(exactly = 1) { authRepository.resolveFederatedUser(identity, null) }
    }

    @Test
    fun `passes tenant slug header as federated user hint`() = testApplication {
        val authConfig = testAuthConfig(authMode = "okta")
        val authRepository = mockk<AuthRepository>()
        val oktaTokenVerifier = mockk<OktaTokenVerifier>()
        val identity = OktaIdentity(
            subject = "okta-user",
            email = "user@jeitto.com",
            tenantId = null,
            groups = listOf("Jeitto Confia - Viewer"),
            issuer = "https://example.okta.com",
        )
        val user = AuthenticatedUser(
            id = 7,
            tenantId = 42,
            tenantSlug = "jeitto",
            tenantName = "Jeitto",
            email = "user@jeitto.com",
            displayName = "Federated User",
            role = PlatformRole.VIEWER,
            authProvider = "okta",
            onboardingCompleted = true,
        )

        every { oktaTokenVerifier.verify("okta-token") } returns identity
        coEvery { authRepository.resolveFederatedUser(identity, "jeitto") } returns user

        val authenticator = RequestAuthenticator(
            config = authConfig,
            authRepository = authRepository,
            localTokenService = testTokenService(authConfig),
            oktaTokenVerifier = oktaTokenVerifier,
        )

        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(Authentication) {
                appAuth(authenticator)
            }
            routing {
                authenticate("app-auth") {
                    get("/protected") {
                        val principal = call.principal<AppPrincipal>()
                        call.respond("${principal?.source}:${principal?.tenantSlug}")
                    }
                }
            }
        }

        val response = client.get("/protected") {
            header("Authorization", "Bearer okta-token")
            header(RequestAuthenticator.TENANT_SLUG_HEADER, "jeitto")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OKTA:jeitto", response.bodyAsText())
    }
}
