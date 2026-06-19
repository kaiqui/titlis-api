package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.config.ScoreopsClient
import io.titlis.api.domain.*
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.LabelRegistryRepository
import io.titlis.api.repository.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun Application.operatorQueueRoutes(
    queueRepo: QueueRepository,
    labelRegistryRepo: LabelRegistryRepository,
    aiConfigRepo: AiConfigRepository,
    apiKeyRepo: ApiKeyRepository,
    scoreopsClient: ScoreopsClient,
    scope: CoroutineScope,
) {
    val log = LoggerFactory.getLogger("OperatorQueueRoutes")
    val json = Json { ignoreUnknownKeys = true }

    routing {
        route("/v1/operator") {

            get("/datadog-config") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val creds = aiConfigRepo.getDDCredentials(tenantId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "datadog_config_not_found"))
                call.respond(DatadogConfigDTO(ddApiKey = creds.ddApiKey, ddAppKey = creds.ddAppKey, ddSite = creds.ddSite))
            }

            get("/queue-config") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val enabled = aiConfigRepo.isQueueMonitoringEnabled(tenantId)
                val monitorCreation = aiConfigRepo.isMonitorCreationEnabled(tenantId)
                call.respond(QueueConfigDTO(enabled = enabled, monitorCreationEnabled = monitorCreation))
            }

            get("/label-registry") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val labels = labelRegistryRepo.listForOperator(tenantId)
                call.respond(LabelRegistryOperatorDTO(labels = labels))
            }

            post("/queue/observe") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val req = runCatching { call.receive<QueueObservationRequest>() }
                    .getOrElse {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                    }
                val result = queueRepo.recordObservation(tenantId, req)
                call.respond(result)
            }

            post("/queue/observe/batch") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val reqs = runCatching { call.receive<List<QueueObservationRequest>>() }
                    .getOrElse {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                    }
                if (reqs.isEmpty()) {
                    call.respond(emptyList<QueueBatchObserveResponseItem>())
                    return@post
                }
                val results = queueRepo.recordObservationBatch(tenantId, reqs)
                call.respond(results)
            }

            // Fase 3 — nomes de fila conhecidos; o operator casa localmente contra env vars.
            get("/queue-names") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                call.respond(queueRepo.queueNamesFor(tenantId))
            }

            // Fase 3 — hints de correlação por env var (só os matches encontrados no cluster).
            post("/queue/link-hints") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val hints = runCatching { call.receive<List<QueueLinkHint>>() }
                    .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload")) }
                val n = queueRepo.recordLinkHints(tenantId, hints)
                call.respond(mapOf("suggestionsRecorded" to n))
            }

            // externalId is passed as query param to avoid path parsing issues with slashes
            get("/queue/lifecycle") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val externalId = call.request.queryParameters["externalId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "externalId required"))
                val provider = call.request.queryParameters["provider"] ?: "gcp_pubsub"
                val lifecycle = queueRepo.getLifecycle(tenantId, externalId, provider)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(lifecycle)
            }

            post("/queue/promote") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val externalId = call.request.queryParameters["externalId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "externalId required"))
                val provider = call.request.queryParameters["provider"] ?: "gcp_pubsub"
                val thresholds = queueRepo.promoteToMonitoring(tenantId, externalId, provider)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(thresholds)
            }

            post("/queue/evaluate") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val req = runCatching { call.receive<QueueEvaluateRequest>() }
                    .getOrElse {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                    }
                val withTenant = req.copy(tenantId = tenantId)

                call.respond(HttpStatusCode.Accepted)
                scope.launch {
                    try {
                        val body = json.encodeToString(withTenant)
                        val resp = scoreopsClient.post("/v1/queue/evaluate", body)
                        if (resp.statusCode() >= 400) {
                            log.warn("scoreops queue/evaluate failed status={} externalId={} tenant={}", resp.statusCode(), req.externalId, tenantId)
                        }
                    } catch (e: Exception) {
                        log.warn("scoreops queue/evaluate error externalId={} tenant={}", req.externalId, tenantId, e)
                    }
                }
            }
        }
    }
}
