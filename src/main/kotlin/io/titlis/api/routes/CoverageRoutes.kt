package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireRole
import io.titlis.api.config.ScoreopsClient
import io.titlis.api.repository.CoverageRepository
import io.titlis.api.repository.SloRepository
import org.slf4j.LoggerFactory

// Orquestra o sweep de coverage: monta snapshots do grafo, enriquece SLO, pontua no scoreops e
// persiste. A geração de findings é determinística (scoreops) — sem IA.
class CoverageService(
    private val coverageRepo: CoverageRepository,
    private val sloRepo: SloRepository,
    private val scoreops: ScoreopsClient,
) {
    private val log = LoggerFactory.getLogger(CoverageService::class.java)

    suspend fun runSweep(tenantId: Long): Int {
        val snapshots = coverageRepo.buildSnapshots(tenantId)
        var evaluated = 0
        for (snap in snapshots) {
            val (hasSlo, sloHealthy) = sloRepo.sloPresenceForNamespace(snap.cluster, snap.namespace, tenantId)
            val enriched = snap.copy(found = snap.found.copy(hasSlo = hasSlo, sloHealthy = sloHealthy))
            try {
                val result = scoreops.evaluateCoverage(enriched)
                coverageRepo.upsertResult(result)
                evaluated++
            } catch (e: Exception) {
                log.warn("coverage eval failed uid={} tenant={}", snap.workloadUid, tenantId, e)
            }
        }
        log.info("coverage sweep done tenant={} evaluated={}/{}", tenantId, evaluated, snapshots.size)
        return evaluated
    }
}

fun Application.coverageRoutes(
    coverageService: CoverageService,
    coverageRepo: CoverageRepository,
    internalSecret: String,
) {
    routing {
        // Trigger interno do sweep (X-Internal-Secret). Idempotente.
        post("/v1/internal/coverage/evaluate") {
            if (call.request.headers["X-Internal-Secret"] != internalSecret) {
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            }
            val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tenantId required"))
            val n = coverageService.runSweep(tenantId)
            call.respond(mapOf("evaluated" to n))
        }

        // Leitura para a UI (JWT) — precisa do plugin de auth para popular o principal.
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            route("/v1/coverage") {
                get {
                    val principal = call.requireRole() ?: return@get
                    call.respond(coverageRepo.listForTenant(principal.tenantId))
                }

                // Top-N riscos (menor Trust Score primeiro) — MVP §9.
                get("/top-risks") {
                    val principal = call.requireRole() ?: return@get
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 100)
                    call.respond(coverageRepo.topRisks(principal.tenantId, limit))
                }
            }

            // H1 — service-map do hub: produto → squad → serviço → score + bucket de órfãos.
            get("/v1/service-map") {
                val principal = call.requireRole() ?: return@get
                call.respond(coverageRepo.buildServiceMap(principal.tenantId))
            }
        }
    }
}
