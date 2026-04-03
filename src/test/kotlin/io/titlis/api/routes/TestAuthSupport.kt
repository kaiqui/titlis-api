package io.titlis.api.routes

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.auth.Authentication
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.titlis.api.auth.appAuth
import io.titlis.api.auth.oktaJwtAuth
import io.mockk.mockk
import io.titlis.api.auth.LocalTokenService
import io.titlis.api.auth.OktaTokenVerifier
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.config.AuthConfig
import io.titlis.api.repository.AuthRepository
import kotlinx.serialization.json.Json

internal fun testAuthConfig(
    appEnv: String = "local",
    authMode: String = "mixed",
    devBypassEnabled: Boolean = true,
) = AuthConfig(
    appEnv = appEnv,
    issuer = "titlis-local",
    audience = "titlis-ui",
    accessTokenSecret = "test-secret",
    accessTokenTtlMinutes = 60,
    oktaIssuer = "https://example.okta.com/oauth2/default",
    oktaAudience = "api://titlis",
    oktaClientId = "client-id",
    authMode = authMode,
    devBypassEnabled = devBypassEnabled,
    devTenantId = 1,
    devUserEmail = "dev@titlis.local",
    devRoles = listOf("titlis.admin"),
)

internal fun testRequestAuthenticator(
    config: AuthConfig = testAuthConfig(),
    authRepository: AuthRepository = mockk(relaxed = true),
): RequestAuthenticator {
    return RequestAuthenticator(
        config = config,
        authRepository = authRepository,
        localTokenService = LocalTokenService(config),
        oktaTokenVerifier = OktaTokenVerifier(config),
    )
}

internal fun Application.installTestSecurity(
    authenticator: RequestAuthenticator,
    authRepository: AuthRepository = mockk(relaxed = true),
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(Authentication) {
        appAuth(authenticator)
        oktaJwtAuth(OktaTokenVerifier(testAuthConfig()), authRepository)
    }
}

internal fun testTokenService(
    config: AuthConfig = testAuthConfig(),
) = LocalTokenService(config)

internal fun legacyTestRequestAuthenticator(): RequestAuthenticator {
    val authConfig = AuthConfig(
        appEnv = "local",
        issuer = "titlis-local",
        audience = "titlis-ui",
        accessTokenSecret = "test-secret",
        accessTokenTtlMinutes = 60,
        oktaIssuer = "https://example.okta.com/oauth2/default",
        oktaAudience = "api://titlis",
        oktaClientId = "client-id",
        authMode = "mixed",
        devBypassEnabled = true,
        devTenantId = 1,
        devUserEmail = "dev@titlis.local",
        devRoles = listOf("titlis.admin"),
    )

    return testRequestAuthenticator(authConfig)
}
