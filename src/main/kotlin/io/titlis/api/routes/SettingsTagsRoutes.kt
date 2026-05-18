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
import io.titlis.api.repository.TagRepository
import kotlinx.serialization.Serializable

private val validResourceTypes = setOf("cluster", "namespace", "workload", "tenant", "slo")

@Serializable
data class AddTagRequest(val tag: String)

@Serializable
data class ResourceTagsResponse(val resourceId: Long, val tags: List<String>)

fun Application.settingsTagsRoutes(tagRepo: TagRepository) {
    routing {
        route("/v1/settings/tags") {
            authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {

                get("/resource-list/clusters") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    call.respond(tagRepo.listClusters(principal.tenantId))
                }

                get("/resource-list/namespaces") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val clusterId = call.request.queryParameters["clusterId"]?.toLongOrNull()
                    call.respond(tagRepo.listNamespaces(principal.tenantId, clusterId))
                }

                get("/resource-list/workloads") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val clusterId   = call.request.queryParameters["clusterId"]?.toLongOrNull()
                    val namespaceId = call.request.queryParameters["namespaceId"]?.toLongOrNull()
                    call.respond(tagRepo.listWorkloads(principal.tenantId, clusterId, namespaceId))
                }

                get("/{resourceType}") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val resourceType = call.parameters["resourceType"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_type"))
                    if (resourceType !in validResourceTypes)
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_type"))
                    val result = tagRepo.listResourcesWithTags(principal.tenantId, resourceType)
                        .map { (id, tags) -> ResourceTagsResponse(id, tags) }
                    call.respond(result)
                }

                get("/{resourceType}/{resourceId}") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val resourceType = call.parameters["resourceType"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_type"))
                    if (resourceType !in validResourceTypes)
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_type"))
                    val resourceId = call.parameters["resourceId"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_id"))
                    if (!tagRepo.validateOwnership(principal.tenantId, resourceType, resourceId))
                        return@get call.respond(HttpStatusCode.NotFound)
                    val tags = tagRepo.listTagsForResource(principal.tenantId, resourceType, resourceId)
                    call.respond(ResourceTagsResponse(resourceId, tags))
                }

                post("/{resourceType}/{resourceId}") {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val resourceType = call.parameters["resourceType"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_type"))
                    if (resourceType !in validResourceTypes)
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_type"))
                    val resourceId = call.parameters["resourceId"]?.toLongOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_id"))
                    if (!tagRepo.validateOwnership(principal.tenantId, resourceType, resourceId))
                        return@post call.respond(HttpStatusCode.NotFound)
                    val body = call.receive<AddTagRequest>()
                    if (body.tag.isBlank())
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tag cannot be blank"))
                    tagRepo.addTag(principal.tenantId, resourceType, resourceId, body.tag.trim(), principal.email)
                    call.respond(HttpStatusCode.Created, mapOf("tag" to body.tag.trim()))
                }

                delete("/{resourceType}/{resourceId}/{tag}") {
                    val principal = call.requireAdminPrincipal() ?: return@delete
                    val resourceType = call.parameters["resourceType"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_type"))
                    if (resourceType !in validResourceTypes)
                        return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_type"))
                    val resourceId = call.parameters["resourceId"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_resource_id"))
                    val tag = call.parameters["tag"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tag required"))
                    if (!tagRepo.validateOwnership(principal.tenantId, resourceType, resourceId))
                        return@delete call.respond(HttpStatusCode.NotFound)
                    val removed = tagRepo.removeTag(principal.tenantId, resourceType, resourceId, tag)
                    call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
                }
            }
        }
    }
}
