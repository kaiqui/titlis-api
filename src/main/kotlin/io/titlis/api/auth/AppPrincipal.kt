package io.titlis.api.auth

import io.ktor.server.auth.Principal

enum class AuthSource {
    LOCAL,
    OKTA,
    DEV_BYPASS,
    AUTH_DISABLED,
}

data class AppPrincipal(
    val userId: Long?,
    val tenantId: Long,
    val tenantSlug: String,
    val tenantName: String,
    val email: String,
    val displayName: String?,
    val role: PlatformRole,
    val authProvider: String,
    val onboardingCompleted: Boolean,
    val source: AuthSource,
) : Principal {
    fun toResponse() = AuthUserResponse(
        id = userId ?: 0,
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

fun AuthenticatedUser.toPrincipal(source: AuthSource): AppPrincipal = AppPrincipal(
    userId = id,
    tenantId = tenantId,
    tenantSlug = tenantSlug,
    tenantName = tenantName,
    email = email,
    displayName = displayName,
    role = role,
    authProvider = authProvider,
    onboardingCompleted = onboardingCompleted,
    source = source,
)
