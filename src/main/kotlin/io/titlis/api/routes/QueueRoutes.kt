package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.auth.requireRole
import io.titlis.api.domain.LinkQueueRequest
import io.titlis.api.repository.QueueRepository
import io.titlis.api.repository.ServiceDefinitionRepository

fun Application.queueRoutes(
    queueRepo: QueueRepository,
    serviceDefRepo: ServiceDefinitionRepository,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            route("/v1/queues") {
                get {
                    val principal = call.requireRole() ?: return@get
                    val queues = queueRepo.listQueues(principal.tenantId)
                    call.respond(queues)
                }

                // Serviços disponíveis para atribuição manual de uma fila (dropdown na UI).
                get("/services") {
                    val principal = call.requireRole() ?: return@get
                    call.respond(serviceDefRepo.listForTenant(principal.tenantId))
                }

                get("/{id}/scorecard") {
                    val principal = call.requireRole() ?: return@get
                    val queueId = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val detail = queueRepo.getQueueScorecard(principal.tenantId, queueId)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(detail)
                }

                get("/{id}/thresholds") {
                    val principal = call.requireRole() ?: return@get
                    val queueId = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val thresholds = queueRepo.getQueueThresholds(principal.tenantId, queueId)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(thresholds)
                }

                get("/{id}/suggestions") {
                    val principal = call.requireRole() ?: return@get
                    val queueId = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(queueRepo.getSuggestions(principal.tenantId, queueId))
                }

                // Atribuição manual de dono (admin) — confirma sugestão ou escolhe outro serviço.
                post("/{id}/link") {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val queueId = call.parameters["id"]?.toLongOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val req = runCatching { call.receive<LinkQueueRequest>() }
                        .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload")) }
                    val ok = queueRepo.linkQueue(principal.tenantId, queueId, req.serviceDefinitionId)
                    if (ok) call.respond(HttpStatusCode.NoContent)
                    else call.respond(HttpStatusCode.NotFound, mapOf("error" to "queue_or_service_not_found"))
                }
            }
        }
    }
}
