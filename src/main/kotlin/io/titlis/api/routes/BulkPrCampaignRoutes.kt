package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.repository.CampaignRepository

// Bulk PR campaigns — criação via titlis-prbot (Temporal) foi descontinuada.
// Remediações agora são realizadas de forma conversacional pelo titlis-ai via MCP GitHub.
// Rotas de leitura mantidas para consulta de campanhas históricas.
// Rotas de escrita retornam 410 Gone.

private val goneCampaigns = mapOf(
    "error" to "gone",
    "message" to "Campanhas automáticas em frota foram descontinuadas. Use o assistente IA para remediações conversacionais.",
)

fun Application.bulkPrCampaignRoutes(
    campaignRepo: CampaignRepository,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {

            // ── Escrita — descontinuada (410 Gone) ──────────────────────────────
            post("/v1/bulk-pr/campaigns") {
                call.respond(HttpStatusCode.Gone, goneCampaigns)
            }

            post("/v1/bulk-pr/campaigns/{id}/cancel") {
                call.respond(HttpStatusCode.Gone, goneCampaigns)
            }

            post("/v1/bulk-pr/manifest-campaigns") {
                call.respond(HttpStatusCode.Gone, goneCampaigns)
            }

            get("/v1/bulk-pr/service-definitions") {
                call.respond(HttpStatusCode.Gone, goneCampaigns)
            }

            // ── Leitura — mantida para histórico ────────────────────────────────
            get("/v1/bulk-pr/campaigns") {
                val principal = call.principal<AppPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val campaigns = campaignRepo.list(principal.tenantId)
                call.respond(HttpStatusCode.OK, campaigns)
            }

            get("/v1/bulk-pr/campaigns/{id}") {
                val principal = call.principal<AppPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val campaign = campaignRepo.findById(id, principal.tenantId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(HttpStatusCode.OK, campaign)
            }

            get("/v1/bulk-pr/campaigns/{id}/items") {
                val principal = call.principal<AppPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val items = campaignRepo.listItems(id, principal.tenantId)
                call.respond(HttpStatusCode.OK, items)
            }
        }
    }
}
