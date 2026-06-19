package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.domain.DatadogSettingsDTO
import io.titlis.api.domain.QueueCountsDTO
import io.titlis.api.domain.SaveDatadogSettingsRequest
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.QueueRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class DatadogTestResult(
    val ok: Boolean,
    val error: String? = null,
)

fun Application.datadogSettingsRoutes(
    aiConfigRepo: AiConfigRepository,
    queueRepo: QueueRepository,
) {
    val log = LoggerFactory.getLogger("DatadogSettingsRoutes")
    val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            route("/v1/settings/datadog") {

                get {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val cfg = aiConfigRepo.getByTenant(principal.tenantId)
                    val counts = queueRepo.countsByLifecycle(principal.tenantId)
                    call.respond(
                        DatadogSettingsDTO(
                            hasApiKey              = !cfg?.ddApiKeyEnc.isNullOrBlank(),
                            hasAppKey              = !cfg?.ddAppKeyEnc.isNullOrBlank(),
                            site                   = cfg?.ddSite ?: "datadoghq.com",
                            queueMonitoringEnabled = cfg?.queueMonitoringEnabled ?: false,
                            monitorCreationEnabled = cfg?.monitorCreationEnabled ?: false,
                            queueCounts            = QueueCountsDTO(
                                discovering = counts["DISCOVERING"] ?: 0,
                                learning    = counts["LEARNING"] ?: 0,
                                monitoring  = counts["MONITORING"] ?: 0,
                            ),
                        )
                    )
                }

                put {
                    val principal = call.requireAdminPrincipal() ?: return@put
                    val req = runCatching { call.receive<SaveDatadogSettingsRequest>() }
                        .getOrElse {
                            return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                        }

                    if (!req.ddApiKey.isNullOrBlank() || !req.ddAppKey.isNullOrBlank()) {
                        val existing = aiConfigRepo.getDDCredentials(principal.tenantId)
                        aiConfigRepo.upsertDatadogCreds(
                            tenantId    = principal.tenantId,
                            ddApiKeyEnc = req.ddApiKey?.takeIf { it.isNotBlank() } ?: existing?.ddApiKey ?: "",
                            ddAppKeyEnc = req.ddAppKey?.takeIf { it.isNotBlank() } ?: existing?.ddAppKey,
                            ddSite      = req.ddSite,
                        )
                    }

                    aiConfigRepo.setQueueMonitoringEnabled(principal.tenantId, req.queueMonitoringEnabled)
                    aiConfigRepo.setMonitorCreationEnabled(principal.tenantId, req.monitorCreationEnabled)
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/test") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val creds = aiConfigRepo.getDDCredentials(principal.tenantId)
                    if (creds == null || creds.ddApiKey.isBlank()) {
                        call.respond(DatadogTestResult(ok = false, error = "Credenciais Datadog não configuradas"))
                        return@get
                    }
                    try {
                        val site = creds.ddSite.ifBlank { "datadoghq.com" }
                        val req = HttpRequest.newBuilder()
                            .uri(URI.create("https://api.${site}/api/v1/validate"))
                            .header("DD-API-KEY", creds.ddApiKey)
                            .header("DD-APPLICATION-KEY", creds.ddAppKey)
                            .GET()
                            .timeout(Duration.ofSeconds(8))
                            .build()
                        val resp = withContext(Dispatchers.IO) {
                            http.send(req, HttpResponse.BodyHandlers.ofString())
                        }
                        if (resp.statusCode() == 200) {
                            call.respond(DatadogTestResult(ok = true))
                        } else {
                            call.respond(DatadogTestResult(ok = false, error = "Datadog retornou status ${resp.statusCode()}"))
                        }
                    } catch (e: Exception) {
                        log.warn("datadog connectivity test failed tenant={}", principal.tenantId, e)
                        call.respond(DatadogTestResult(ok = false, error = e.message ?: "Falha de conexão"))
                    }
                }
            }
        }
    }
}
