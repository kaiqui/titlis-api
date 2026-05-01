package io.titlis.api.auth

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.Payload
import io.titlis.api.config.AuthConfig
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

data class OktaIdentity(
    val subject: String,
    val email: String?,
    val tenantId: Long?,
    val groups: List<String>,
    val issuer: String,
) {
    fun platformRole(): PlatformRole? = platformRoleFromIamGroups(groups)
}

class OktaTokenVerifier(
    private val config: AuthConfig,
) {
    private val normalizedIssuer = config.oktaIssuer?.let(::normalizeIssuer)

    private val jwkProvider = normalizedIssuer?.let { issuer ->
        JwkProviderBuilder(URI.create("${issuer.trimEnd('/')}/v1/keys").toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()
    }

    fun issuer(): String? = normalizedIssuer

    fun audience(): String? = config.oktaAudience

    fun jwkProvider() = jwkProvider

    fun verify(token: String): OktaIdentity? {
        val audience = config.oktaAudience ?: return null
        return verifyWithAudience(token, audience)
    }

    fun verifyIdToken(token: String): OktaIdentity? {
        val audience = config.oktaClientId ?: return null
        return verifyWithAudience(token, audience)
    }

    fun payloadToIdentity(payload: Payload): OktaIdentity? = runCatching {
        payload.toIdentity()
    }.getOrNull()

    private fun Payload.toIdentity(): OktaIdentity = OktaIdentity(
        subject = subject,
        email = extractEmail(),
        tenantId = getClaim("titlis_tenant_id")?.asString()?.toLongOrNull()
            ?: getClaim("titlis_tenant_id")?.asLong(),
        groups = extractGroups(),
        issuer = issuer,
    )

    private fun Payload.extractEmail(): String? {
        val email = getClaim("email")?.asString()?.trim()
        if (!email.isNullOrBlank()) return email

        val preferredUsername = getClaim("preferred_username")?.asString()?.trim()
        if (!preferredUsername.isNullOrBlank()) return preferredUsername

        return subject.takeIf { it.contains('@') }?.trim()
    }

    private fun Payload.extractGroups(): List<String> {
        val groupedClaims = listOf("group", "groups", "titlis_roles")
        for (claimName in groupedClaims) {
            val claim = getClaim(claimName) ?: continue
            claim.asList(String::class.java)
                ?.filterNotNull()
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }

            claim.asString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return listOf(it) }
        }
        return emptyList()
    }

    private fun normalizeIssuer(value: String): String = value
        .trim()
        .removeSuffix("/.well-known/openid-configuration")
        .removeSuffix("/.well-known/oauth-authorization-server")
        .trimEnd('/')

    private fun verifyWithAudience(token: String, audience: String): OktaIdentity? {
        val issuer = normalizedIssuer ?: return null
        val provider = jwkProvider ?: return null

        return runCatching {
            val decoded = JWT.decode(token)
            val keyId = decoded.keyId ?: return null
            val jwk = provider.get(keyId)
            val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
            val verifier = JWT.require(algorithm)
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
            val verified = verifier.verify(token)
            verified.toIdentity()
        }.getOrNull()
    }
}
