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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val bulkLog = LoggerFactory.getLogger("BulkPrCampaignRoutes")
private val bulkJson = Json { ignoreUnknownKeys = true }

fun Application.bulkPrCampaignRoutes(
    campaignRepo: CampaignRepository,
    prbotClient: PrbotClient,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            post("/v1/bulk-pr/campaigns") {
                val principal = call.requireAdminPrincipal() ?: return@post
                val body = call.receiveText()
                val (status, resp) = prbotClient.proxy("POST", "/v1/campaigns", body)
                // On success, persist the campaign so GET /v1/bulk-pr/campaigns returns it.
                if (status in 200..299) {
                    try {
                        val req = bulkJson.parseToJsonElement(body).jsonObject
                        val res = bulkJson.parseToJsonElement(resp).jsonObject
                        val campaignId = res["campaign_id"]?.jsonPrimitive?.content ?: ""
                        val workflowId = res["workflow_id"]?.jsonPrimitive?.content ?: campaignId
                        if (campaignId.isNotEmpty()) {
                            campaignRepo.insert(
                                id             = campaignId,
                                tenantId       = principal.tenantId,
                                workflowId     = workflowId,
                                actorEmail     = principal.email,
                                triggerSource  = req["trigger_source"]?.jsonPrimitive?.content ?: "manual",
                                ruleId         = req["rule_id"]?.jsonPrimitive?.content,
                                title          = req["title"]?.jsonPrimitive?.content ?: "HPA Campaign",
                                description    = req["description"]?.jsonPrimitive?.content,
                                status         = "RUNNING",
                                idempotencyKey = req["idempotency_key"]?.jsonPrimitive?.content ?: campaignId,
                                totalItems     = req["items"]?.jsonArray?.size ?: 0,
                            )
                        }
                    } catch (e: Exception) {
                        bulkLog.warn("Could not persist campaign after prbot accept: ${e.message}")
                    }
                }
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

            // Returns list of workloads that have a .titlis/service.yaml discovered by prbot.
            // Used by the UI to show ServiceDefinition adherence badges.
            get("/v1/bulk-pr/service-definitions") {
                val principal = call.principal<AppPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val (status, resp) = prbotClient.proxy("GET", "/v1/mappings?tenant_id=${principal.tenantId}", null)
                call.respond(HttpStatusCode.fromValue(status), resp)
            }
        }
    }
}
