package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.domain.ScorecardEvaluatedEvent
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScorecardRepository

fun Application.internalScorecardRoutes(
    scorecardRepo: ScorecardRepository,
    remediationRepo: RemediationRepository,
    internalSecret: String,
) {
    routing {
        route("/v1/internal/scoreops") {
            post("/scorecard-evaluated") {
                val secret = call.request.headers["X-Internal-Secret"]
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }
                val event = call.receive<ScorecardEvaluatedEvent>()
                val tenantId = event.tenantId
                    ?: run { call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tenant_id required")); return@post }

                scorecardRepo.upsertScorecard(event, tenantId)
                if (event.validationResults.isNotEmpty()) {
                    val passedRuleIds = event.validationResults
                        .filter { it.passed }
                        .map { it.ruleId }
                        .toSet()
                    remediationRepo.autoResolveIfAllFixed(event.workloadId, tenantId, passedRuleIds)
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
