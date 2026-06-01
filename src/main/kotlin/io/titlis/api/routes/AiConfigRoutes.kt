package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.SUPPORTED_PROVIDERS
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class UpsertAiConfigRequest(
    val provider: String,
    val model: String,
    val apiKey: String? = null,
    val githubToken: String? = null,
    val githubBaseBranch: String = "main",
    val githubAuthMode: String = "pat",
    val githubAppId: String? = null,
    val githubAppPrivateKey: String? = null,
    val githubAppInstallationId: String? = null,
    val monthlyTokenBudget: Int? = null,
)

@Serializable
data class AiConfigResponse(
    val provider: String,
    val model: String,
    val githubBaseBranch: String,
    val githubAuthMode: String,
    val monthlyTokenBudget: Int?,
    val tokensUsedMonth: Int,
    val isActive: Boolean,
    val hasApiKey: Boolean,
    val hasGithubToken: Boolean,
    val hasGithubApp: Boolean,
    val updatedAt: String,
)

fun Application.aiConfigRoutes(
    aiConfigRepo: AiConfigRepository,
    requestAuthenticator: RequestAuthenticator? = null,
) {
    routing {
        route("/v1/settings/ai-config") {
            fun io.ktor.server.routing.Route.protectedEndpoints() {
                get {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val config = aiConfigRepo.getByTenant(principal.tenantId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "ai_not_configured"),
                        )
                    call.respond(config.toResponse())
                }

                put {
                    val principal = call.requireAdminPrincipal() ?: return@put
                    val req = call.receive<UpsertAiConfigRequest>()

                    if (req.provider !in SUPPORTED_PROVIDERS) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            buildJsonObject {
                                put("error", "unsupported_provider")
                                put("supported", buildJsonArray { SUPPORTED_PROVIDERS.sorted().forEach { add(it) } })
                            },
                        )
                    }
                    if (req.model.isBlank()) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "model_required"),
                        )
                    }

                    val existing = aiConfigRepo.getByTenant(principal.tenantId)
                    val resolvedApiKey = req.apiKey
                        ?: existing?.apiKeyEnc
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "api_key_required_for_first_setup"),
                        )
                    val resolvedGithubToken        = req.githubToken?.takeIf { it.isNotBlank() } ?: existing?.githubTokenEnc
                    val resolvedGithubAppId        = req.githubAppId?.takeIf { it.isNotBlank() } ?: existing?.githubAppIdEnc
                    val resolvedGithubAppPrivKey   = req.githubAppPrivateKey?.takeIf { it.isNotBlank() } ?: existing?.githubAppPrivKeyEnc
                    val resolvedGithubAppInstallId = req.githubAppInstallationId?.takeIf { it.isNotBlank() } ?: existing?.githubAppInstallIdEnc

                    val config = aiConfigRepo.upsert(
                        tenantId              = principal.tenantId,
                        provider              = req.provider,
                        model                 = req.model,
                        apiKeyEnc             = resolvedApiKey,
                        githubTokenEnc        = resolvedGithubToken,
                        githubBaseBranch      = req.githubBaseBranch,
                        githubAuthMode        = req.githubAuthMode.ifBlank { "pat" },
                        githubAppIdEnc        = resolvedGithubAppId,
                        githubAppPrivKeyEnc   = resolvedGithubAppPrivKey,
                        githubAppInstallIdEnc = resolvedGithubAppInstallId,
                        monthlyTokenBudget    = req.monthlyTokenBudget,
                    )

                    call.respond(HttpStatusCode.OK, config.toResponse())
                }

                post("/test") {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val config = aiConfigRepo.getByTenant(principal.tenantId)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "ai_not_configured"),
                        )
                    // Teste de conectividade: verifica que o provider e model são minimamente válidos.
                    // Chamada real ao LLM delegada ao titlis-ai em Fase 3+.
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "provider" to config.provider,
                            "model" to config.model,
                            "status" to "config_valid",
                            "note" to "Live connectivity test available after titlis-ai is deployed.",
                        ),
                    )
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

private fun io.titlis.api.repository.TenantAiConfigRecord.toResponse() = AiConfigResponse(
    provider          = provider,
    model             = model,
    githubBaseBranch  = githubBaseBranch,
    githubAuthMode    = githubAuthMode,
    monthlyTokenBudget = monthlyTokenBudget,
    tokensUsedMonth   = tokensUsedMonth,
    isActive          = isActive,
    hasApiKey         = apiKeyEnc.isNotBlank(),
    hasGithubToken    = !githubTokenEnc.isNullOrBlank(),
    hasGithubApp      = !githubAppIdEnc.isNullOrBlank() && !githubAppPrivKeyEnc.isNullOrBlank() && !githubAppInstallIdEnc.isNullOrBlank(),
    updatedAt         = updatedAt.toString(),
)
