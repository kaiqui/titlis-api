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
import io.titlis.api.repository.FavoriteRepository
import io.titlis.api.repository.SloRepository
import io.titlis.api.repository.TagRepository
import io.titlis.api.auth.PasswordHasher
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.config.ScoreopsClient
import io.titlis.api.routes.aiConfigRoutes
import io.titlis.api.routes.aiRoutes
import io.titlis.api.routes.gitHubLinkRoutes
import io.titlis.api.routes.internalAiRoutes
import io.titlis.api.routes.internalServicemapRoutes
import io.titlis.api.routes.ragRoutes
import io.titlis.api.routes.apiKeyRoutes
import io.titlis.api.routes.authRoutes
import io.titlis.api.routes.healthRoutes
import io.titlis.api.routes.favoriteRoutes
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
import io.titlis.api.routes.gcpBillingSettingsRoutes
import io.titlis.api.routes.internalCostRoutes
import io.titlis.api.routes.costIngestRoutes
import io.titlis.api.routes.datadogSettingsRoutes
import io.titlis.api.routes.internalQueueRoutes
import io.titlis.api.routes.labelRegistryRoutes
import io.titlis.api.routes.operatorQueueRoutes
import io.titlis.api.routes.operatorDiscoveryRoutes
import io.titlis.api.routes.queueRoutes
import io.titlis.api.routes.reliabilityRoutes
import io.titlis.api.routes.coverageRoutes
import io.titlis.api.routes.CoverageService
import io.titlis.api.repository.CostRepository
import io.titlis.api.repository.DiscoveryRepository
import io.titlis.api.repository.CoverageRepository
import io.titlis.api.repository.GcpBillingConfigRepository
import io.titlis.api.repository.LabelRegistryRepository
import io.titlis.api.repository.QueueRepository
import io.titlis.api.repository.ReliabilityRepository
import io.titlis.api.repository.ServiceDefinitionRepository

import io.titlis.api.routes.sloRoutes
import io.titlis.api.routes.v2Routes
import io.titlis.api.udp.EventRouter
import io.titlis.api.auth.ClerkJwtVerifier
import io.titlis.api.repository.TeamInviteRepository
import io.titlis.api.services.ClerkProvisionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, configure = {
        connector { port = 8080 }
        responseWriteTimeoutSeconds = 300
        requestReadTimeoutSeconds   = 120
    }, module = Application::module).start(wait = true)
}

fun Application.module() {
    val config = AppConfig.from(environment.config)

    DatabaseMigrator.migrate(config.databaseMigration)
    DatabaseFactory.init(config.database)

    val adminRepo           = AdminRepository()
    val costRepo            = CostRepository()
    val gcpBillingRepo      = GcpBillingConfigRepository()
    val queueRepo           = QueueRepository()
    val discoveryRepo       = DiscoveryRepository()
    val coverageRepo        = CoverageRepository()
    val serviceDefRepo      = ServiceDefinitionRepository()
    val reliabilityRepo     = ReliabilityRepository()
    val labelRegistryRepo   = LabelRegistryRepository()
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
    val favoriteRepo     = FavoriteRepository()
    val passwordHasher   = PasswordHasher()
    val authRepo         = AuthRepository(passwordHasher)
    val scoreopsClient   = ScoreopsClient(config.scoreops.url, config.scoreops.secret)
    val coverageService  = CoverageService(coverageRepo, sloRepo, scoreopsClient)
    val tokenService    = LocalTokenService(config.auth)
    val oktaVerifier    = OktaTokenVerifier(config.auth)
    val clerkVerifier = config.clerk.jwksUrl?.let { ClerkJwtVerifier(it) }
    val requestAuthenticator = RequestAuthenticator(config.auth, authRepo, tokenService, oktaVerifier, clerkVerifier)
    val teamInviteRepo = TeamInviteRepository()
    val clerkProvisionService = ClerkProvisionService(teamInviteRepo)

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
        val origins = config.corsAllowedOrigins
        if (origins.contains("*")) {
            anyHost()
        } else {
            origins.forEach { origin ->
                val uri = io.ktor.http.Url(origin)
                allowHost(uri.hostWithPort, schemes = listOf(uri.protocol.name))
            }
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
    scorecardRoutes(scorecardRepo, favoriteRepo, tagRepo, requestAuthenticator)
    favoriteRoutes(favoriteRepo, requestAuthenticator)
    remediationRoutes(remediationRepo, requestAuthenticator)
    sloRoutes(sloRepo, requestAuthenticator)
    operatorRoutes(sloRepo, apiKeyRepo, router, requestAuthenticator)
    aiConfigRoutes(aiConfigRepo, requestAuthenticator)
    aiRoutes(scorecardRepo, aiConfigRepo, config, requestAuthenticator, coverageRepo = coverageRepo)
    gitHubLinkRoutes(scorecardRepo, aiConfigRepo, requestAuthenticator)
    ragRoutes(knowledgeRepo, config.aiService.internalSecret)
    internalAiRoutes(scorecardRepo, remediationRepo, sloRepo, config.aiService.internalSecret)
    internalServicemapRoutes(aiConfigRepo, serviceDefRepo, config.aiService.internalSecret)
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
    gcpBillingSettingsRoutes(gcpBillingRepo)
    internalCostRoutes(gcpBillingRepo, costRepo, config.cost.internalSecret)
    costIngestRoutes(costRepo, gcpBillingRepo, config.cost.internalSecret)
    queueRoutes(queueRepo, serviceDefRepo)
    reliabilityRoutes(reliabilityRepo)
    coverageRoutes(coverageService, coverageRepo, config.aiService.internalSecret)
    internalQueueRoutes(queueRepo, config.scoreops.secret)
    operatorQueueRoutes(queueRepo, labelRegistryRepo, aiConfigRepo, apiKeyRepo, scoreopsClient, scope)
    operatorDiscoveryRoutes(discoveryRepo, apiKeyRepo)
    datadogSettingsRoutes(aiConfigRepo, queueRepo)
    labelRegistryRoutes(labelRegistryRepo)
    v2Routes(clerkVerifier, config.clerk.webhookSecret, teamInviteRepo, clerkProvisionService, scorecardRepo, aiConfigRepo, apiKeyRepo, config)
}
