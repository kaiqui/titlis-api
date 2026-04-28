package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.domain.UdpEnvelope
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.SloRepository
import io.titlis.api.udp.EventRouter
import kotlinx.serialization.Serializable

@Serializable
data class ProposeChangeRequest(
    val field: String,
    val newValue: String,
    val oldValue: String,
)

@Serializable
data class MarkChangeFailedRequest(
    val error: String,
)

fun Application.operatorRoutes(
    sloRepo: SloRepository,
    apiKeyRepo: ApiKeyRepository,
    eventRouter: EventRouter,
    requestAuthenticator: RequestAuthenticator? = null,
) {
    routing {
        // Operator API-key-authenticated endpoints
        route("/v1/operator") {
            post("/events") {
                val rawKey = call.request.headers["X-Api-Key"]
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "missing_api_key"),
                    )
                val tenantId = apiKeyRepo.resolveByToken(rawKey)
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "invalid_api_key"),
                    )
                val envelope = runCatching { call.receive<UdpEnvelope>() }
                    .getOrElse {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "invalid_envelope"),
                        )
                    }
                eventRouter.routeHttp(envelope, tenantId, rawKey)
                call.respond(HttpStatusCode.Accepted)
            }

            get("/pending-slo-changes") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "invalid_api_key"),
                    )
                val changes = sloRepo.listPendingChanges(tenantId)
                call.respondJson(changes)
            }

            post("/pending-slo-changes/{id}/applied") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "invalid_api_key"),
                    )
                val id = call.parameters["id"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "id required"),
                    )
                val ok = sloRepo.markChangeApplied(id, tenantId)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }

            post("/pending-slo-changes/{id}/failed") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "invalid_api_key"),
                    )
                val id = call.parameters["id"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "id required"),
                    )
                val body = call.receive<MarkChangeFailedRequest>()
                val ok = sloRepo.markChangeFailed(id, body.error, tenantId)
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }
        }

        // JWT-authenticated endpoint (titlis-ai proposes threshold changes)
        route("/v1") {
            fun io.ktor.server.routing.Route.protectedEndpoints() {
                post("/slo-configs/{id}/propose-change") {
                    val principal = call.principal<AppPrincipal>()
                    val sloConfigId = call.parameters["id"]?.toLongOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "invalid_slo_config_id"),
                        )
                    val body = call.receive<ProposeChangeRequest>()
                    if (body.field !in listOf("target", "warning", "timeframe")) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "field must be target, warning, or timeframe"),
                        )
                    }
                    val change = sloRepo.proposeChange(
                        sloConfigId = sloConfigId,
                        tenantId = principal?.tenantId ?: 0,
                        field = body.field,
                        oldValue = body.oldValue,
                        newValue = body.newValue,
                        requestedBy = principal?.email ?: "unknown",
                    ) ?: return@post call.respond(HttpStatusCode.NotFound)
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

private suspend fun resolveApiKeyTenant(call: ApplicationCall, apiKeyRepo: ApiKeyRepository): Long? {
    val rawToken = call.request.headers["X-Api-Key"] ?: return null
    return apiKeyRepo.resolveByToken(rawToken)
}
