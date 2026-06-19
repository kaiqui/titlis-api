package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.domain.QueueEvaluatedEvent
import io.titlis.api.repository.QueueRepository
import org.slf4j.LoggerFactory

fun Application.internalQueueRoutes(
    queueRepo: QueueRepository,
    internalSecret: String,
) {
    val log = LoggerFactory.getLogger("InternalQueueRoutes")

    routing {
        route("/v1/internal/scoreops") {
            post("/queue-evaluated") {
                val secret = call.request.headers["X-Internal-Secret"]
                if (secret != internalSecret) {
                    log.warn("queue-evaluated: unauthorized — secret mismatch")
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }
                val event = runCatching { call.receive<QueueEvaluatedEvent>() }
                    .getOrElse { ex ->
                        log.warn("queue-evaluated: invalid payload — {}", ex.message)
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                        return@post
                    }
                log.info("queue-evaluated: received provider={} externalId={} tenant={} score={} status={}",
                    event.provider, event.externalId, event.tenantId, event.overallScore, event.complianceStatus)
                queueRepo.upsertQueueScorecard(event)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
