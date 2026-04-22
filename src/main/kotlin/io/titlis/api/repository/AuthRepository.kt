package io.titlis.api.repository

import io.titlis.api.auth.AuthenticatedUser
import io.titlis.api.auth.BootstrapAlreadyConfiguredException
import io.titlis.api.auth.BootstrapSetupRequest
import io.titlis.api.auth.BootstrapStatusResponse
import io.titlis.api.auth.InvalidCredentialsException
import io.titlis.api.auth.LocalLoginRequest
import io.titlis.api.auth.OidcDiscoveryVerifier
import io.titlis.api.auth.OktaIdentity
import io.titlis.api.auth.PasswordHasher
import io.titlis.api.auth.PlatformRole
import io.titlis.api.auth.TenantAuthIntegrationNotFoundException
import io.titlis.api.auth.TenantAuthIntegrationResponse
import io.titlis.api.auth.TenantAuthIntegrationValidationException
import io.titlis.api.auth.TenantRegistrationConflictException
import io.titlis.api.auth.UpsertTenantAuthIntegrationRequest
import io.titlis.api.auth.DefaultOidcDiscoveryVerifier
import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.PlatformUsers
import io.titlis.api.database.tables.TenantAuthIntegrations
import io.titlis.api.database.tables.Tenants
import io.titlis.api.database.tables.UserAuthIdentities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AuthRepository(
    private val passwordHasher: PasswordHasher,
    private val oidcDiscoveryVerifier: OidcDiscoveryVerifier = DefaultOidcDiscoveryVerifier(),
) {
    private val oidcProviders = setOf("okta", "generic_oidc")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun bootstrapStatus(): BootstrapStatusResponse = dbQuery {
        val bootstrapRequired = PlatformUsers
            .selectAll()
            .where { PlatformUsers.deletedAt.isNull() }
            .limit(1)
            .singleOrNull() == null
        val primaryIntegration = TenantAuthIntegrations
            .select(
                TenantAuthIntegrations.providerType,
                TenantAuthIntegrations.isEnabled,
            )
            .where {
                (TenantAuthIntegrations.isPrimary eq true) and
                    TenantAuthIntegrations.deletedAt.isNull()
            }
            .singleOrNull()

        val oktaConfigured = TenantAuthIntegrations
            .selectAll()
            .where {
                (TenantAuthIntegrations.providerType eq "okta") and
                    (TenantAuthIntegrations.isEnabled eq true) and
                    TenantAuthIntegrations.deletedAt.isNull()
            }
            .limit(1)
            .singleOrNull() != null

        BootstrapStatusResponse(
            bootstrapRequired = bootstrapRequired,
            localLoginEnabled = bootstrapRequired || primaryIntegration?.get(TenantAuthIntegrations.isEnabled) == true || hasEnabledLocalIntegration(),
            oktaConfigured = oktaConfigured,
            primaryProvider = primaryIntegration?.get(TenantAuthIntegrations.providerType),
        )
    }

    suspend fun setupBootstrap(request: BootstrapSetupRequest): AuthenticatedUser = dbQuery {
        val tenantName = request.tenantName.trim()
        val tenantSlug = normalizeTenantSlug(request.tenantSlug)
        val adminName = request.adminName.trim()
        val adminEmail = request.adminEmail.trim().lowercase()
        val password = request.password

        require(tenantName.isNotBlank()) { "tenantName obrigatório" }
        require(tenantSlug.isNotBlank()) { "tenantSlug obrigatório" }
        require(adminName.isNotBlank()) { "adminName obrigatório" }
        require(adminEmail.isNotBlank()) { "adminEmail obrigatório" }
        require(password.length >= 10) { "A senha precisa ter pelo menos 10 caracteres" }

        val tenantExists = Tenants
            .select(Tenants.tenantId)
            .where { Tenants.slug eq tenantSlug }
            .singleOrNull() != null
        if (tenantExists) {
            throw TenantRegistrationConflictException("tenant_slug_taken")
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)

        Tenants.insert {
            it[Tenants.tenantName] = tenantName
            it[Tenants.slug] = tenantSlug
            it[Tenants.isActive] = true
            it[Tenants.tenantPlan] = "enterprise"
            it[Tenants.createdAt] = now
            it[Tenants.updatedAt] = now
        }

        val tenantId = Tenants
            .select(Tenants.tenantId)
            .where { Tenants.slug eq tenantSlug }
            .single()[Tenants.tenantId]

        TenantAuthIntegrations.insert {
            it[TenantAuthIntegrations.tenantId] = tenantId
            it[TenantAuthIntegrations.providerType] = "local"
            it[TenantAuthIntegrations.integrationKind] = "local_password"
            it[TenantAuthIntegrations.integrationName] = "Local admin"
            it[TenantAuthIntegrations.isEnabled] = true
            it[TenantAuthIntegrations.isPrimary] = true
            it[TenantAuthIntegrations.verifiedAt] = now
            it[TenantAuthIntegrations.activatedAt] = now
            it[TenantAuthIntegrations.createdAt] = now
            it[TenantAuthIntegrations.updatedAt] = now
        }

        PlatformUsers.insert {
            it[PlatformUsers.tenantId] = tenantId
            it[PlatformUsers.email] = adminEmail
            it[PlatformUsers.displayName] = adminName
            it[PlatformUsers.passwordHash] = passwordHasher.hash(password)
            it[PlatformUsers.platformRole] = PlatformRole.ADMIN.dbValue
            it[PlatformUsers.isActive] = true
            it[PlatformUsers.isBreakGlass] = true
            it[PlatformUsers.lastLoginAt] = now
            it[PlatformUsers.createdAt] = now
            it[PlatformUsers.updatedAt] = now
        }

        val userId = PlatformUsers
            .select(PlatformUsers.platformUserId)
            .where {
                (PlatformUsers.tenantId eq tenantId) and
                    (PlatformUsers.email eq adminEmail)
            }
            .single()[PlatformUsers.platformUserId]

        TenantAuthIntegrations.update({
            (TenantAuthIntegrations.tenantId eq tenantId) and
                (TenantAuthIntegrations.providerType eq "local")
        }) {
            it[configuredByPlatformUserId] = userId
            it[updatedAt] = now
        }

        loadUserById(userId) ?: error("Falha ao carregar usuário bootstrap")
    }

    suspend fun authenticateLocal(request: LocalLoginRequest): AuthenticatedUser = dbQuery {
        val tenantSlug = normalizeTenantSlug(request.tenantSlug)
        val email = request.email.trim().lowercase()
        val password = request.password

        require(tenantSlug.isNotBlank()) { "tenantSlug obrigatório" }
        require(email.isNotBlank()) { "email obrigatório" }
        require(password.isNotBlank()) { "password obrigatório" }

        ensureLocalLoginEnabled(tenantSlug)

        val row = baseUserQuery()
            .andWhere {
                (Tenants.slug eq tenantSlug) and
                    (PlatformUsers.email eq email) and
                    (PlatformUsers.isActive eq true) and
                    PlatformUsers.deletedAt.isNull()
            }
            .singleOrNull()
            ?: throw InvalidCredentialsException()

        val passwordHash = row[PlatformUsers.passwordHash]
        if (passwordHash.isNullOrBlank() || !passwordHasher.verify(password, passwordHash)) {
            throw InvalidCredentialsException()
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        PlatformUsers.update({ PlatformUsers.platformUserId eq row[PlatformUsers.platformUserId] }) {
            it[lastLoginAt] = now
            it[updatedAt] = now
        }

        row.toAuthenticatedUser()
    }

    suspend fun getUser(userId: Long): AuthenticatedUser? = dbQuery {
        baseUserQuery()
            .andWhere { PlatformUsers.platformUserId eq userId }
            .singleOrNull()
            ?.toAuthenticatedUser()
    }

    suspend fun resolveFederatedUser(identity: OktaIdentity): AuthenticatedUser? = dbQuery {
        val tenantId = identity.tenantId ?: return@dbQuery null

        val integrationRow = TenantAuthIntegrations
            .select(
                TenantAuthIntegrations.tenantAuthIntegrationId,
                TenantAuthIntegrations.issuerUrl,
            )
            .where {
                (TenantAuthIntegrations.tenantId eq tenantId) and
                    (TenantAuthIntegrations.providerType eq "okta") and
                    (TenantAuthIntegrations.isEnabled eq true) and
                    TenantAuthIntegrations.deletedAt.isNull()
            }
            .singleOrNull()
            ?: return@dbQuery null

        val integrationId = integrationRow[TenantAuthIntegrations.tenantAuthIntegrationId]

        val identityRow = (UserAuthIdentities innerJoin PlatformUsers innerJoin Tenants)
            .select(
                PlatformUsers.platformUserId,
                PlatformUsers.tenantId,
                PlatformUsers.email,
                PlatformUsers.displayName,
                PlatformUsers.platformRole,
                PlatformUsers.isActive,
                Tenants.slug,
                Tenants.tenantName,
            )
            .where {
                (UserAuthIdentities.tenantAuthIntegrationId eq integrationId) and
                    (UserAuthIdentities.providerSubject eq identity.subject) and
                    UserAuthIdentities.deletedAt.isNull() and
                    PlatformUsers.deletedAt.isNull()
            }
            .singleOrNull()

        if (identityRow != null && identityRow[PlatformUsers.isActive]) {
            touchFederatedIdentity(identity.subject, integrationId, identity.issuer)
            return@dbQuery identityRow.toAuthenticatedUser(authProvider = "okta", onboardingCompleted = true)
        }

        val email = identity.email?.trim()?.lowercase() ?: return@dbQuery null
        val userRow = (PlatformUsers innerJoin Tenants)
            .select(
                PlatformUsers.platformUserId,
                PlatformUsers.tenantId,
                PlatformUsers.email,
                PlatformUsers.displayName,
                PlatformUsers.platformRole,
                PlatformUsers.isActive,
                Tenants.slug,
                Tenants.tenantName,
            )
            .where {
                (PlatformUsers.tenantId eq tenantId) and
                    (PlatformUsers.email eq email) and
                    (PlatformUsers.isActive eq true) and
                    PlatformUsers.deletedAt.isNull()
            }
            .singleOrNull()
            ?: return@dbQuery null

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        UserAuthIdentities.insert {
            it[platformUserId] = userRow[PlatformUsers.platformUserId]
            it[tenantAuthIntegrationId] = integrationId
            it[providerSubject] = identity.subject
            it[issuerUrl] = identity.issuer
            it[emailSnapshot] = email
            it[claimsSnapshot] = buildJsonObject {
                put("email", email)
                put("tenant_id", tenantId)
                put("roles", buildJsonArray { identity.roles.forEach { role -> add(JsonPrimitive(role)) } })
            }.toString()
            it[lastAuthenticatedAt] = now
            it[createdAt] = now
            it[updatedAt] = now
        }

        userRow.toAuthenticatedUser(authProvider = "okta", onboardingCompleted = true)
    }

    suspend fun listTenantAuthIntegrations(tenantId: Long): List<TenantAuthIntegrationResponse> = dbQuery {
        TenantAuthIntegrations
            .selectAll()
            .where {
                (TenantAuthIntegrations.tenantId eq tenantId) and
                    TenantAuthIntegrations.deletedAt.isNull()
            }
            .orderBy(TenantAuthIntegrations.isPrimary to org.jetbrains.exposed.sql.SortOrder.DESC)
            .map { it.toTenantAuthIntegrationResponse() }
    }

    suspend fun upsertTenantAuthIntegration(
        tenantId: Long,
        configuredByUserId: Long?,
        request: UpsertTenantAuthIntegrationRequest,
    ): TenantAuthIntegrationResponse = dbQuery {
        val providerType = request.providerType.trim().lowercase()
        val integrationKind = "sso_oidc"
        val integrationName = request.integrationName.trim()
        val issuerUrl = normalizeIssuerUrl(request.issuerUrl)
        val clientId = request.clientId.trim()
        val audience = request.audience.trim()
        val scopes = normalizeScopes(request.scopes)
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        if (providerType !in oidcProviders) {
            throw TenantAuthIntegrationValidationException("provider_type_unsupported")
        }
        if (integrationName.isBlank()) {
            throw TenantAuthIntegrationValidationException("integration_name_required")
        }
        if (clientId.isBlank()) {
            throw TenantAuthIntegrationValidationException("client_id_required")
        }
        if (audience.isBlank()) {
            throw TenantAuthIntegrationValidationException("audience_required")
        }

        val row = TenantAuthIntegrations
            .select(TenantAuthIntegrations.tenantAuthIntegrationId)
            .where {
                (TenantAuthIntegrations.tenantId eq tenantId) and
                    (TenantAuthIntegrations.providerType eq providerType) and
                    (TenantAuthIntegrations.integrationKind eq integrationKind) and
                    TenantAuthIntegrations.deletedAt.isNull()
            }
            .singleOrNull()
        val currentIntegrationId = row?.get(TenantAuthIntegrations.tenantAuthIntegrationId)

        val integrationNameOwner = TenantAuthIntegrations
            .select(TenantAuthIntegrations.tenantAuthIntegrationId)
            .where {
                (TenantAuthIntegrations.tenantId eq tenantId) and
                    (TenantAuthIntegrations.integrationName eq integrationName) and
                    TenantAuthIntegrations.deletedAt.isNull()
            }
            .singleOrNull()
            ?.get(TenantAuthIntegrations.tenantAuthIntegrationId)

        if (integrationNameOwner != null && integrationNameOwner != currentIntegrationId) {
            throw TenantAuthIntegrationValidationException("integration_name_taken")
        }

        val configJson = buildJsonObject {
            request.redirectUri?.trim()?.takeIf { it.isNotBlank() }?.let { put("redirect_uri", it) }
            request.postLogoutRedirectUri?.trim()?.takeIf { it.isNotBlank() }?.let { put("post_logout_redirect_uri", it) }
        }.toString()

        val integrationId = if (currentIntegrationId == null) {
            TenantAuthIntegrations.insert {
                it[TenantAuthIntegrations.tenantId] = tenantId
                it[TenantAuthIntegrations.providerType] = providerType
                it[TenantAuthIntegrations.integrationKind] = integrationKind
                it[TenantAuthIntegrations.integrationName] = integrationName
                it[TenantAuthIntegrations.isEnabled] = true
                it[TenantAuthIntegrations.isPrimary] = false
                it[TenantAuthIntegrations.issuerUrl] = issuerUrl
                it[TenantAuthIntegrations.clientId] = clientId
                it[TenantAuthIntegrations.audience] = audience
                it[TenantAuthIntegrations.scopes] = scopes.joinToString(" ")
                it[TenantAuthIntegrations.configJson] = configJson
                it[TenantAuthIntegrations.configuredByPlatformUserId] = configuredByUserId
                it[TenantAuthIntegrations.verifiedAt] = null
                it[TenantAuthIntegrations.activatedAt] = null
                it[TenantAuthIntegrations.createdAt] = now
                it[TenantAuthIntegrations.updatedAt] = now
            } get TenantAuthIntegrations.tenantAuthIntegrationId
        } else {
            TenantAuthIntegrations.update({
                (TenantAuthIntegrations.tenantAuthIntegrationId eq currentIntegrationId) and
                    (TenantAuthIntegrations.tenantId eq tenantId)
            }) {
                it[TenantAuthIntegrations.integrationName] = integrationName
                it[TenantAuthIntegrations.isEnabled] = true
                it[TenantAuthIntegrations.issuerUrl] = issuerUrl
                it[TenantAuthIntegrations.clientId] = clientId
                it[TenantAuthIntegrations.audience] = audience
                it[TenantAuthIntegrations.scopes] = scopes.joinToString(" ")
                it[TenantAuthIntegrations.configJson] = configJson
                it[TenantAuthIntegrations.configuredByPlatformUserId] = configuredByUserId
                it[TenantAuthIntegrations.verifiedAt] = null
                it[TenantAuthIntegrations.updatedAt] = now
            }
            currentIntegrationId
        }

        loadTenantIntegrationById(tenantId, integrationId) ?: throw TenantAuthIntegrationNotFoundException()
    }

    suspend fun verifyTenantAuthIntegration(
        tenantId: Long,
        integrationId: Long,
    ): TenantAuthIntegrationResponse = dbQuery {
        val row = loadTenantIntegrationRow(tenantId, integrationId) ?: throw TenantAuthIntegrationNotFoundException()
        val issuerUrl = row[TenantAuthIntegrations.issuerUrl]?.trim().orEmpty()
        val clientId = row[TenantAuthIntegrations.clientId]?.trim().orEmpty()
        val audience = row[TenantAuthIntegrations.audience]?.trim().orEmpty()

        if (issuerUrl.isBlank()) throw TenantAuthIntegrationValidationException("issuer_required")
        if (clientId.isBlank()) throw TenantAuthIntegrationValidationException("client_id_required")
        if (audience.isBlank()) throw TenantAuthIntegrationValidationException("audience_required")

        validateIssuerUrl(issuerUrl)
        oidcDiscoveryVerifier.verify(issuerUrl)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        TenantAuthIntegrations.update({
            (TenantAuthIntegrations.tenantAuthIntegrationId eq integrationId) and
                (TenantAuthIntegrations.tenantId eq tenantId)
        }) {
            it[TenantAuthIntegrations.verifiedAt] = now
            it[TenantAuthIntegrations.updatedAt] = now
        }

        loadTenantIntegrationById(tenantId, integrationId) ?: throw TenantAuthIntegrationNotFoundException()
    }

    suspend fun activateTenantAuthIntegration(
        tenantId: Long,
        integrationId: Long,
        configuredByUserId: Long?,
    ): TenantAuthIntegrationResponse = dbQuery {
        val row = loadTenantIntegrationRow(tenantId, integrationId) ?: throw TenantAuthIntegrationNotFoundException()
        if (row[TenantAuthIntegrations.integrationKind] != "sso_oidc") {
            throw TenantAuthIntegrationValidationException("integration_kind_unsupported")
        }
        if (row[TenantAuthIntegrations.verifiedAt] == null) {
            throw TenantAuthIntegrationValidationException("integration_not_verified")
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        TenantAuthIntegrations.update({
            (TenantAuthIntegrations.tenantId eq tenantId) and
                (TenantAuthIntegrations.integrationKind eq "sso_oidc")
        }) {
            it[TenantAuthIntegrations.isPrimary] = false
            it[TenantAuthIntegrations.updatedAt] = now
        }

        TenantAuthIntegrations.update({
            (TenantAuthIntegrations.tenantAuthIntegrationId eq integrationId) and
                (TenantAuthIntegrations.tenantId eq tenantId)
        }) {
            it[TenantAuthIntegrations.isEnabled] = true
            it[TenantAuthIntegrations.isPrimary] = true
            it[TenantAuthIntegrations.activatedAt] = now
            it[TenantAuthIntegrations.configuredByPlatformUserId] = configuredByUserId
            it[TenantAuthIntegrations.updatedAt] = now
        }

        loadTenantIntegrationById(tenantId, integrationId) ?: throw TenantAuthIntegrationNotFoundException()
    }

    suspend fun deactivateTenantAuthIntegration(
        tenantId: Long,
        integrationId: Long,
        configuredByUserId: Long?,
    ): TenantAuthIntegrationResponse = dbQuery {
        val row = loadTenantIntegrationRow(tenantId, integrationId) ?: throw TenantAuthIntegrationNotFoundException()
        if (row[TenantAuthIntegrations.providerType] == "local") {
            throw TenantAuthIntegrationValidationException("local_provider_cannot_be_disabled")
        }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        TenantAuthIntegrations.update({
            (TenantAuthIntegrations.tenantAuthIntegrationId eq integrationId) and
                (TenantAuthIntegrations.tenantId eq tenantId)
        }) {
            it[TenantAuthIntegrations.isEnabled] = false
            it[TenantAuthIntegrations.isPrimary] = false
            it[TenantAuthIntegrations.configuredByPlatformUserId] = configuredByUserId
            it[TenantAuthIntegrations.updatedAt] = now
        }

        loadTenantIntegrationById(tenantId, integrationId) ?: throw TenantAuthIntegrationNotFoundException()
    }

    private fun hasEnabledLocalIntegration(): Boolean = TenantAuthIntegrations
        .selectAll()
        .where {
            (TenantAuthIntegrations.providerType eq "local") and
                (TenantAuthIntegrations.isEnabled eq true) and
                TenantAuthIntegrations.deletedAt.isNull()
        }
        .limit(1)
        .singleOrNull() != null

    private fun ensureLocalLoginEnabled(tenantSlug: String) {
        val localEnabled = (TenantAuthIntegrations innerJoin Tenants)
            .select(TenantAuthIntegrations.tenantAuthIntegrationId)
            .where {
                (Tenants.slug eq tenantSlug) and
                    (TenantAuthIntegrations.providerType eq "local") and
                    (TenantAuthIntegrations.isEnabled eq true) and
                    TenantAuthIntegrations.deletedAt.isNull()
            }
            .singleOrNull() != null

        if (!localEnabled) {
            throw InvalidCredentialsException()
        }
    }

    private fun baseUserQuery() = (PlatformUsers innerJoin Tenants)
        .join(TenantAuthIntegrations, JoinType.LEFT, additionalConstraint = {
            (TenantAuthIntegrations.tenantId eq Tenants.tenantId) and
                (TenantAuthIntegrations.providerType eq "okta") and
                (TenantAuthIntegrations.isPrimary eq true) and
                (TenantAuthIntegrations.isEnabled eq true) and
                TenantAuthIntegrations.deletedAt.isNull()
        })
        .select(
            PlatformUsers.platformUserId,
            PlatformUsers.tenantId,
            PlatformUsers.email,
            PlatformUsers.displayName,
            PlatformUsers.passwordHash,
            PlatformUsers.platformRole,
            Tenants.slug,
            Tenants.tenantName,
            TenantAuthIntegrations.tenantAuthIntegrationId,
        )

    private fun loadUserById(userId: Long): AuthenticatedUser? = baseUserQuery()
        .andWhere { PlatformUsers.platformUserId eq userId }
        .singleOrNull()
        ?.toAuthenticatedUser()

    private fun touchFederatedIdentity(subject: String, integrationId: Long, issuer: String) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        UserAuthIdentities.update({
            (UserAuthIdentities.providerSubject eq subject) and
                (UserAuthIdentities.tenantAuthIntegrationId eq integrationId) and
                UserAuthIdentities.deletedAt.isNull()
        }) {
            it[lastAuthenticatedAt] = now
            it[issuerUrl] = issuer
            it[updatedAt] = now
        }
    }

    private fun loadTenantIntegrationById(
        tenantId: Long,
        integrationId: Long,
    ): TenantAuthIntegrationResponse? = TenantAuthIntegrations
        .selectAll()
        .where {
            (TenantAuthIntegrations.tenantAuthIntegrationId eq integrationId) and
                (TenantAuthIntegrations.tenantId eq tenantId) and
                TenantAuthIntegrations.deletedAt.isNull()
        }
        .singleOrNull()
        ?.toTenantAuthIntegrationResponse()

    private fun loadTenantIntegrationRow(
        tenantId: Long,
        integrationId: Long,
    ): ResultRow? = TenantAuthIntegrations
        .selectAll()
        .where {
            (TenantAuthIntegrations.tenantAuthIntegrationId eq integrationId) and
                (TenantAuthIntegrations.tenantId eq tenantId) and
                TenantAuthIntegrations.deletedAt.isNull()
        }
        .singleOrNull()

    private fun ResultRow.toTenantAuthIntegrationResponse(): TenantAuthIntegrationResponse {
        val config = this[TenantAuthIntegrations.configJson]
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val scopes = this[TenantAuthIntegrations.scopes]
            ?.split(Regex("[,\\s]+"))
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

        return TenantAuthIntegrationResponse(
            id = this[TenantAuthIntegrations.tenantAuthIntegrationId],
            providerType = this[TenantAuthIntegrations.providerType],
            integrationKind = this[TenantAuthIntegrations.integrationKind],
            integrationName = this[TenantAuthIntegrations.integrationName],
            isEnabled = this[TenantAuthIntegrations.isEnabled],
            isPrimary = this[TenantAuthIntegrations.isPrimary],
            issuerUrl = this[TenantAuthIntegrations.issuerUrl],
            clientId = this[TenantAuthIntegrations.clientId],
            audience = this[TenantAuthIntegrations.audience],
            scopes = scopes,
            redirectUri = config?.get("redirect_uri")?.jsonPrimitive?.contentOrNull,
            postLogoutRedirectUri = config?.get("post_logout_redirect_uri")?.jsonPrimitive?.contentOrNull,
            verifiedAt = this[TenantAuthIntegrations.verifiedAt]?.toString(),
            activatedAt = this[TenantAuthIntegrations.activatedAt]?.toString(),
            configuredByUserId = this[TenantAuthIntegrations.configuredByPlatformUserId],
            updatedAt = this[TenantAuthIntegrations.updatedAt].toString(),
        )
    }

    private fun normalizeScopes(values: List<String>): List<String> {
        val normalized = values
            .flatMap { scope -> scope.split(Regex("[,\\s]+")) }
            .map { scope -> scope.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return if (normalized.isEmpty()) listOf("openid", "profile", "email") else normalized
    }

    private fun normalizeIssuerUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        if (normalized.isBlank()) {
            throw TenantAuthIntegrationValidationException("issuer_required")
        }
        validateIssuerUrl(normalized)
        return normalized
    }

    private fun validateIssuerUrl(value: String) {
        val parsed = runCatching { URI.create(value) }.getOrNull()
            ?: throw TenantAuthIntegrationValidationException("issuer_invalid")
        if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            throw TenantAuthIntegrationValidationException("issuer_invalid")
        }
    }

    private fun ResultRow.toAuthenticatedUser(
        authProvider: String = "local",
        onboardingCompleted: Boolean = true,
    ): AuthenticatedUser = AuthenticatedUser(
        id = this[PlatformUsers.platformUserId],
        tenantId = this[PlatformUsers.tenantId],
        tenantSlug = this[Tenants.slug],
        tenantName = this[Tenants.tenantName],
        email = this[PlatformUsers.email],
        displayName = this[PlatformUsers.displayName],
        role = PlatformRole.fromDb(this[PlatformUsers.platformRole]),
        authProvider = authProvider,
        onboardingCompleted = onboardingCompleted,
    )

    private fun normalizeTenantSlug(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9-]+"), "-")
        .replace(Regex("-{2,}"), "-")
        .trim('-')
}
