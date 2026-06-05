package io.titlis.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.slf4j.event.Level
import io.titlis.api.auth.appAuth
import io.titlis.api.auth.LocalTokenService
import io.titlis.api.auth.OktaTokenVerifier
import io.titlis.api.auth.oktaJwtAuth
import io.titlis.api.config.AppConfig
import io.titlis.api.database.DatabaseFactory
import io.titlis.api.database.DatabaseMigrator
import io.titlis.api.repository.AdminRepository
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.CampaignRepository
import io.titlis.api.repository.KnowledgeRepository
import io.titlis.api.repository.AuthRepository
import io.titlis.api.repository.MetricsRepository
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScoreConfigRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.SloRepository
import io.titlis.api.repository.TagRepository
import io.titlis.api.auth.PasswordHasher
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.config.ScoreopsClient
import io.titlis.api.routes.aiConfigRoutes
import io.titlis.api.routes.aiRoutes
import io.titlis.api.routes.gitHubLinkRoutes
import io.titlis.api.routes.internalAiRoutes
import io.titlis.api.routes.ragRoutes
import io.titlis.api.routes.apiKeyRoutes
import io.titlis.api.routes.authRoutes
import io.titlis.api.routes.healthRoutes
import io.titlis.api.routes.remediationRoutes
import io.titlis.api.routes.scorecardRoutes
import io.titlis.api.routes.settingsAuthRoutes
import io.titlis.api.routes.settingsScoreConfigRoutes
import io.titlis.api.routes.settingsTagsRoutes
import io.titlis.api.routes.settingsTagPoliciesRoutes
import io.titlis.api.routes.bulkPrCampaignRoutes
import io.titlis.api.routes.internalPrbotRoutes
import io.titlis.api.routes.adminRoutes
import io.titlis.api.routes.internalScorecardRoutes
import io.titlis.api.routes.operatorRoutes
import io.titlis.api.routes.operatorScoringRoutes
import io.titlis.api.routes.settingsInsightsRoutes
import io.titlis.api.routes.settingsPrbotRoutes
import io.titlis.api.routes.internalInsightsRoutes

import io.titlis.api.routes.sloRoutes
import io.titlis.api.udp.EventRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, configure = {
        connector { port = 8080 }
        responseWriteTimeoutSeconds = 300
        requestReadTimeoutSeconds = 30
    }, module = Application::module).start(wait = true)
}

fun Application.module() {
    val config = AppConfig.from(environment.config)

    DatabaseMigrator.migrate(config.databaseMigration)
    DatabaseFactory.init(config.database)

    val adminRepo         = AdminRepository()
    val scorecardRepo    = ScorecardRepository()
    val remediationRepo  = RemediationRepository()
    val sloRepo          = SloRepository()
    val metricsRepo      = MetricsRepository()
    val apiKeyRepo       = ApiKeyRepository()
    val aiConfigRepo     = AiConfigRepository()
    val knowledgeRepo    = KnowledgeRepository()
    val scoreConfigRepo  = ScoreConfigRepository()
    val tagRepo          = TagRepository()
    val campaignRepo     = CampaignRepository()
    val passwordHasher   = PasswordHasher()
    val authRepo         = AuthRepository(passwordHasher)
    val scoreopsClient   = ScoreopsClient(config.scoreops.url, config.scoreops.secret)
    val tokenService    = LocalTokenService(config.auth)
    val oktaVerifier    = OktaTokenVerifier(config.auth)
    val requestAuthenticator = RequestAuthenticator(config.auth, authRepo, tokenService, oktaVerifier)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val router = EventRouter(scorecardRepo, remediationRepo, sloRepo, metricsRepo, apiKeyRepo, scope, campaignRepo)

    install(CallLogging) {
        level = Level.INFO
        mdc("http.method") { it.request.httpMethod.value }
        mdc("http.path") { it.request.uri.substringBefore("?") }
        mdc("http.status") { it.response.status()?.value?.toString() ?: "unknown" }
        filter { !it.request.uri.startsWith("/health") && !it.request.uri.startsWith("/ready") }
    }

    install(CORS) {
        config.corsAllowedOrigins.forEach { origin ->
            val uri = io.ktor.http.Url(origin)
            allowHost(uri.hostWithPort, schemes = listOf(uri.protocol.name))
        }
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Titlis-Tenant-Slug")
        allowHeader("X-Dev-Auth")
        allowHeader("X-Dev-Tenant-Id")
        allowHeader("X-Dev-User")
        allowHeader("X-Dev-Roles")
        allowCredentials = true
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = false; coerceInputValues = true; encodeDefaults = true })
    }

    install(Authentication) {
        appAuth(requestAuthenticator)
        oktaJwtAuth(oktaVerifier, authRepo)
    }

    install(StatusPages) {
        val log = org.slf4j.LoggerFactory.getLogger("StatusPages")
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception on ${call.request.httpMethod.value} ${call.request.path()}", cause)
            call.respond(
                io.ktor.http.HttpStatusCode.InternalServerError,
                mapOf("error" to cause.message)
            )
        }
    }

    healthRoutes()
    adminRoutes(adminRepo, requestAuthenticator)
    authRoutes(authRepo, tokenService, requestAuthenticator, apiKeyRepo, oktaVerifier)
    settingsAuthRoutes(authRepo)
    apiKeyRoutes(apiKeyRepo)
    scorecardRoutes(scorecardRepo, requestAuthenticator)
    remediationRoutes(remediationRepo, requestAuthenticator)
    sloRoutes(sloRepo, requestAuthenticator)
    operatorRoutes(sloRepo, apiKeyRepo, router, requestAuthenticator)
    aiConfigRoutes(aiConfigRepo, requestAuthenticator)
    aiRoutes(scorecardRepo, aiConfigRepo, config, requestAuthenticator)
    gitHubLinkRoutes(scorecardRepo, aiConfigRepo, requestAuthenticator)
    ragRoutes(knowledgeRepo, config.aiService.internalSecret)
    internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, config.aiService.internalSecret)
    settingsScoreConfigRoutes(scoreopsClient, scoreConfigRepo, requestAuthenticator)
    settingsTagsRoutes(tagRepo)
    settingsTagPoliciesRoutes(scoreopsClient, requestAuthenticator)
    operatorScoringRoutes(scoreopsClient, apiKeyRepo, tagRepo, sloRepo, scope)
    bulkPrCampaignRoutes(campaignRepo)
    settingsPrbotRoutes()
    settingsInsightsRoutes(aiConfigRepo)
    internalInsightsRoutes(aiConfigRepo, config.aiService.internalSecret)
    internalPrbotRoutes(scorecardRepo, aiConfigRepo, config.aiService.internalSecret)
    internalScorecardRoutes(scorecardRepo, remediationRepo, config.scoreops.secret)
}
