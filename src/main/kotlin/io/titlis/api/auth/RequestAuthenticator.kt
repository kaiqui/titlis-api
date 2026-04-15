package io.titlis.api.auth

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.titlis.api.config.AuthConfig
import io.titlis.api.repository.AuthRepository
import org.slf4j.LoggerFactory

class RequestAuthenticator(
    private val config: AuthConfig,
    private val authRepository: AuthRepository,
    private val localTokenService: LocalTokenService,
    private val oktaTokenVerifier: OktaTokenVerifier,
) {
    private val logger = LoggerFactory.getLogger(RequestAuthenticator::class.java)
    private val authMode = AuthMode.from(config.authMode)

    suspend fun authenticate(call: ApplicationCall): AppPrincipal? {
        if (isPublicPath(call.request.path())) return null

        if (authMode == AuthMode.DISABLED) {
            logger.warn("Titlis auth is running in disabled mode. path={}", call.request.path())
            return buildConfigBypassPrincipal(source = AuthSource.AUTH_DISABLED)
        }

        if (authMode == AuthMode.MIXED) {
            devBypassPrincipal(call)?.let { return it }
        }

        val token = extractBearerToken(call) ?: return null

        localTokenService.verify(token)?.let { payload ->
            val userId = payload.sub.toLongOrNull() ?: return null
            return authRepository.getUser(userId)?.toPrincipal(AuthSource.LOCAL)
        }

        if (authMode == AuthMode.OKTA || authMode == AuthMode.MIXED) {
            val oktaIdentity = oktaTokenVerifier.verify(token) ?: return null
            return authRepository.resolveFederatedUser(oktaIdentity)?.toPrincipal(AuthSource.OKTA)
        }

        return null
    }

    fun shouldProtect(call: ApplicationCall): Boolean = !isPublicPath(call.request.path())

    private fun devBypassPrincipal(call: ApplicationCall): AppPrincipal? {
        if (!config.devBypassEnabled || !config.appEnv.equals("local", ignoreCase = true)) {
            return null
        }

        val headers = call.request.headers
        val enabled = headers["X-Dev-Auth"]?.equals("true", ignoreCase = true) == true
        if (!enabled) return null

        val tenantId = headers["X-Dev-Tenant-Id"]?.toLongOrNull() ?: config.devTenantId
        val email = headers["X-Dev-User"] ?: config.devUserEmail
        val role = parseRole(headers["X-Dev-Roles"]?.split(",")?.map(String::trim)?.filter(String::isNotBlank).orEmpty().ifEmpty { config.devRoles })

        logger.warn(
            "Titlis dev bypass authentication was used. path={} tenantId={} email={} role={}",
            call.request.path(),
            tenantId,
            email,
            role.dbValue,
        )

        return AppPrincipal(
            userId = null,
            tenantId = tenantId,
            tenantSlug = "dev-tenant-$tenantId",
            tenantName = "Tenant $tenantId",
            email = email,
            displayName = "Dev Bypass",
            role = role,
            authProvider = "dev_bypass",
            onboardingCompleted = true,
            source = AuthSource.DEV_BYPASS,
        )
    }

    private fun buildConfigBypassPrincipal(source: AuthSource): AppPrincipal = AppPrincipal(
        userId = null,
        tenantId = config.devTenantId,
        tenantSlug = "dev-tenant-${config.devTenantId}",
        tenantName = "Tenant ${config.devTenantId}",
        email = config.devUserEmail,
        displayName = "Auth Disabled",
        role = parseRole(config.devRoles),
        authProvider = "auth_disabled",
        onboardingCompleted = true,
        source = source,
    )

    private fun parseRole(values: List<String>): PlatformRole {
        val normalized = values.map { it.lowercase() }
        return when {
            normalized.any { it == "titlis.admin" || it == "admin" } -> PlatformRole.ADMIN
            else -> PlatformRole.VIEWER
        }
    }

    private fun extractBearerToken(call: ApplicationCall): String? {
        val header = call.request.headers[HttpHeaders.Authorization] ?: return null
        val token = header.removePrefix("Bearer ").trim()
        return token.takeIf { it.isNotBlank() }
    }

    private fun isPublicPath(path: String): Boolean {
        if (path == "/health" || path == "/ready") return true
        return path == "/v1/auth/bootstrap/status" ||
            path == "/v1/auth/bootstrap/setup" ||
            path == "/v1/auth/local/login"
    }
}
