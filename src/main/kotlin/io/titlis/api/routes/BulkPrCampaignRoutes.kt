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
import io.titlis.api.repository.CampaignRepository

fun Application.bulkPrCampaignRoutes(
    campaignRepo: CampaignRepository,
    prbotClient: PrbotClient,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            post("/v1/bulk-pr/campaigns") {
                call.requireAdminPrincipal() ?: return@post
                val body = call.receiveText()
                val (status, resp) = prbotClient.proxy("POST", "/v1/campaigns", body)
                call.respond(HttpStatusCode.fromValue(status), resp)
            }

            get("/v1/bulk-pr/campaigns") {
                val principal = call.principal<AppPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val campaigns = campaignRepo.list(principal.tenantId)
                call.respond(HttpStatusCode.OK, campaigns)
            }

            get("/v1/bulk-pr/campaigns/{id}") {
                val principal = call.principal<AppPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val campaign = campaignRepo.findById(id, principal.tenantId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(HttpStatusCode.OK, campaign)
            }

            post("/v1/bulk-pr/campaigns/{id}/cancel") {
                val principal = call.requireAdminPrincipal() ?: return@post
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val (status, resp) = prbotClient.proxy("POST", "/v1/campaigns/$id/cancel", null)
                call.respond(HttpStatusCode.fromValue(status), resp)
            }
        }
    }
}
