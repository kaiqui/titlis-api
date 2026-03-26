package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.repository.RemediationRepository

fun Application.remediationRoutes(repo: RemediationRepository) {
    routing {
        route("/v1") {
            // workloadId = k8s metadata.uid (UUID string) — resolvido internamente para Long workload_id
            get("/workloads/{workloadId}/remediation") {
                val k8sUid = call.parameters["workloadId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "workloadId required")
                val result = repo.getByWorkload(k8sUid)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondJson(result)
            }
        }
    }
}
