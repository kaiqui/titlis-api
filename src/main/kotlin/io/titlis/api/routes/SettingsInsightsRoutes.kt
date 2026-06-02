package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.repository.AiConfigRepository
import kotlinx.serialization.Serializable

// titlis-insights foi descontinuado. Credenciais Datadog são armazenadas na titlis-api
// e entregues ao titlis-ai via GET /v1/internal/ai/datadog-config para uso com MCP Datadog.
// Endpoints de HPA templates e probe direto foram removidos (410 Gone).

private val goneInsights = mapOf(
    "error" to "gone",
    "message" to "Funcionalidade descontinuada. Configure as credenciais Datadog em /v1/settings/datadog.",
)

@Serializable
data class SaveDatadogCredsRequest(
    val ddApiKey: String,
    val ddAppKey: String? = null,
    val site: String = "datadoghq.com",
)

fun Application.settingsInsightsRoutes(
    aiConfigRepo: AiConfigRepository,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {

            // ── Descontinuados (410 Gone) ────────────────────────────────────────
            get("/v1/settings/hpa-templates")              { call.respond(HttpStatusCode.Gone, goneInsights) }
            put("/v1/settings/hpa-templates")              { call.respond(HttpStatusCode.Gone, goneInsights) }
            get("/v1/insights/recommendations/hpa/preview") { call.respond(HttpStatusCode.Gone, goneInsights) }
            get("/v1/insights/datadog/probe")              { call.respond(HttpStatusCode.Gone, goneInsights) }

            // ── Credenciais Datadog — write-only, armazenadas localmente ─────────
            post("/v1/settings/datadog") {
                val principal = call.requireAdminPrincipal() ?: return@post
                val req = call.receive<SaveDatadogCredsRequest>()
                if (req.ddApiKey.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "dd_api_key_required"))
                }
                aiConfigRepo.upsertDatadogCreds(
                    tenantId    = principal.tenantId,
                    ddApiKeyEnc = req.ddApiKey,
                    ddAppKeyEnc = req.ddAppKey?.takeIf { it.isNotBlank() },
                    ddSite      = req.site.ifBlank { "datadoghq.com" },
                )
                call.respond(HttpStatusCode.OK, mapOf("status" to "saved"))
            }

            // Retorna apenas se as credenciais estão configuradas (sem probe ao vivo).
            get("/v1/settings/datadog/status") {
                val principal = call.requireAdminPrincipal() ?: return@get
                val creds = aiConfigRepo.getDDCredentials(principal.tenantId)
                call.respondJson(
                    mapOf("configured" to (creds != null), "probeStatus" to if (creds != null) "not_checked" else "not_configured")
                )
            }
        }
    }
}
