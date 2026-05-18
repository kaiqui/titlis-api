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
import io.titlis.api.config.InsightsClient

fun Application.settingsInsightsRoutes(
    insightsClient: InsightsClient,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            get("/v1/settings/hpa-templates") {
                val principal = call.principal<AppPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val (status, body) = insightsClient.proxy("GET", "/v1/tenants/${principal.tenantId}/hpa-templates", null)
                call.respond(HttpStatusCode.fromValue(status), body)
            }

            put("/v1/settings/hpa-templates") {
                val principal = call.requireAdminPrincipal() ?: return@put
                val reqBody = call.receiveText()
                val (status, body) = insightsClient.proxy("PUT", "/v1/tenants/${principal.tenantId}/hpa-templates", reqBody)
                call.respond(HttpStatusCode.fromValue(status), body)
            }

            get("/v1/insights/recommendations/hpa/preview") {
                val principal = call.principal<AppPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val workloadUid = call.request.queryParameters["workload_uid"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val environment = call.request.queryParameters["environment"] ?: "prd"
                val criticality = call.request.queryParameters["criticality"] ?: "medium"
                val (status, body) = insightsClient.proxy(
                    "GET",
                    "/v1/recommendations/hpa?tenant_id=${principal.tenantId}&workload_uid=$workloadUid&environment=$environment&criticality=$criticality",
                    null,
                )
                call.respond(HttpStatusCode.fromValue(status), body)
            }

            get("/v1/insights/datadog/probe") {
                val principal = call.requireAdminPrincipal() ?: return@get
                val (status, body) = insightsClient.proxy("GET", "/v1/datadog/probe?tenant_id=${principal.tenantId}", null)
                call.respond(HttpStatusCode.fromValue(status), body)
            }
        }
    }
}
