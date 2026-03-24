package io.titlis.api

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.titlis.api.config.AppConfig
import io.titlis.api.database.DatabaseFactory
import io.titlis.api.repository.MetricsRepository
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.SloRepository
import io.titlis.api.routes.healthRoutes
import io.titlis.api.routes.remediationRoutes
import io.titlis.api.routes.scorecardRoutes
import io.titlis.api.udp.EventRouter
import io.titlis.api.udp.UdpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    val config = AppConfig.from(environment.config)

    DatabaseFactory.init(config.database)

    val scorecardRepo   = ScorecardRepository()
    val remediationRepo = RemediationRepository()
    val sloRepo         = SloRepository()
    val metricsRepo     = MetricsRepository()

    val router    = EventRouter(scorecardRepo, remediationRepo, sloRepo, metricsRepo)
    val udpServer = UdpServer(config.udp, router)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    udpServer.start(scope)

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = false })
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to cause.message)
            )
        }
    }

    healthRoutes()
    scorecardRoutes(scorecardRepo)
    remediationRoutes(remediationRepo)
}