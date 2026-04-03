package io.titlis.api.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.response.respond

suspend fun ApplicationCall.requireRole(vararg allowedRoles: PlatformRole): AppPrincipal? {
    val principal = principal<AppPrincipal>()
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_or_invalid_token"))
        return null
    }

    if (allowedRoles.isNotEmpty() && principal.role !in allowedRoles.toSet()) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
        return null
    }

    return principal
}

suspend fun ApplicationCall.requireAdminPrincipal(): AppPrincipal? = requireRole(PlatformRole.ADMIN)
