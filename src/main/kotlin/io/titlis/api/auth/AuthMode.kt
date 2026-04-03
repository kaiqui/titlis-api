package io.titlis.api.auth

enum class AuthMode {
    DISABLED,
    OKTA,
    MIXED;

    companion object {
        fun from(value: String): AuthMode = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MIXED
    }
}
