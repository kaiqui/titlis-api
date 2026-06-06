package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.repository.FavoriteRepository

fun Application.favoriteRoutes(
    repo: FavoriteRepository,
    requestAuthenticator: RequestAuthenticator? = null,
) {
    routing {
        route("/v1") {
            fun Route.protectedEndpoints() {
                post("/workloads/{k8sUid}/favorite") {
                    val principal = call.principal<AppPrincipal>()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val userId = principal.userId
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val k8sUid = call.parameters["k8sUid"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    repo.add(userId, principal.tenantId, k8sUid)
                    call.respond(HttpStatusCode.NoContent)
                }

                delete("/workloads/{k8sUid}/favorite") {
                    val principal = call.principal<AppPrincipal>()
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                    val userId = principal.userId
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                    val k8sUid = call.parameters["k8sUid"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    repo.remove(userId, principal.tenantId, k8sUid)
                    call.respond(HttpStatusCode.NoContent)
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
