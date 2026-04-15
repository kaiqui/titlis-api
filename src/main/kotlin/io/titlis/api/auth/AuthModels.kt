package io.titlis.api.auth

import kotlinx.serialization.Serializable

enum class PlatformRole(val dbValue: String) {
    ADMIN("admin"),
    VIEWER("viewer");

    companion object {
        fun fromDb(value: String?): PlatformRole = entries.firstOrNull { it.dbValue == value } ?: VIEWER
    }
}

@Serializable
data class BootstrapStatusResponse(
    val bootstrapRequired: Boolean,
    val localLoginEnabled: Boolean,
    val oktaConfigured: Boolean,
    val primaryProvider: String? = null,
)

@Serializable
data class BootstrapSetupRequest(
    val tenantName: String,
    val tenantSlug: String,
    val adminName: String,
    val adminEmail: String,
    val password: String,
)

@Serializable
data class LocalLoginRequest(
    val tenantSlug: String,
    val email: String,
    val password: String,
)

@Serializable
data class AuthUserResponse(
    val id: Long,
    val tenantId: Long,
    val tenantSlug: String,
    val tenantName: String,
    val email: String,
    val displayName: String?,
    val role: String,
    val authProvider: String,
    val onboardingCompleted: Boolean,
    val canRemediate: Boolean,
)

@Serializable
data class AuthSessionResponse(
    val accessToken: String,
    val expiresAt: String,
    val user: AuthUserResponse,
)

@Serializable
data class BootstrapSetupResponse(
    val accessToken: String,
    val expiresAt: String,
    val user: AuthUserResponse,
    val operatorApiKey: String,
)

@Serializable
data class AuthMeResponse(
    val user: AuthUserResponse,
)

@Serializable
data class TenantAuthIntegrationResponse(
    val id: Long,
    val providerType: String,
    val integrationKind: String,
    val integrationName: String,
    val isEnabled: Boolean,
    val isPrimary: Boolean,
    val issuerUrl: String?,
    val clientId: String?,
    val audience: String?,
    val scopes: List<String>,
    val redirectUri: String?,
    val postLogoutRedirectUri: String?,
    val verifiedAt: String?,
    val activatedAt: String?,
    val configuredByUserId: Long?,
    val updatedAt: String,
)

@Serializable
data class UpsertTenantAuthIntegrationRequest(
    val providerType: String,
    val integrationName: String,
    val issuerUrl: String,
    val clientId: String,
    val audience: String,
    val scopes: List<String> = listOf("openid", "profile", "email"),
    val redirectUri: String? = null,
    val postLogoutRedirectUri: String? = null,
)

@Serializable
data class VerifyTenantAuthIntegrationResponse(
    val status: String,
    val message: String,
    val integration: TenantAuthIntegrationResponse,
)

data class AuthenticatedUser(
    val id: Long,
    val tenantId: Long,
    val tenantSlug: String,
    val tenantName: String,
    val email: String,
    val displayName: String?,
    val role: PlatformRole,
    val authProvider: String,
    val onboardingCompleted: Boolean,
) {
    fun toResponse(): AuthUserResponse = AuthUserResponse(
        id = id,
        tenantId = tenantId,
        tenantSlug = tenantSlug,
        tenantName = tenantName,
        email = email,
        displayName = displayName,
        role = role.dbValue,
        authProvider = authProvider,
        onboardingCompleted = onboardingCompleted,
        canRemediate = role == PlatformRole.ADMIN,
    )
}

class InvalidCredentialsException : RuntimeException("Credenciais inválidas")
class BootstrapAlreadyConfiguredException : RuntimeException("Bootstrap já foi configurado")
class TenantRegistrationConflictException(code: String) : RuntimeException(code)
class TenantAuthIntegrationNotFoundException : RuntimeException("integration_not_found")
class TenantAuthIntegrationValidationException(code: String) : RuntimeException(code)
