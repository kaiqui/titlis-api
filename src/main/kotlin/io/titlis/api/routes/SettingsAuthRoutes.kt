package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.TenantAuthIntegrationNotFoundException
import io.titlis.api.auth.TenantAuthIntegrationValidationException
import io.titlis.api.auth.UpsertTenantAuthIntegrationRequest
import io.titlis.api.auth.VerifyTenantAuthIntegrationResponse
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.repository.AuthRepository

fun Application.settingsAuthRoutes(
    repo: AuthRepository,
) {
    routing {
        route("/v1/settings/auth") {
            authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
                get("/providers") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val integrations = repo.listTenantAuthIntegrations(principal.tenantId)
                    call.respond(integrations)
                }

                post("/providers") {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    try {
                        val request = call.receive<UpsertTenantAuthIntegrationRequest>()
                        val integration = repo.upsertTenantAuthIntegration(
                            tenantId = principal.tenantId,
                            configuredByUserId = principal.userId,
                            request = request,
                        )
                        call.respond(HttpStatusCode.Created, integration)
                    } catch (cause: TenantAuthIntegrationValidationException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid_auth_provider_config")))
                    } catch (cause: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid_request")))
                    } catch (cause: org.postgresql.util.PSQLException) {
                        val message = cause.message?.lowercase().orEmpty()
                        if (message.contains("chk_auth_provider_type") || message.contains("provider_type")) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "provider_type_unsupported"))
                        } else {
                            throw cause
                        }
                    }
                }

                post("/providers/{id}/verify") {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val integrationId = call.requireIntegrationId() ?: return@post
                    try {
                        val integration = repo.verifyTenantAuthIntegration(principal.tenantId, integrationId)
                        call.respond(
                            VerifyTenantAuthIntegrationResponse(
                                status = "verified",
                                message = "Integracao validada com sucesso.",
                                integration = integration,
                            ),
                        )
                    } catch (_: TenantAuthIntegrationNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "integration_not_found"))
                    } catch (cause: TenantAuthIntegrationValidationException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid_auth_provider_config")))
                    }
                }

                post("/providers/{id}/activate") {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val integrationId = call.requireIntegrationId() ?: return@post
                    try {
                        val integration = repo.activateTenantAuthIntegration(
                            tenantId = principal.tenantId,
                            integrationId = integrationId,
                            configuredByUserId = principal.userId,
                        )
                        call.respond(integration)
                    } catch (_: TenantAuthIntegrationNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "integration_not_found"))
                    } catch (cause: TenantAuthIntegrationValidationException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid_auth_provider_config")))
                    }
                }

                post("/providers/{id}/deactivate") {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val integrationId = call.requireIntegrationId() ?: return@post
                    try {
                        val integration = repo.deactivateTenantAuthIntegration(
                            tenantId = principal.tenantId,
                            integrationId = integrationId,
                            configuredByUserId = principal.userId,
                        )
                        call.respond(integration)
                    } catch (_: TenantAuthIntegrationNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "integration_not_found"))
                    } catch (cause: TenantAuthIntegrationValidationException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid_auth_provider_config")))
                    }
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.requireIntegrationId(): Long? {
    val raw = parameters["id"]
    val value = raw?.toLongOrNull()
    if (value == null) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_integration_id"))
        return null
    }
    return value
}
