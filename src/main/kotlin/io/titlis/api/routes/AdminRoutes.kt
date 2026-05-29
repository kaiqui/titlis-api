package io.titlis.api.routes

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.repository.AdminRepository

fun Application.adminRoutes(
    adminRepo: AdminRepository,
    requestAuthenticator: io.titlis.api.auth.RequestAuthenticator? = null,
) {
    routing {
        route("/v1/admin") {
            fun io.ktor.server.routing.Route.protectedEndpoints() {
                get("/overview") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val overview = adminRepo.getOverview(principal.tenantId)
                    call.respond(overview)
                }

                get("/users") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val users = adminRepo.listUsers(principal.tenantId)
                    call.respond(users)
                }
            }

            if (requestAuthenticator == null) {
                protectedEndpoints()
            } else {
                authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
                    protectedEndpoints()
                }
            }
        }
    }
}
