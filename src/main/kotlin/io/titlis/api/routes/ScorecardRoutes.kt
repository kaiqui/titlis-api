package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.repository.ScorecardRepository

fun Application.scorecardRoutes(repo: ScorecardRepository) {
    routing {
        route("/v1") {
            // Dashboard: estado atual de todos os workloads
            get("/dashboard") {
                val cluster = call.request.queryParameters["cluster"]
                call.respondJson(repo.getDashboard(cluster))
            }

            // Estado atual de um workload específico
            get("/workloads/{workloadId}/scorecard") {
                val id = call.parameters["workloadId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "workloadId required")
                val result = repo.getByWorkloadId(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondJson(result)
            }
        }
    }
}
