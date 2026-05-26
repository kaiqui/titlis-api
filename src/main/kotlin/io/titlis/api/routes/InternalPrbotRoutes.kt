package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.FindingsResponse
import io.titlis.api.repository.ScorecardRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Endpoints internos mantidos para consulta de findings e GitHub token —
// úteis para o titlis-ai via MCP GitHub para saber quais workloads precisam
// de remediação. Os endpoints de geração de patch e eventos do prbot foram removidos.

fun Application.internalPrbotRoutes(
    scorecardRepo: ScorecardRepository,
    aiConfigRepo: AiConfigRepository,
    internalSecret: String,
) {
    routing {
        // Lista findings abertos por regra — usado pelo titlis-ai para contextualizar remediações.
        get("/v1/internal/prbot/findings") {
            val secret = call.request.headers["X-Internal-Secret"]
            if (secret != internalSecret) {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "forbidden") })
                return@get
            }
            val tenantId = call.request.queryParameters["tenant_id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenant_id required") })
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceAtMost(500) ?: 100

            val ruleIdsParam = call.request.queryParameters["rule_ids"]
            if (ruleIdsParam != null) {
                val ruleIds = ruleIdsParam.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (ruleIds.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "rule_ids cannot be empty") })
                    return@get
                }
                val findings = scorecardRepo.getOpenFindingsByRules(tenantId, ruleIds, limit)
                call.respond(HttpStatusCode.OK, FindingsResponse(findings))
            } else {
                val ruleId = call.request.queryParameters["rule_id"] ?: "PERF-004"
                val findings = scorecardRepo.getOpenFindingsByRule(tenantId, ruleId, limit)
                call.respond(HttpStatusCode.OK, FindingsResponse(findings))
            }
        }

        // Retorna o GitHub token do tenant — usado pelo titlis-ai para inicializar MCP GitHub.
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
