package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.config.PrbotClient

fun Application.settingsPrbotRoutes(
    prbotClient: PrbotClient,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            get("/v1/settings/auto-remediation") {
                val principal = call.principal<AppPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val (status, body) = prbotClient.proxy("GET", "/v1/tenants/${principal.tenantId}/policies?rule_id=PERF-004", null)
                if (status == 200) {
                    call.respond(HttpStatusCode.OK, "[$body]")
                } else {
                    call.respond(HttpStatusCode.OK, "[]")
                }
            }

            put("/v1/settings/auto-remediation") {
                val principal = call.requireAdminPrincipal() ?: return@put
                val reqBody = call.receiveText()
                val (status, body) = prbotClient.proxy("PUT", "/v1/tenants/${principal.tenantId}/policies", reqBody)
                call.respond(HttpStatusCode.fromValue(status), body)
            }

            get("/v1/settings/gitops-profiles") {
                val principal = call.principal<AppPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val (status, body) = prbotClient.proxy("GET", "/v1/gitops-profiles?tenant_id=${principal.tenantId}", null)
                call.respond(HttpStatusCode.fromValue(status), body)
            }

            put("/v1/settings/gitops-profiles") {
                call.requireAdminPrincipal() ?: return@put
                val reqBody = call.receiveText()
                val (status, body) = prbotClient.proxy("PUT", "/v1/gitops-profiles", reqBody)
                call.respond(HttpStatusCode.fromValue(status), body)
            }

            get("/v1/mappings") {
                val principal = call.principal<AppPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val (status, body) = prbotClient.proxy("GET", "/v1/mappings?tenant_id=${principal.tenantId}", null)
                call.respond(HttpStatusCode.fromValue(status), body)
            }

            post("/v1/mappings/refresh") {
                val principal = call.requireAdminPrincipal() ?: return@post
                val (status, body) = prbotClient.proxy("POST", "/v1/mappings/refresh?tenant_id=${principal.tenantId}", null)
                call.respond(HttpStatusCode.fromValue(status), body)
            }

            get("/v1/settings/github-app/install-url") {
                val principal = call.requireAdminPrincipal() ?: return@get
                val (status, body) = prbotClient.proxy("GET", "/v1/github-app/install-url?tenant_id=${principal.tenantId}", null)
                call.respond(HttpStatusCode.fromValue(status), body)
            }
        }
    }
}
