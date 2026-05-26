package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.repository.SloRepository
import kotlinx.serialization.Serializable

@Serializable
data class ProposeSloChangeRequest(
    val field: String,       // "target" | "warning" | "timeframe"
    val oldValue: String,
    val newValue: String,
)

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

                // Coverage view: all active workloads classified by SLO presence.
                // slo_status: "WITH_SLO" | "CANDIDATE" | "NO_DATADOG"
                get("/slos/coverage") {
                    val principal = call.principal<AppPrincipal>()
                    call.respondJson(repo.coverage(principal?.tenantId ?: 0))
                }

                get("/namespaces/{namespace}/slos/{name}") {
                    val principal = call.principal<AppPrincipal>()
                    val namespace = call.parameters["namespace"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "namespace required")
                    val name = call.parameters["name"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "name required")
                    val result = repo.getByName(namespace, name, principal?.tenantId ?: 0)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondJson(result)
                }

                // Propose a config change for a SLO (admin only).
                // Change is persisted in slo_config_pending_changes and applied
                // by the operator-go on its next polling cycle.
                post("/slos/{sloConfigId}/propose-change") {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val sloConfigId = call.parameters["sloConfigId"]?.toLongOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_slo_config_id"))
                    val req = call.receive<ProposeSloChangeRequest>()
                    val allowedFields = setOf("target", "warning", "timeframe")
                    if (req.field !in allowedFields) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "invalid_field", "allowed" to allowedFields.toList()),
                        )
                    }
                    if (req.newValue.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "new_value_required"))
                    }
                    val change = repo.proposeChange(
                        sloConfigId  = sloConfigId,
                        tenantId     = principal.tenantId,
                        field        = req.field,
                        oldValue     = req.oldValue,
                        newValue     = req.newValue,
                        requestedBy  = "user:${principal.email ?: principal.userId}",
                    ) ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "slo_config_not_found"))
                    call.respond(HttpStatusCode.Created, change)
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
