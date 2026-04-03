package io.titlis.api.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.Principal
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import io.titlis.api.repository.AuthRepository

private const val APP_AUTH_PROVIDER = "app-auth"
private const val OKTA_JWT_PROVIDER = "okta-jwt"

class AppAuthenticationProvider(config: Config) : AuthenticationProvider(config) {
    private val authenticator = config.authenticator

    class Config(name: String?) : AuthenticationProvider.Config(name) {
        lateinit var authenticator: RequestAuthenticator
    }

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val principal = authenticator.authenticate(context.call)
        if (principal != null) {
            context.principal(principal)
            return
        }

        if (!authenticator.shouldProtect(context.call)) {
            return
        }

        context.challenge("app-auth", AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_or_invalid_token"))
            challenge.complete()
        }
    }
}

fun AuthenticationConfig.appAuth(
    authenticator: RequestAuthenticator,
    name: String = APP_AUTH_PROVIDER,
) {
    val provider = AppAuthenticationProvider(AppAuthenticationProvider.Config(name).apply {
        this.authenticator = authenticator
    })
    register(provider)
}

fun AuthenticationConfig.oktaJwtAuth(
    verifier: OktaTokenVerifier,
    authRepository: AuthRepository,
    name: String = OKTA_JWT_PROVIDER,
) {
    val issuer = verifier.issuer() ?: return
    val audience = verifier.audience() ?: return
    val jwkProvider = verifier.jwkProvider() ?: return

    jwt(name) {
        realm = "Titlis"
        verifier(jwkProvider, issuer) {
            withAudience(audience)
        }
        validate { credential ->
            val identity = verifier.payloadToIdentity(credential.payload) ?: return@validate null
            authRepository.resolveFederatedUser(identity)?.toPrincipal(AuthSource.OKTA)
        }
        challenge { _, _ ->
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_or_invalid_token"))
        }
    }
}

fun ApplicationCall.requireAuthenticatedPrincipal(): AppPrincipal? = principal<AppPrincipal>()

fun protectedProviderNames(vararg values: String?): Array<String> = values.filterNotNull().toTypedArray()
