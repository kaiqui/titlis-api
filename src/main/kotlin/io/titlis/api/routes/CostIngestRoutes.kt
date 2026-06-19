package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.titlis.api.repository.CostRepository
import io.titlis.api.repository.GcpBillingConfigRepository
import io.titlis.api.repository.NamespaceCostIngest
import io.titlis.api.repository.WorkloadCostIngest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class CostIngestRequest(
    val tenantId: Long,
    val date: String,
    val provider: String,
    val workloadCosts: List<WorkloadCostIngest>,
    val namespaceCosts: List<NamespaceCostIngest>,
)

@Serializable
data class CostIngestResponse(
    val accepted: Int,
    val skipped: Int,
)

fun Application.costIngestRoutes(
    costRepo: CostRepository,
    gcpBillingRepo: GcpBillingConfigRepository,
    internalSecret: String,
) {
    routing {
        post("/v1/cost/ingest") {
            val secret = call.request.headers["X-Internal-Secret"] ?: ""
            if (secret != internalSecret) {
                call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "forbidden") })
                return@post
            }

            val req = call.receive<CostIngestRequest>()

            val date = runCatching { LocalDate.parse(req.date) }.getOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    buildJsonObject { put("error", "invalid date format, expected YYYY-MM-DD") },
                )

            val accepted = costRepo.insertWorkloadCostBatch(
                tenantId = req.tenantId,
                date     = date,
                provider = req.provider,
                entries  = req.workloadCosts,
            )
            costRepo.insertNamespaceCostBatch(
                tenantId = req.tenantId,
                date     = date,
                provider = req.provider,
                entries  = req.namespaceCosts,
            )

            gcpBillingRepo.markCollected(
                tenantId        = req.tenantId,
                workloadsCovered = accepted,
            )

            call.respond(CostIngestResponse(accepted = accepted, skipped = 0))
        }
    }
}
