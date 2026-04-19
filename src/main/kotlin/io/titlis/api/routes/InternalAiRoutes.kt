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
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.SloRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class AiRemediationNotification(
    val workloadId: String,
    val tenantId: Long,
    val prUrl: String? = null,
    val prNumber: Int? = null,
    val githubBranch: String? = null,
    val repoUrl: String? = null,
    val findingIds: List<String> = emptyList(),
)

@Serializable
data class InternalProposeChangeRequest(
    val field: String,
    val oldValue: String,
    val newValue: String,
    val requestedBy: String = "titlis-ai",
)

fun Application.internalAiRoutes(
    scorecardRepo: ScorecardRepository,
    remediationRepo: RemediationRepository,
    sloRepo: SloRepository,
    internalSecret: String,
) {
    routing {
        route("/v1/internal/ai") {

            get("/scorecards") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@get
                }
                val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenantId required") })
                val k8sUid = call.request.queryParameters["k8sUid"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "k8sUid required") })
                val result = scorecardRepo.getByWorkloadId(k8sUid, tenantId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondJson(result)
            }

            get("/workloads") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@get
                }
                val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenantId required") })
                val name = call.request.queryParameters["name"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "name required") })
                val namespace = call.request.queryParameters["namespace"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "namespace required") })
                val result = scorecardRepo.getByName(name, namespace, tenantId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondJson(result)
            }

            get("/dashboard") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@get
                }
                val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenantId required") })
                val cluster = call.request.queryParameters["cluster"]
                call.respondJson(scorecardRepo.getDashboard(tenantId, cluster))
            }

            get("/similar-resolved") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@get
                }
                val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenantId required") })
                val ruleId = call.request.queryParameters["ruleId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "ruleId required") })
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5
                call.respondJson(scorecardRepo.getSimilarResolved(ruleId, tenantId, limit))
            }

            get("/slos") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@get
                }
                val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenantId required") })
                val namespace = call.request.queryParameters["namespace"]
                call.respondJson(sloRepo.list(tenantId, namespace, null))
            }

            get("/remediations") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@get
                }
                val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenantId required") })
                val k8sUid = call.request.queryParameters["k8sUid"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "k8sUid required") })
                call.respondJson(remediationRepo.getHistory(k8sUid, tenantId))
            }

            post("/remediations") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@post
                }
                val body = call.receive<AiRemediationNotification>()
                remediationRepo.notifyRemediationStarted(
                    k8sUid         = body.workloadId,
                    tenantId       = body.tenantId,
                    prUrl          = body.prUrl,
                    prNumber       = body.prNumber,
                    githubBranch   = body.githubBranch,
                    repositoryUrl  = body.repoUrl,
                    findingIds     = body.findingIds,
                )
                call.respond(HttpStatusCode.NoContent)
            }

            post("/slo-configs/{id}/propose-change") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@post
                }
                val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "tenantId required") })
                val sloConfigId = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "invalid_slo_config_id") })
                val body = call.receive<InternalProposeChangeRequest>()
                if (body.field !in listOf("target", "warning", "timeframe")) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "field must be target, warning, or timeframe") },
                    )
                }
                val change = sloRepo.proposeChange(
                    sloConfigId = sloConfigId,
                    tenantId = tenantId,
                    field = body.field,
                    oldValue = body.oldValue,
                    newValue = body.newValue,
                    requestedBy = body.requestedBy,
                ) ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(HttpStatusCode.Created, change)
            }
        }
    }
}
