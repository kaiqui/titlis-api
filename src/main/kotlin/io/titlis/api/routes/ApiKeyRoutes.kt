package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.repository.ApiKeyRecord
import io.titlis.api.repository.ApiKeyRepository
import kotlinx.serialization.Serializable

@Serializable
data class CreateApiKeyRequest(val description: String? = null)

@Serializable
data class ApiKeyConnectionStatus(
    val connected: Boolean,
    val lastEventAt: String?,
    val activeKeyCount: Int,
)

@Serializable
data class ApiKeyListItem(
    val id: Long,
    val prefix: String,
    val description: String?,
    val isActive: Boolean,
    val lastUsedAt: String?,
    val createdAt: String,
)

@Serializable
data class CreateApiKeyResponse(
    val id: Long,
    val prefix: String,
    val description: String?,
    val rawToken: String,
    val createdAt: String,
)

fun Application.apiKeyRoutes(apiKeyRepo: ApiKeyRepository) {
    routing {
        route("/v1/settings/api-keys") {
            authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
                get {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val keys = apiKeyRepo.listByTenant(principal.tenantId)
                    call.respond(keys.map { it.toListItem() })
                }

                get("/connection-status") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val keys = apiKeyRepo.listByTenant(principal.tenantId)
                    val lastEventAt = apiKeyRepo.lastEventAt(principal.tenantId)
                    call.respond(
                        ApiKeyConnectionStatus(
                            connected = lastEventAt != null,
                            lastEventAt = lastEventAt?.toString(),
                            activeKeyCount = keys.size,
                        ),
                    )
                }

                post {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val body = call.receive<CreateApiKeyRequest>()
                    val (record, rawToken) = apiKeyRepo.create(
                        tenantId        = principal.tenantId,
                        description     = body.description,
                        createdByUserId = principal.userId,
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        CreateApiKeyResponse(
                            id          = record.apiKeyId,
                            prefix      = record.keyPrefix,
                            description = record.description,
                            rawToken    = rawToken,
                            createdAt   = record.createdAt.toString(),
                        ),
                    )
                }

                delete("/{id}") {
                    val principal = call.requireAdminPrincipal() ?: return@delete
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_api_key_id"))
                    val ok = apiKeyRepo.revoke(id, principal.tenantId)
                    call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
                }
            }
        }
    }
}

private fun ApiKeyRecord.toListItem() = ApiKeyListItem(
    id          = apiKeyId,
    prefix      = keyPrefix,
    description = description,
    isActive    = isActive,
    lastUsedAt  = lastUsedAt?.toString(),
    createdAt   = createdAt.toString(),
)
