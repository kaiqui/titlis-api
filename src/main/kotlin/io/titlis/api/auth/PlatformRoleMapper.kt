package io.titlis.api.auth

private val adminGroupAliases = setOf(
    "jeitto confia - admin",
    "titlis.admin",
    "admin",
)

private val viewerGroupAliases = setOf(
    "jeitto confia - viewer",
    "titlis.viewer",
    "titlis.engineer",
    "viewer",
    "engineer",
)

fun platformRoleFromIamGroups(values: List<String>): PlatformRole? {
    val normalized = values.map { it.trim().lowercase() }.filter { it.isNotBlank() }
    return when {
        normalized.any { it in adminGroupAliases } -> PlatformRole.ADMIN
        normalized.any { it in viewerGroupAliases } -> PlatformRole.VIEWER
        else -> null
    }
}

fun platformRoleFromOptionalGroups(values: List<String>): PlatformRole =
    platformRoleFromIamGroups(values) ?: PlatformRole.VIEWER
