package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.config.ScoreopsClient
import io.titlis.api.repository.ScoreConfigRepository

fun Application.settingsScoreConfigRoutes(
    scoreopsClient: ScoreopsClient,
    scoreConfigRepo: ScoreConfigRepository,
    requestAuthenticator: RequestAuthenticator? = null,
) {
    routing {
        route("/v1/settings/score-config") {
            fun Route.adminEndpoints() {

                // Lista regras disponíveis da engine (leitura direta do banco)
                get("/rules") {
                    call.requireAdminPrincipal() ?: return@get
                    val engine = call.request.queryParameters["engine"] ?: "kubernetes"
                    call.respond(scoreConfigRepo.listRules(engine))
                }

                // Lista overrides do tenant (proxy GET scoreops)
                get("/overrides") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val q = call.request.queryParameters
                    val path = buildString {
                        append("/tenants/${principal.tenantId}/overrides")
                        val params = buildList {
                            q["engine"]?.let  { add("engine=$it") }
                            q["scope"]?.let   { add("scope=$it") }
                            q["cluster"]?.let { add("cluster=$it") }
                        }
                        if (params.isNotEmpty()) append("?${params.joinToString("&")}")
                    }
                    val resp = scoreopsClient.get(path)
                    call.respond(HttpStatusCode.fromValue(resp.statusCode()), resp.body())
                }

                // Cria ou atualiza override (proxy POST scoreops)
                post("/overrides") {
                    val principal = call.requireAdminPrincipal() ?: return@post
                    val body = call.receiveText()
                    val resp = scoreopsClient.post("/tenants/${principal.tenantId}/overrides", body)
                    call.respond(HttpStatusCode.fromValue(resp.statusCode()), resp.body())
                }

                // Remove override (proxy DELETE scoreops)
                delete("/overrides/{id}") {
                    val principal = call.requireAdminPrincipal() ?: return@delete
                    val id = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id obrigatório"))
                    val resp = scoreopsClient.delete("/tenants/${principal.tenantId}/overrides/$id")
                    call.respond(HttpStatusCode.fromValue(resp.statusCode()), resp.body())
                }

                // Resolve estado efetivo de uma regra para um workload (proxy GET scoreops)
                get("/overrides/resolve") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val q = call.request.queryParameters
                    val path = buildString {
                        append("/tenants/${principal.tenantId}/overrides/resolve")
                        val params = buildList {
                            q["engine"]?.let      { add("engine=$it") }
                            q["ruleId"]?.let      { add("ruleId=$it") }
                            q["cluster"]?.let     { add("cluster=$it") }
                            q["namespace"]?.let   { add("namespace=$it") }
                            q["workloadUid"]?.let { add("workloadUid=$it") }
                        }
                        if (params.isNotEmpty()) append("?${params.joinToString("&")}")
                    }
                    val resp = scoreopsClient.get(path)
                    call.respond(HttpStatusCode.fromValue(resp.statusCode()), resp.body())
                }

                // Lê pesos dos pilares (leitura direta do banco)
                get("/weights") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val engine = call.request.queryParameters["engine"] ?: "kubernetes"
                    call.respond(scoreConfigRepo.getPillarWeights(principal.tenantId, engine))
                }

                // Atualiza pesos dos pilares (proxy PUT scoreops — valida guardrails lá)
                put("/weights") {
                    val principal = call.requireAdminPrincipal() ?: return@put
                    val body = call.receiveText()
                    val resp = scoreopsClient.put("/tenants/${principal.tenantId}/weights", body)
                    call.respond(HttpStatusCode.fromValue(resp.statusCode()), resp.body())
                }

                // Histórico de mudanças de config (proxy GET scoreops)
                get("/audit") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val q = call.request.queryParameters
                    val path = buildString {
                        append("/tenants/${principal.tenantId}/audit")
                        val params = buildList {
                            q["limit"]?.let  { add("limit=$it") }
                            q["before"]?.let { add("before=$it") }
                        }
                        if (params.isNotEmpty()) append("?${params.joinToString("&")}")
                    }
                    val resp = scoreopsClient.get(path)
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
