package io.titlis.api.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class OidcDiscoveryVerification(
    val issuer: String,
    val discoveryUrl: String,
    val jwksUri: String,
)

interface OidcDiscoveryVerifier {
    fun verify(issuerUrl: String): OidcDiscoveryVerification
}

class DefaultOidcDiscoveryVerifier(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : OidcDiscoveryVerifier {

    override fun verify(issuerUrl: String): OidcDiscoveryVerification {
        val normalizedIssuer = canonicalIssuer(issuerUrl)
        val discoveryUrl = "$normalizedIssuer/.well-known/openid-configuration"

        val request = try {
            HttpRequest.newBuilder()
                .uri(URI.create(discoveryUrl))
                .timeout(Duration.ofSeconds(6))
                .header("Accept", "application/json")
                .GET()
                .build()
        } catch (_: Exception) {
            throw TenantAuthIntegrationValidationException("oidc_discovery_invalid_url")
        }

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            throw TenantAuthIntegrationValidationException("oidc_discovery_unreachable")
        }

        if (response.statusCode() !in 200..299) {
            throw TenantAuthIntegrationValidationException("oidc_discovery_http_error")
        }

        val payload = try {
            json.parseToJsonElement(response.body()) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw TenantAuthIntegrationValidationException("oidc_discovery_invalid_json")

        val discoveredIssuer = payload["issuer"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let(::canonicalIssuer)
            ?: throw TenantAuthIntegrationValidationException("oidc_discovery_missing_issuer")

        if (discoveredIssuer != normalizedIssuer) {
            throw TenantAuthIntegrationValidationException("oidc_discovery_issuer_mismatch")
        }

        val jwksUri = payload["jwks_uri"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw TenantAuthIntegrationValidationException("oidc_discovery_missing_jwks_uri")

        validateAbsoluteHttpUrl(jwksUri, "oidc_discovery_invalid_jwks_uri")

        return OidcDiscoveryVerification(
            issuer = discoveredIssuer,
            discoveryUrl = discoveryUrl,
            jwksUri = jwksUri,
        )
    }

    private fun canonicalIssuer(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            throw TenantAuthIntegrationValidationException("issuer_required")
        }

        val marker = "/.well-known/"
        val canonical = if (trimmed.contains(marker)) {
            trimmed.substringBefore(marker).trimEnd('/')
        } else {
            trimmed
        }

        validateAbsoluteHttpUrl(canonical, "issuer_invalid")
        return canonical
    }

    private fun validateAbsoluteHttpUrl(value: String, errorCode: String) {
        val uri = runCatching { URI.create(value) }.getOrNull()
            ?: throw TenantAuthIntegrationValidationException(errorCode)

        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw TenantAuthIntegrationValidationException(errorCode)
        }
    }
}
