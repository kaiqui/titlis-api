package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.domain.AssetGraphRequest
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.DiscoveryRepository
import org.slf4j.LoggerFactory

// Discovery Engine — ingestão do grafo de ativos do titlis-operator-go.
// Auth por API key (resolve o tenant; nunca vem do payload — regra 9).
fun Application.operatorDiscoveryRoutes(
    discoveryRepo: DiscoveryRepository,
    apiKeyRepo: ApiKeyRepository,
) {
    val log = LoggerFactory.getLogger("OperatorDiscoveryRoutes")
    routing {
        route("/v1/operator/discovery") {
            post("/assets") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_api_key"))
                val req = runCatching { call.receive<AssetGraphRequest>() }
                    .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_payload")) }

                val result = discoveryRepo.ingestAssetGraph(tenantId, req)
                log.info(
                    "discovery ingest tenant={} cluster={} assets={} relations={} deactivatedAssets={} deactivatedRelations={}",
                    tenantId, req.cluster, result.assetsUpserted, result.relationsUpserted,
                    result.assetsDeactivated, result.relationsDeactivated,
                )
                call.respond(result)
            }
        }
    }
}
