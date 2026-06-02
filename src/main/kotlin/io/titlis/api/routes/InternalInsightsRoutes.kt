package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.repository.AiConfigRepository
import kotlinx.serialization.Serializable

@Serializable
data class DDCredentialsResponse(
    val ddApiKey: String,
    val ddAppKey: String,
    val site: String,
)

// Entrega credenciais Datadog ao titlis-ai para inicializar o MCP Datadog por sessão.
// Autenticado por X-Internal-Secret — nunca exposto ao browser.
fun Application.internalInsightsRoutes(
    aiConfigRepo: AiConfigRepository,
    internalSecret: String,
) {
    routing {
        get("/v1/internal/ai/datadog-config") {
            val secret = call.request.headers["X-Internal-Secret"]
            if (secret != internalSecret) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
            val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tenantId required"))

            val creds = aiConfigRepo.getDDCredentials(tenantId)
                ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "dd_credentials_not_configured"),
                )

            call.respond(
                HttpStatusCode.OK,
                DDCredentialsResponse(
                    ddApiKey = creds.ddApiKey,
                    ddAppKey = creds.ddAppKey,
                    site     = creds.ddSite,
                ),
            )
        }
    }
}
