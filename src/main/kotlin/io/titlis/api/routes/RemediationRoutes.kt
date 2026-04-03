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
import io.titlis.api.repository.RemediationRepository

fun Application.remediationRoutes(
    repo: RemediationRepository,
    requestAuthenticator: RequestAuthenticator? = null,
) {
    routing {
        route("/v1") {
            fun Route.protectedEndpoints() {
                get("/workloads/{workloadId}/remediation") {
                    val principal = call.principal<AppPrincipal>()
                    val k8sUid = call.parameters["workloadId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "workloadId required")
                    val result = repo.getByWorkload(k8sUid, principal?.tenantId ?: 0)
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
