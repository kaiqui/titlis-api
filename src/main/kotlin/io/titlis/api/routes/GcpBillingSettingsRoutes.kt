package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireAdminPrincipal
import io.titlis.api.repository.GcpBillingConfigRepository
import kotlinx.serialization.Serializable

@Serializable
data class SaveGcpBillingRequest(
    val billingAccountId: String,
    val projectId: String,
    val bigqueryDataset: String,
    val bigqueryLocation: String = "US",
    val serviceAccountJson: String,
)

fun Application.gcpBillingSettingsRoutes(
    gcpBillingRepo: GcpBillingConfigRepository,
) {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            route("/v1/settings/gcp-billing") {

                get("/status") {
                    val principal = call.requireAdminPrincipal() ?: return@get
                    val status = gcpBillingRepo.getStatus(principal.tenantId)
                    call.respond(status)
                }

                put("") {
                    val principal = call.requireAdminPrincipal() ?: return@put
                    val req = call.receive<SaveGcpBillingRequest>()

                    if (req.billingAccountId.isBlank() || req.projectId.isBlank() ||
                        req.bigqueryDataset.isBlank() || req.serviceAccountJson.isBlank()
                    ) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "billing_account_id, project_id, bigquery_dataset and service_account_json are required"),
                        )
                        return@put
                    }

                    gcpBillingRepo.upsert(
                        tenantId         = principal.tenantId,
                        billingAccountId = req.billingAccountId,
                        projectId        = req.projectId,
                        bigqueryDataset  = req.bigqueryDataset,
                        bigqueryLocation = req.bigqueryLocation.ifBlank { "US" },
                        credentialsEnc   = req.serviceAccountJson,
                    )
                    call.respond(mapOf("configured" to true))
                }
            }
        }
    }
}
