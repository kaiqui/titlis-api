package io.titlis.api.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireRole
import io.titlis.api.config.AppConfig
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.TenantAiConfigRecord
import io.titlis.api.repository.ScorecardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Serializable
data class RemediateFindingsRequest(
    val findingIds: List<String>,
    val repoUrl: String,
    val deployManifestPath: String = "manifests/kubernetes/main/deploy.yaml",
)

@Serializable
data class ConfirmRemediationRequest(
    val approved: Boolean,
)

@Serializable
data class ExplainFindingRequest(
    val pillar: String,
    val severity: String,
    val actualValue: String? = null,
    val expectedValue: String? = null,
    val deploymentName: String,
    val namespace: String,
    val containerName: String? = null,
)

fun Application.aiRoutes(
    scorecardRepo: ScorecardRepository,
    aiConfigRepo: AiConfigRepository,
    appConfig: AppConfig,
    requestAuthenticator: RequestAuthenticator? = null,
    httpClient: HttpClient = HttpClient.newHttpClient(),
) {

    routing {
        route("/v1/ai") {
            fun Route.protectedEndpoints() {
                post("/workloads/{workloadId}/remediate") {
                    val principal = call.requireRole() ?: return@post
                    val workloadId = call.parameters["workloadId"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "workloadId_required"),
                        )
                    val body = call.receive<RemediateFindingsRequest>()

                    scorecardRepo.getByWorkloadId(workloadId, principal.tenantId)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "workload_not_found"),
                        )

                    val aiConfig = aiConfigRepo.getByTenant(principal.tenantId)
                        ?: return@post call.respond(
                            HttpStatusCode(424, "Failed Dependency"),
                            mapOf("error" to "ai_not_configured"),
                        )

                    val aiPayload = buildRemediatePayload(
                        tenantId = principal.tenantId,
                        workloadId = workloadId,
                        body = body,
                        aiConfig = aiConfig,
                    )

                    val aiRequest = HttpRequest.newBuilder()
                        .uri(URI.create("${appConfig.aiService.url}/v1/remediate"))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Secret", appConfig.aiService.internalSecret)
                        .POST(HttpRequest.BodyPublishers.ofString(aiPayload))
                        .build()

                    call.response.headers.append("Cache-Control", "no-cache")
                    call.response.headers.append("X-Accel-Buffering", "no")
                    call.respondBytesWriter(contentType = ContentType.parse("text/event-stream")) {
                        val channel = this
                        withContext(Dispatchers.IO) {
                            val response = httpClient.send(aiRequest, HttpResponse.BodyHandlers.ofInputStream())
                            val buffer = ByteArray(8192)
                            response.body().use { stream ->
                                var read = stream.read(buffer)
                                while (read >= 0) {
                                    channel.writeFully(buffer, 0, read)
                                    channel.flush()
                                    read = stream.read(buffer)
                                }
                            }
                        }
                    }
                }

                post("/remediate/{threadId}/confirm") {
                    call.requireRole() ?: return@post
                    val threadId = call.parameters["threadId"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "threadId_required"),
                        )
                    val body = call.receive<ConfirmRemediationRequest>()

                    val aiPayload = """{"approved":${body.approved}}"""

                    val aiRequest = HttpRequest.newBuilder()
                        .uri(URI.create("${appConfig.aiService.url}/v1/remediate/$threadId/confirm"))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Secret", appConfig.aiService.internalSecret)
                        .POST(HttpRequest.BodyPublishers.ofString(aiPayload))
                        .build()

                    call.response.headers.append("Cache-Control", "no-cache")
                    call.response.headers.append("X-Accel-Buffering", "no")
                    call.respondBytesWriter(contentType = ContentType.parse("text/event-stream")) {
                        val channel = this
                        withContext(Dispatchers.IO) {
                            val response = httpClient.send(aiRequest, HttpResponse.BodyHandlers.ofInputStream())
                            val buffer = ByteArray(8192)
                            response.body().use { stream ->
                                var read = stream.read(buffer)
                                while (read >= 0) {
                                    channel.writeFully(buffer, 0, read)
                                    channel.flush()
                                    read = stream.read(buffer)
                                }
                            }
                        }
                    }
                }

                post("/workloads/{workloadId}/findings/{ruleId}/explain") {
                    val principal = call.requireRole() ?: return@post
                    val workloadId = call.parameters["workloadId"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "workloadId_required"),
                        )
                    val ruleId = call.parameters["ruleId"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "ruleId_required"),
                        )
                    val body = call.receive<ExplainFindingRequest>()

                    // Validate workload belongs to tenant (→ 404 if not found or wrong tenant)
                    scorecardRepo.getByWorkloadId(workloadId, principal.tenantId)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "workload_not_found"),
                        )

                    // Check AI config exists (→ 424 if not configured)
                    val aiConfig = aiConfigRepo.getByTenant(principal.tenantId)
                        ?: return@post call.respond(
                            HttpStatusCode(424, "Failed Dependency"),
                            mapOf("error" to "ai_not_configured"),
                        )

                    // Check token quota (→ 429 if exceeded)
                    val budget = aiConfig.monthlyTokenBudget
                    if (budget != null && aiConfig.tokensUsedMonth >= budget) {
                        return@post call.respond(
                            HttpStatusCode.TooManyRequests,
                            buildJsonObject {
                                put("error", "quota_exceeded")
                                put("budget", budget)
                                put("used", aiConfig.tokensUsedMonth)
                            },
                        )
                    }

                    // Build request payload for titlis-ai
                    val aiPayload = buildAiExplainPayload(
                        tenantId = principal.tenantId,
                        ruleId = ruleId,
                        body = body,
                        aiConfig = aiConfig,
                    )

                    val aiRequest = HttpRequest.newBuilder()
                        .uri(URI.create("${appConfig.aiService.url}/v1/explain"))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Secret", appConfig.aiService.internalSecret)
                        .POST(HttpRequest.BodyPublishers.ofString(aiPayload))
                        .build()

                    call.response.headers.append("Cache-Control", "no-cache")
                    call.response.headers.append("X-Accel-Buffering", "no")
                    call.respondBytesWriter(contentType = ContentType.parse("text/event-stream")) {
                        val channel = this
                        withContext(Dispatchers.IO) {
                            val response = httpClient.send(aiRequest, HttpResponse.BodyHandlers.ofInputStream())
                            val buffer = ByteArray(8192)
                            response.body().use { stream ->
                                var read = stream.read(buffer)
                                while (read >= 0) {
                                    channel.writeFully(buffer, 0, read)
                                    channel.flush()
                                    read = stream.read(buffer)
                                }
                            }
                        }
                    }
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

private fun buildRemediatePayload(
    tenantId: Long,
    workloadId: String,
    body: RemediateFindingsRequest,
    aiConfig: TenantAiConfigRecord,
): String = buildJsonObject {
    put("tenant_id", tenantId)
    put("workload_id", workloadId)
    put("finding_ids", buildJsonArray { body.findingIds.forEach { add(it) } })
    put("repo_url", body.repoUrl)
    put("deploy_manifest_path", body.deployManifestPath)
    put("ai_config", buildJsonObject {
        put("provider", aiConfig.provider)
        put("model", aiConfig.model)
        put("api_key", aiConfig.apiKeyEnc)
        put("github_token", aiConfig.githubTokenEnc?.let { JsonPrimitive(it) } ?: JsonNull)
        put("github_base_branch", aiConfig.githubBaseBranch)
        put("monthly_token_budget", aiConfig.monthlyTokenBudget?.let { JsonPrimitive(it) } ?: JsonNull)
        put("tokens_used_month", aiConfig.tokensUsedMonth)
    })
}.toString()

private fun buildAiExplainPayload(
    tenantId: Long,
    ruleId: String,
    body: ExplainFindingRequest,
    aiConfig: TenantAiConfigRecord,
): String = buildJsonObject {
    put("tenant_id", tenantId)
    put("workload_id", 0)
    put("finding", buildJsonObject {
        put("rule_id", ruleId)
        put("pillar", body.pillar)
        put("severity", body.severity)
        put("actual_value", body.actualValue?.let { JsonPrimitive(it) } ?: JsonNull)
        put("expected_value", body.expectedValue?.let { JsonPrimitive(it) } ?: JsonNull)
        put("deployment_name", body.deploymentName)
        put("namespace", body.namespace)
        put("container_name", body.containerName?.let { JsonPrimitive(it) } ?: JsonNull)
    })
    put("ai_config", buildJsonObject {
        put("provider", aiConfig.provider)
        put("model", aiConfig.model)
        put("api_key", aiConfig.apiKeyEnc)
        put("monthly_token_budget", aiConfig.monthlyTokenBudget?.let { JsonPrimitive(it) } ?: JsonNull)
        put("tokens_used_month", aiConfig.tokensUsedMonth)
    })
}.toString()
