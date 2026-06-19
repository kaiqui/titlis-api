package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.repository.CostRepository
import io.titlis.api.repository.GcpBillingConfigRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class TenantBillingConfigDTO(
    val tenantId: Long,
    val provider: String,
    val credentials: String,
    val params: String,
    val clusterNames: List<String>,
)

fun Application.internalCostRoutes(
    gcpBillingRepo: GcpBillingConfigRepository,
    costRepo: CostRepository,
    internalSecret: String,
) {
    routing {
        route("/v1/internal/cost") {

            get("/config") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "forbidden") })
                    return@get
                }

                val configs = gcpBillingRepo.getAllActive().map { cfg ->
                    val params = buildJsonObject {
                        put("project_id", cfg.projectId)
                        put("bigquery_dataset", cfg.bigqueryDataset)
                    }.toString()

                    TenantBillingConfigDTO(
                        tenantId     = cfg.tenantId,
                        provider     = "gcp",
                        credentials  = cfg.credentialsEnc,
                        params       = params,
                        clusterNames = emptyList(),
                    )
                }
                call.respond(configs)
            }

            get("/workloads") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "forbidden") })
                    return@get
                }

                val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "tenantId required") },
                    )

                val workloads = costRepo.getWorkloadRefs(tenantId)
                call.respond(workloads)
            }
        }
    }
}
