package io.titlis.api.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.titlis.api.auth.ClerkIdentity
import io.titlis.api.auth.ClerkJwtVerifier
import io.titlis.api.config.AppConfig
import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.PlatformUsers
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.TeamInviteRepository
import io.titlis.api.repository.TenantAiConfigRecord
import io.titlis.api.services.ClerkProvisionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val log = LoggerFactory.getLogger("V2Routes")
private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class AddInviteRequest(val email: String, val role: String = "viewer")

@Serializable
private data class UpdateRoleRequest(val role: String)

fun Application.v2Routes(
    clerkVerifier: ClerkJwtVerifier?,
    clerkWebhookSecret: String?,
    teamInviteRepo: TeamInviteRepository,
    provisionService: ClerkProvisionService,
    scorecardRepo: ScorecardRepository,
    aiConfigRepo: AiConfigRepository,
    apiKeyRepo: ApiKeyRepository,
    appConfig: AppConfig,
    httpClient: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    routing {
        route("/v2") {

            // ─── Webhook Clerk (sem auth — verificado por svix-signature) ─────────────
            post("/webhooks/clerk") {
                val body = call.receiveText()

                if (clerkWebhookSecret != null) {
                    val svixId        = call.request.headers["svix-id"]
                    val svixTimestamp = call.request.headers["svix-timestamp"]
                    val svixSignature = call.request.headers["svix-signature"]

                    if (svixId == null || svixTimestamp == null || svixSignature == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_svix_headers"))
                        return@post
                    }

                    if (!verifySvixSignature(svixId, svixTimestamp, body, svixSignature, clerkWebhookSecret)) {
                        log.warn("clerk_webhook invalid_signature svix_id={}", svixId)
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_signature"))
                        return@post
                    }
                }

                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val eventType = root?.get("type")?.jsonPrimitive?.content
                val data = root?.get("data")?.jsonObject

                when (eventType) {
                    "user.deleted" -> {
                        val clerkUserId = data?.get("id")?.jsonPrimitive?.content
                        if (clerkUserId != null) {
                            dbQuery {
                                PlatformUsers.update({ PlatformUsers.clerkUserId eq clerkUserId }) {
                                    it[deletedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                                }
                            }
                            log.info("clerk_webhook user.deleted clerk_user_id={}", clerkUserId)
                        }
                    }
                    "user.updated" -> {
                        val clerkUserId = data?.get("id")?.jsonPrimitive?.content
                        val primaryEmailId = data?.get("primary_email_address_id")?.jsonPrimitive?.content
                        val emailAddresses = data?.get("email_addresses")?.jsonArray
                        if (clerkUserId != null && primaryEmailId != null && emailAddresses != null) {
                            val newEmail = emailAddresses
                                .mapNotNull { it.jsonObject }
                                .firstOrNull { it["id"]?.jsonPrimitive?.content == primaryEmailId }
                                ?.get("email_address")?.jsonPrimitive?.content
                            if (newEmail != null) {
                                dbQuery {
                                    PlatformUsers.update({ PlatformUsers.clerkUserId eq clerkUserId }) {
                                        it[email] = newEmail
                                    }
                                }
                                log.info("clerk_webhook user.updated clerk_user_id={} new_email={}", clerkUserId, newEmail)
                            }
                        }
                    }
                    else -> log.info("clerk_webhook received event={}", eventType)
                }

                call.respond(HttpStatusCode.OK, mapOf("received" to true))
            }

            // ─── Provisão (Clerk JWT — sem usuário pré-existente) ─────────────────────
            post("/auth/provision") {
                val verifier = clerkVerifier ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "clerk_not_configured"))
                    return@post
                }
                val identity = call.extractClerkIdentity(verifier) ?: return@post
                val result = provisionService.provision(identity)
                call.respond(HttpStatusCode.OK, result)
            }

            // ─── Dashboard (workloads com scores) ─────────────────────────────────────
            get("/dashboard") {
                val principal = call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@get
                val cluster = call.request.queryParameters["cluster"]
                val dashboard = scorecardRepo.getDashboard(principal.tenantId, cluster)
                call.respondJson(dashboard)
            }

            // ─── Scorecard detail por workload ────────────────────────────────────────
            get("/workloads/{workloadId}/scorecard") {
                val principal = call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@get
                val workloadId = call.parameters["workloadId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "workloadId_required"))
                val scorecard = scorecardRepo.getByWorkloadId(workloadId, principal.tenantId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
                call.respondJson(scorecard)
            }

            // ─── AI proxy — chat ──────────────────────────────────────────────────────
            post("/ai/agent/chat") {
                val principal = call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@post
                val body = call.receive<AgentChatRequest>()
                val aiConfig = aiConfigRepo.getByTenant(principal.tenantId)
                    ?: return@post call.respond(
                        HttpStatusCode(424, "Failed Dependency"),
                        mapOf("error" to "ai_not_configured"),
                    )
                val aiRequest = HttpRequest.newBuilder()
                    .uri(URI.create("${appConfig.aiService.url}/v1/agent/chat"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", appConfig.aiService.internalSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(buildV2AgentChatPayload(principal.tenantId, body, aiConfig)))
                    .build()
                call.respondAiSse(httpClient, aiRequest)
            }

            // ─── AI proxy — tool decisions ────────────────────────────────────────────
            post("/ai/agent/{sessionId}/tools/respond") {
                call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@post
                val sessionId = call.parameters["sessionId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId_required"))
                val body = call.receive<AgentToolsRespondRequest>()
                val aiRequest = HttpRequest.newBuilder()
                    .uri(URI.create("${appConfig.aiService.url}/v1/agent/$sessionId/tools/respond"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", appConfig.aiService.internalSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(buildV2ToolsRespondPayload(body)))
                    .build()
                call.respondAiSse(httpClient, aiRequest)
            }

            // ─── AI proxy — remediação ────────────────────────────────────────────────
            post("/ai/workloads/{workloadId}/remediate") {
                val principal = call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@post
                val workloadId = call.parameters["workloadId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "workloadId_required"))
                scorecardRepo.getByWorkloadId(workloadId, principal.tenantId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "workload_not_found"))
                val aiConfig = aiConfigRepo.getByTenant(principal.tenantId)
                    ?: return@post call.respond(
                        HttpStatusCode(424, "Failed Dependency"),
                        mapOf("error" to "ai_not_configured"),
                    )
                val body = call.receive<RemediateFindingsRequest>()
                val aiRequest = HttpRequest.newBuilder()
                    .uri(URI.create("${appConfig.aiService.url}/v1/remediate"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", appConfig.aiService.internalSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(buildV2RemediatePayload(principal.tenantId, workloadId, body, aiConfig)))
                    .build()
                call.respondAiSse(httpClient, aiRequest)
            }

            // ─── AI proxy — confirmação de remediação ─────────────────────────────────
            post("/ai/remediate/{threadId}/confirm") {
                call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@post
                val threadId = call.parameters["threadId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "threadId_required"))
                val body = call.receive<ConfirmRemediationRequest>()
                val aiRequest = HttpRequest.newBuilder()
                    .uri(URI.create("${appConfig.aiService.url}/v1/remediate/$threadId/confirm"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", appConfig.aiService.internalSecret)
                    .POST(HttpRequest.BodyPublishers.ofString("""{"approved":${body.approved}}"""))
                    .build()
                call.respondAiSse(httpClient, aiRequest)
            }

            // ─── Team Invites (Clerk JWT + admin only) ────────────────────────────────
            get("/team/invites") {
                val verifier = clerkVerifier ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "clerk_not_configured"))
                    return@get
                }
                val principal = call.requireClerkPrincipal(verifier, teamInviteRepo) ?: return@get
                if (!principal.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                    return@get
                }
                val invites = teamInviteRepo.listByTenant(principal.tenantId)
                call.respond(HttpStatusCode.OK, invites)
            }

            post("/team/invites") {
                val verifier = clerkVerifier ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "clerk_not_configured"))
                    return@post
                }
                val principal = call.requireClerkPrincipal(verifier, teamInviteRepo) ?: return@post
                if (!principal.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                    return@post
                }
                val req = runCatching { json.decodeFromString<AddInviteRequest>(call.receiveText()) }.getOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                        return@post
                    }
                if (req.email.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "email_required"))
                    return@post
                }
                val validRoles = setOf("admin", "viewer", "engineer", "pm")
                if (req.role !in validRoles) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_role"))
                    return@post
                }
                val invite = runCatching {
                    teamInviteRepo.create(principal.tenantId, req.email, req.role)
                }.getOrElse {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "email_already_invited"))
                    return@post
                }
                call.respond(HttpStatusCode.Created, invite)
            }

            put("/team/invites/{email}") {
                val verifier = clerkVerifier ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "clerk_not_configured"))
                    return@put
                }
                val principal = call.requireClerkPrincipal(verifier, teamInviteRepo) ?: return@put
                if (!principal.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                    return@put
                }
                val email = call.parameters["email"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "email_required"))
                    return@put
                }
                val req = runCatching { json.decodeFromString<UpdateRoleRequest>(call.receiveText()) }.getOrNull()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                        return@put
                    }
                val updated = teamInviteRepo.updateRole(principal.tenantId, email, req.role)
                if (!updated) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "invite_not_found"))
                    return@put
                }
                call.respond(HttpStatusCode.OK, mapOf("updated" to true))
            }

            delete("/team/invites/{email}") {
                val verifier = clerkVerifier ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "clerk_not_configured"))
                    return@delete
                }
                val principal = call.requireClerkPrincipal(verifier, teamInviteRepo) ?: return@delete
                if (!principal.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                    return@delete
                }
                val email = call.parameters["email"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "email_required"))
                    return@delete
                }
                val deleted = teamInviteRepo.delete(principal.tenantId, email)
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "invite_not_found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }

            // ─── API Keys do operator (Clerk JWT + admin) ─────────────────────────────
            // Reusa ApiKeyRepository e os data classes de ApiKeyRoutes.kt (mesmo package).
            // O tenantId vem do principal Clerk — nunca do payload (isolamento multi-tenant).
            get("/settings/api-keys") {
                val principal = call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@get
                if (!principal.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                    return@get
                }
                val keys = apiKeyRepo.listByTenant(principal.tenantId)
                call.respond(keys.map { it.toApiKeyListItem() })
            }

            get("/settings/api-keys/connection-status") {
                val principal = call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@get
                if (!principal.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                    return@get
                }
                val keys = apiKeyRepo.listByTenant(principal.tenantId)
                val lastEventAt = apiKeyRepo.lastEventAt(principal.tenantId)
                call.respond(
                    ApiKeyConnectionStatus(
                        connected = lastEventAt != null,
                        lastEventAt = lastEventAt?.toString(),
                        activeKeyCount = keys.size,
                    ),
                )
            }

            post("/settings/api-keys") {
                val principal = call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@post
                if (!principal.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                    return@post
                }
                val body = runCatching { json.decodeFromString<CreateApiKeyRequest>(call.receiveText()) }
                    .getOrElse { CreateApiKeyRequest(description = null) }
                val (record, rawToken) = apiKeyRepo.create(
                    tenantId        = principal.tenantId,
                    description     = body.description,
                    createdByUserId = principal.platformUserId,
                )
                call.respond(
                    HttpStatusCode.Created,
                    CreateApiKeyResponse(
                        id          = record.apiKeyId,
                        prefix      = record.keyPrefix,
                        description = record.description,
                        rawToken    = rawToken,
                        createdAt   = record.createdAt.toString(),
                    ),
                )
            }

            delete("/settings/api-keys/{id}") {
                val principal = call.requireClerkPrincipal(clerkVerifier, teamInviteRepo) ?: return@delete
                if (!principal.isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
                    return@delete
                }
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_api_key_id"))
                val ok = apiKeyRepo.revoke(id, principal.tenantId)
                call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            }
        }
    }
}

private fun io.titlis.api.repository.ApiKeyRecord.toApiKeyListItem() = ApiKeyListItem(
    id          = apiKeyId,
    prefix      = keyPrefix,
    description = description,
    isActive    = isActive,
    lastUsedAt  = lastUsedAt?.toString(),
    createdAt   = createdAt.toString(),
)

// ─── Helpers de autenticação Clerk ───────────────────────────────────────────

private data class ClerkPrincipal(
    val clerkUserId: String,
    val platformUserId: Long,
    val tenantId: Long,
    val role: String,
) {
    val isAdmin: Boolean get() = role == "admin"
}

private suspend fun ApplicationCall.extractClerkIdentity(
    verifier: ClerkJwtVerifier,
): ClerkIdentity? {
    val token = request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.trim()
        ?: run {
            respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_token"))
            return null
        }
    val identity = verifier.verify(token) ?: run {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token"))
        return null
    }
    return identity
}

private suspend fun ApplicationCall.requireClerkPrincipal(
    verifier: ClerkJwtVerifier?,
    teamInviteRepo: TeamInviteRepository,
): ClerkPrincipal? {
    val actualVerifier = verifier ?: run {
        respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "clerk_not_configured"))
        return null
    }
    val identity = extractClerkIdentity(actualVerifier) ?: return null

    val user = dbQuery {
        PlatformUsers
            .select(PlatformUsers.platformUserId, PlatformUsers.tenantId, PlatformUsers.platformRole)
            .where {
                (PlatformUsers.clerkUserId eq identity.clerkUserId) and
                PlatformUsers.deletedAt.isNull()
            }
            .limit(1)
            .singleOrNull()
    }

    if (user == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "user_not_provisioned"))
        return null
    }

    return ClerkPrincipal(
        clerkUserId = identity.clerkUserId,
        platformUserId = user[PlatformUsers.platformUserId],
        tenantId = user[PlatformUsers.tenantId],
        role = user[PlatformUsers.platformRole],
    )
}

// ─── SSE proxy helper ─────────────────────────────────────────────────────────

private suspend fun ApplicationCall.respondAiSse(
    httpClient: HttpClient,
    aiRequest: HttpRequest,
) {
    response.headers.append("Cache-Control", "no-cache")
    response.headers.append("X-Accel-Buffering", "no")
    respondBytesWriter(contentType = ContentType.parse("text/event-stream")) {
        withContext(Dispatchers.IO) { streamSse(this@respondBytesWriter, httpClient, aiRequest) }
    }
}

private suspend fun streamSse(
    channel: ByteWriteChannel,
    httpClient: HttpClient,
    request: HttpRequest,
) {
    channel.writeFully(": keepalive\n\n".toByteArray())
    channel.flush()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() != 200) {
        val body = response.body().bufferedReader().readText().take(400).replace("\"", "'")
        val errorEvent = buildJsonObject {
            put("type", "error")
            put("error", "titlis-ai retornou ${response.statusCode()}: $body")
        }
        channel.writeFully("data: $errorEvent\n\n".toByteArray())
        channel.writeFully("data: {\"type\":\"done\"}\n\n".toByteArray())
        return
    }
    val buffer = ByteArray(8192)
    response.body().use { stream ->
        var read = stream.read(buffer)
        while (read >= 0) {
            channel.writeFully(buffer, 0, read)
            channel.flush()
            read = stream.read(buffer)
        }
    }
}

// ─── Payload builders para titlis-ai ─────────────────────────────────────────

private fun buildV2AgentChatPayload(
    tenantId: Long,
    body: AgentChatRequest,
    aiConfig: TenantAiConfigRecord,
): String = buildJsonObject {
    put("tenant_id", tenantId)
    put("session_id", body.sessionId)
    put("message", body.message)
    put("workload_id", body.workloadId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("ai_config", aiConfigJson(aiConfig))
}.toString()

private fun buildV2ToolsRespondPayload(body: AgentToolsRespondRequest): String = buildJsonObject {
    put("decisions", buildJsonArray {
        body.decisions.forEach { d ->
            add(buildJsonObject {
                put("proposal_id", d.proposalId)
                put("approved", d.approved)
                if (d.editedArgs != null) put("edited_args", d.editedArgs) else put("edited_args", JsonNull)
            })
        }
    })
}.toString()

private fun buildV2RemediatePayload(
    tenantId: Long,
    workloadId: String,
    body: RemediateFindingsRequest,
    aiConfig: TenantAiConfigRecord,
): String = buildJsonObject {
    put("tenant_id", tenantId)
    put("workload_id", workloadId)
    put("finding_ids", buildJsonArray { body.findingIds.forEach { add(it) } })
    put("repo_url", body.repoUrl)
    put("deploy_manifest_path", body.deployManifestPath)
    put("service_yaml_path", body.serviceYamlPath)
    put("ai_config", aiConfigJson(aiConfig))
}.toString()

private fun aiConfigJson(aiConfig: TenantAiConfigRecord) = buildJsonObject {
    put("provider", aiConfig.provider)
    put("model", aiConfig.model)
    put("api_key", aiConfig.apiKeyEnc)
    put("github_token", aiConfig.githubTokenEnc?.let { JsonPrimitive(it) } ?: JsonNull)
    put("github_base_branch", aiConfig.githubBaseBranch)
    put("github_auth_mode", aiConfig.githubAuthMode)
    put("github_app_id", aiConfig.githubAppIdEnc?.let { JsonPrimitive(it) } ?: JsonNull)
    put("github_app_private_key", aiConfig.githubAppPrivKeyEnc?.let { JsonPrimitive(it) } ?: JsonNull)
    put("github_app_installation_id", aiConfig.githubAppInstallIdEnc?.let { JsonPrimitive(it) } ?: JsonNull)
    put("monthly_token_budget", aiConfig.monthlyTokenBudget?.let { JsonPrimitive(it) } ?: JsonNull)
    put("tokens_used_month", aiConfig.tokensUsedMonth)
}

// ─── Verificação de assinatura svix ──────────────────────────────────────────

// svix usa HMAC-SHA256 sobre "{svix-id}.{svix-timestamp}.{body}".
// O secret vem no formato "whsec_<base64>" do Clerk Dashboard.
// Um header svix-signature pode conter múltiplas assinaturas separadas por espaço.
private fun verifySvixSignature(
    svixId: String,
    svixTimestamp: String,
    body: String,
    svixSignature: String,
    webhookSecret: String,
): Boolean = runCatching {
    val rawSecret = if (webhookSecret.startsWith("whsec_")) {
        Base64.getDecoder().decode(webhookSecret.removePrefix("whsec_"))
    } else {
        Base64.getDecoder().decode(webhookSecret)
    }
    val toSign = "$svixId.$svixTimestamp.$body"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(rawSecret, "HmacSHA256"))
    val computed = Base64.getEncoder().encodeToString(mac.doFinal(toSign.toByteArray(Charsets.UTF_8)))
    svixSignature.split(" ").any { part -> part.substringAfter(",") == computed }
}.getOrElse { false }
