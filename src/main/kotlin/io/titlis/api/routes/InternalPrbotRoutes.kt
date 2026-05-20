package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.domain.UdpEnvelope
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.FindingsResponse
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.udp.EventRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val json = Json { ignoreUnknownKeys = true }

private val aiHttpClient: HttpClient = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(10))
    .build()

fun Application.internalPrbotRoutes(
    scorecardRepo: ScorecardRepository,
    aiConfigRepo: AiConfigRepository,
    internalSecret: String,
    eventRouter: EventRouter,
    aiServiceUrl: String = "",
    aiServiceSecret: String = "",
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

        post("/v1/internal/prbot/generate-manifest-patch") {
            val secret = call.request.headers["X-Internal-Secret"]
            if (secret != internalSecret) {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "forbidden") })
                return@post
            }
            if (aiServiceUrl.isBlank()) {
                call.respond(HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", "ai_service_not_configured") })
                return@post
            }
            val body = call.receiveText()

            // Inject the tenant's AI config into the request before forwarding to titlis-ai.
            val tenantId = runCatching {
                json.parseToJsonElement(body).jsonObject["tenant_id"]?.jsonPrimitive?.longOrNull
            }.getOrNull()

            val enrichedBody = if (tenantId != null) {
                val aiConfig = aiConfigRepo.getByTenant(tenantId)
                if (aiConfig != null && aiConfig.apiKeyEnc.isNotBlank()) {
                    val parsed = json.parseToJsonElement(body).jsonObject
                    val mutable = parsed.toMutableMap()
                    mutable["ai_config"] = buildJsonObject {
                        put("api_key", aiConfig.apiKeyEnc)
                        put("provider", aiConfig.provider)
                        put("model", aiConfig.model)
                    }
                    JsonObject(mutable).toString()
                } else body
            } else body

            val resp = withContext(Dispatchers.IO) {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$aiServiceUrl/v1/prbot/generate-manifest-patch"))
                    .header("X-Internal-Secret", aiServiceSecret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(enrichedBody))
                    .timeout(Duration.ofMinutes(3))
                    .build()
                aiHttpClient.send(req, HttpResponse.BodyHandlers.ofString())
            }

            call.response.headers.append("Content-Type", "application/json")
            call.respondText(resp.body(), ContentType.Application.Json, HttpStatusCode.fromValue(resp.statusCode()))
        }

        post("/v1/internal/prbot/events") {
            val secret = call.request.headers["X-Internal-Secret"]
            if (secret != internalSecret) {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "forbidden") })
                return@post
            }
            val body = call.receiveText()
            val envelope = runCatching { json.decodeFromString<UdpEnvelope>(body) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "invalid envelope") })
                return@post
            }
            val tenantId = envelope.tenantId ?: run {
                call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenant_id required") })
                return@post
            }
            eventRouter.routeFromPrbot(envelope, tenantId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
