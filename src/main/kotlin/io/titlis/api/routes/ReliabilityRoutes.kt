package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireRole
import io.titlis.api.repository.ReliabilityRepository

fun Application.reliabilityRoutes(
    reliabilityRepo: ReliabilityRepository,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            route("/v1/reliability") {
                // Mesma rota parametrizada para os dois frontends:
                //   titlis-ui      → ?depth=all (árvore inteira)
                //   titlis-ui-saas → ?root={path}&depth=1 (por segmento RSC)
                get("/tree") {
                    val principal = call.requireRole() ?: return@get
                    val root = call.request.queryParameters["root"] ?: ""
                    val depthParam = call.request.queryParameters["depth"] ?: "all"
                    val depth = if (depthParam == "all") Int.MAX_VALUE else (depthParam.toIntOrNull() ?: Int.MAX_VALUE)
                    val tree = reliabilityRepo.getTree(principal.tenantId, root, depth)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(tree)
                }

                get("/services/{id}/findings") {
                    val principal = call.requireRole() ?: return@get
                    val serviceDefinitionId = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    call.respond(reliabilityRepo.getServiceFindings(principal.tenantId, serviceDefinitionId))
                }

                // Tendência de RI (um ponto por dia) para o nó — sparkline nas duas UIs.
                get("/trend") {
                    val principal = call.requireRole() ?: return@get
                    val root = call.request.queryParameters["root"] ?: ""
                    val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 14).coerceIn(1, 90)
                    call.respond(reliabilityRepo.getTrend(principal.tenantId, root, days))
                }
            }
        }
    }
}
