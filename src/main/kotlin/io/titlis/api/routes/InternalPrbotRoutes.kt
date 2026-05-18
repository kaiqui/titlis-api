package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.ScorecardRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Application.internalPrbotRoutes(
    scorecardRepo: ScorecardRepository,
    aiConfigRepo: AiConfigRepository,
    internalSecret: String,
) {
    routing {
        get("/v1/internal/prbot/findings") {
            val secret = call.request.headers["X-Internal-Secret"]
            if (secret != internalSecret) {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "forbidden") })
                return@get
            }
            val tenantId = call.request.queryParameters["tenant_id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenant_id required") })
            val ruleId = call.request.queryParameters["rule_id"] ?: "PERF-004"
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceAtMost(500) ?: 100

            val findings = scorecardRepo.getOpenFindingsByRule(tenantId, ruleId, limit)
            call.respond(HttpStatusCode.OK, findings)
        }

        get("/v1/internal/prbot/github-token") {
            val secret = call.request.headers["X-Internal-Secret"]
            if (secret != internalSecret) {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "forbidden") })
                return@get
            }
            val tenantId = call.request.queryParameters["tenant_id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenant_id required") })

            val config = aiConfigRepo.getByTenant(tenantId)
            if (config?.githubTokenEnc.isNullOrBlank()) {
                call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "no_github_token") })
                return@get
            }
            call.respond(HttpStatusCode.OK, buildJsonObject { put("token", config!!.githubTokenEnc!!) })
        }
    }
}
