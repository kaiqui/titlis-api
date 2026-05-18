package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.config.ScoreopsClient

fun Application.settingsTagPoliciesRoutes(
    scoreopsClient: ScoreopsClient,
    requestAuthenticator: RequestAuthenticator? = null,
) {
    routing {
        route("/v1/settings/scoring/tag-policies") {
            fun Route.adminEndpoints() {

                get {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val resp = scoreopsClient.get("/tenants/${principal.tenantId}/tag-policies")
                    call.respond(HttpStatusCode.fromValue(resp.statusCode()), resp.body())
                }

                post {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val body = call.receiveText()
                    val resp = scoreopsClient.post("/tenants/${principal.tenantId}/tag-policies", body)
                    call.respond(HttpStatusCode.fromValue(resp.statusCode()), resp.body())
                }

                delete("/{id}") {
                    val principal = call.requireAdminPrincipal() ?: return@delete
                    val id = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id obrigatório"))
                    val resp = scoreopsClient.delete("/tenants/${principal.tenantId}/tag-policies/$id")
                    call.respond(HttpStatusCode.fromValue(resp.statusCode()), resp.body())
                }
            }

            if (requestAuthenticator == null) {
                adminEndpoints()
            } else {
                authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
                    adminEndpoints()
                }
            }
        }
    }
}
