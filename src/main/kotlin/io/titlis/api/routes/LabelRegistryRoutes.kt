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
import io.titlis.api.domain.AddLabelValueRequest
import io.titlis.api.repository.LabelRegistryRepository

fun Application.labelRegistryRoutes(
    labelRegistryRepo: LabelRegistryRepository,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            route("/v1/settings/labels") {

                get {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val registry = labelRegistryRepo.listByTenant(principal.tenantId)
                    val flat = registry.labels.flatMap { it.values }
                    call.respond(flat)
                }

                post {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val req = runCatching { call.receive<AddLabelValueRequest>() }
                        .getOrElse {
                            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload"))
                        }
                    if (req.labelKey.isBlank() || req.labelValue.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "labelKey and labelValue required"))
                    }
                    val entry = labelRegistryRepo.addValue(principal.tenantId, req.labelKey.trim(), req.labelValue.trim())
                    call.respond(HttpStatusCode.Created, entry)
                }

                delete("/{id}") {
                    val principal = call.requireAdminPrincipal() ?: return@delete
                    val labelRegistryId = call.parameters["id"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    val removed = labelRegistryRepo.removeValue(principal.tenantId, labelRegistryId)
                    call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
                }
            }
        }
    }
}
