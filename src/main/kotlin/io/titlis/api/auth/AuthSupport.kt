package io.titlis.api.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

fun ApplicationCall.authContextOrNull(tokenService: LocalTokenService): AuthenticatedRequestContext? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val token = header.removePrefix("Bearer ").trim()
    if (token.isBlank()) return null

    val payload = tokenService.verify(token) ?: return null
    val userId = payload.sub.toLongOrNull() ?: return null

    return AuthenticatedRequestContext(
        userId = userId,
        tenantId = payload.tenantId,
        role = PlatformRole.fromDb(payload.role),
        authProvider = payload.authProvider,
    )
}

suspend fun ApplicationCall.requireAuth(tokenService: LocalTokenService): AuthenticatedRequestContext? {
    val authContext = authContextOrNull(tokenService)
    if (authContext == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_or_invalid_token"))
        return null
    }
    return authContext
}

data class AuthenticatedRequestContext(
    val userId: Long,
    val tenantId: Long,
    val role: PlatformRole,
    val authProvider: String,
)

suspend fun ApplicationCall.requireAppPrincipal(authenticator: RequestAuthenticator?): AppPrincipal? {
    if (authenticator == null || !authenticator.shouldProtect(this)) {
        return null
    }

    val principal = authenticator.authenticate(this)
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_or_invalid_token"))
        return null
    }

    return principal
}
