package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.repository.FavoriteRepository
import io.titlis.api.repository.ScorecardRepository

fun Application.scorecardRoutes(
    repo: ScorecardRepository,
    favoriteRepo: FavoriteRepository,
    requestAuthenticator: RequestAuthenticator? = null,
) {
    routing {
        route("/v1") {
            fun Route.protectedEndpoints() {
                get("/dashboard") {
                    val principal = call.principal<AppPrincipal>()
                    val cluster = call.request.queryParameters["cluster"]
                    val dashboard = repo.getDashboard(principal?.tenantId ?: 0, cluster)
                    val favoriteUids = if (principal?.userId != null)
                        favoriteRepo.listFavoriteK8sUids(principal.userId, principal.tenantId)
                    else emptySet<String>()
                    val enriched = dashboard.map { row ->
                        row + ("is_favorite" to (row["workload_id"] in favoriteUids))
                    }
                    call.respondJson(enriched)
                }

                get("/workloads/{workloadId}/scorecard") {
                    val principal = call.principal<AppPrincipal>()
                    val id = call.parameters["workloadId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "workloadId required")
                    val result = repo.getByWorkloadId(id, principal?.tenantId ?: 0)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondJson(result)
                }
            }

            if (requestAuthenticator == null) {
                protectedEndpoints()
            } else {
                authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
                    protectedEndpoints()
                }
            }
        }
    }
}
