package io.titlis.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.titlis.api.auth.appAuth
import io.titlis.api.auth.LocalTokenService
import io.titlis.api.auth.OktaTokenVerifier
import io.titlis.api.auth.oktaJwtAuth
import io.titlis.api.config.AppConfig
import io.titlis.api.database.DatabaseFactory
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.AuthRepository
import io.titlis.api.repository.MetricsRepository
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.SloRepository
import io.titlis.api.auth.PasswordHasher
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.routes.apiKeyRoutes
import io.titlis.api.routes.authRoutes
import io.titlis.api.routes.healthRoutes
import io.titlis.api.routes.remediationRoutes
import io.titlis.api.routes.scorecardRoutes
import io.titlis.api.routes.settingsAuthRoutes
import io.titlis.api.routes.sloRoutes
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
    val apiKeyRepo      = ApiKeyRepository()
    val passwordHasher  = PasswordHasher()
    val authRepo        = AuthRepository(passwordHasher)
    val tokenService    = LocalTokenService(config.auth)
    val oktaVerifier    = OktaTokenVerifier(config.auth)
    val requestAuthenticator = RequestAuthenticator(config.auth, authRepo, tokenService, oktaVerifier)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val router    = EventRouter(scorecardRepo, remediationRepo, sloRepo, metricsRepo, apiKeyRepo, scope)
    val udpServer = UdpServer(config.udp, router)
    udpServer.start(scope)

    install(CORS) {
        config.corsAllowedOrigins.forEach { origin ->
            val uri = io.ktor.http.Url(origin)
            allowHost(uri.host, schemes = listOf(uri.protocol.name))
        }
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Dev-Auth")
        allowHeader("X-Dev-Tenant-Id")
        allowHeader("X-Dev-User")
        allowHeader("X-Dev-Roles")
        allowCredentials = true
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = false })
    }

    install(Authentication) {
        appAuth(requestAuthenticator)
        oktaJwtAuth(oktaVerifier, authRepo)
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
    authRoutes(authRepo, tokenService, requestAuthenticator, apiKeyRepo)
    settingsAuthRoutes(authRepo)
    apiKeyRoutes(apiKeyRepo)
    scorecardRoutes(scorecardRepo, requestAuthenticator)
    remediationRoutes(remediationRepo, requestAuthenticator)
    sloRoutes(sloRepo, requestAuthenticator)
}
