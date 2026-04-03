package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.repository.SloRepository

fun Application.sloRoutes(
    repo: SloRepository,
    requestAuthenticator: RequestAuthenticator? = null,
) {
    routing {
        route("/v1") {
            fun Route.protectedEndpoints() {
                get("/slos") {
                    val principal = call.principal<AppPrincipal>()
                    val namespace = call.request.queryParameters["namespace"]
                    val cluster = call.request.queryParameters["cluster"]
                    call.respondJson(repo.list(principal?.tenantId ?: 0, namespace, cluster))
                }

                get("/namespaces/{namespace}/slos/{name}") {
                    val principal = call.principal<AppPrincipal>()
                    val namespace = call.parameters["namespace"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "namespace required",
                        )
                    val name = call.parameters["name"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "name required")
                    val result = repo.getByName(namespace, name, principal?.tenantId ?: 0)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondJson(result)
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
