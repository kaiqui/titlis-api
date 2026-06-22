package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.domain.QueueIntegrationSpec
import io.titlis.api.domain.WorkloadMatchSpec
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.ServiceDefinitionEvent
import io.titlis.api.repository.ServiceDefinitionRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ServicemapTenantDTO(
    val tenantId: Long,
    val githubToken: String,
    val authMode: String,
)

@Serializable
data class ServicemapUpsertRequest(
    val tenantId: Long,
    val serviceName: String,
    val team: String,
    val product: String? = null,
    val tier: String? = null,
    val description: String? = null,
    val repoUrl: String? = null,
    val workloads: List<String> = emptyList(),
    val rawYaml: String? = null,
    val workloadMatch: WorkloadMatchSpec? = null,
    val gitopsPaths: JsonElement? = null,
    val remediation: JsonElement? = null,
    val integrations: List<QueueIntegrationSpec> = emptyList(),
)

@Serializable
data class ServicemapScanCompleteRequest(
    val tenantId: Long,
    val seenServiceNames: List<String> = emptyList(),
)

fun Application.internalServicemapRoutes(
    aiConfigRepo: AiConfigRepository,
    serviceDefRepo: ServiceDefinitionRepository,
    internalSecret: String,
) {
    routing {
        route("/v1/internal/servicemap") {

            // Lista tenants ativos com GitHub configurado + token resolvido (PAT ou installation
            // token do App). Token usado em memória pelo worker; nunca persistido/logado.
            get("/tenants") {
                if (call.request.headers["X-Internal-Secret"] != internalSecret) {
                    return@get call.respond(HttpStatusCode.Forbidden, errorBody("internal_secret_invalid"))
                }
                val tenants = buildList {
                    for (cfg in aiConfigRepo.listActive()) {
                        val resolved = resolveGithubToken(cfg)
                        if (resolved is GithubTokenResult.Ok) {
                            add(ServicemapTenantDTO(cfg.tenantId, resolved.token, cfg.githubAuthMode))
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, tenants)
            }

            // Upsert de um .titlis/service.yaml descoberto pelo worker.
            post("/service-definitions") {
                if (call.request.headers["X-Internal-Secret"] != internalSecret) {
                    return@post call.respond(HttpStatusCode.Forbidden, errorBody("internal_secret_invalid"))
                }
                val req = call.receive<ServicemapUpsertRequest>()
                serviceDefRepo.upsert(
                    tenantId = req.tenantId,
                    event = ServiceDefinitionEvent(
                        serviceName     = req.serviceName,
                        team            = req.team,
                        product         = req.product,
                        tier            = req.tier,
                        description     = req.description,
                        repoUrl         = req.repoUrl,
                        workloads       = req.workloads,
                        rawYaml         = req.rawYaml,
                        integrations    = req.integrations,
                        workloadMatch   = req.workloadMatch,
                        gitopsPathsJson = req.gitopsPaths?.toString(),
                        remediationJson = req.remediation?.toString(),
                    ),
                )
                call.respond(HttpStatusCode.OK, buildJsonObject { put("status", "ok") })
            }

            // Fim de um scan: marca stale as definitions do tenant não vistas neste ciclo.
            post("/scan-complete") {
                if (call.request.headers["X-Internal-Secret"] != internalSecret) {
                    return@post call.respond(HttpStatusCode.Forbidden, errorBody("internal_secret_invalid"))
                }
                val req = call.receive<ServicemapScanCompleteRequest>()
                val staled = serviceDefRepo.markStaleExcept(req.tenantId, req.seenServiceNames)
                call.respond(HttpStatusCode.OK, buildJsonObject { put("staled", staled) })
            }
        }
    }
}

private fun errorBody(message: String) = buildJsonObject { put("error", message) }
