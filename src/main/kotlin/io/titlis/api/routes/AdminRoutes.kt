package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.PlatformRole
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.domain.UpdateUserRoleRequest
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

                patch("/users/{userId}/role") {
                    val principal = call.requireAdminPrincipal() ?: return@patch
                    val callerId = principal.userId
                        ?: return@patch call.respond(HttpStatusCode.Forbidden)
                    val userId = call.parameters["userId"]?.toLongOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest)
                    if (userId == callerId) {
                        return@patch call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "admin_cannot_change_own_role"),
                        )
                    }
                    val body = call.receive<UpdateUserRoleRequest>()
                    val newRole = PlatformRole.entries.firstOrNull { it.dbValue == body.role }
                        ?: return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "invalid_role"),
                        )
                    val updated = adminRepo.updateUserRole(userId, principal.tenantId, newRole, callerId)
                    if (!updated) return@patch call.respond(HttpStatusCode.NotFound)
                    call.respond(HttpStatusCode.OK, mapOf("ok" to true))
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
