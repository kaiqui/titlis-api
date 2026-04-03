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
    val roles: List<String>,
    val issuer: String,
)

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
        val issuer = normalizedIssuer ?: return null
        val audience = config.oktaAudience ?: return null
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

    fun payloadToIdentity(payload: Payload): OktaIdentity? = runCatching {
        payload.toIdentity()
    }.getOrNull()

    private fun Payload.toIdentity(): OktaIdentity = OktaIdentity(
        subject = subject,
        email = getClaim("email")?.asString(),
        tenantId = getClaim("titlis_tenant_id")?.asString()?.toLongOrNull()
            ?: getClaim("titlis_tenant_id")?.asLong(),
        roles = getClaim("titlis_roles")?.asList(String::class.java)?.filterNotNull().orEmpty(),
        issuer = issuer,
    )

    private fun normalizeIssuer(value: String): String = value
        .trim()
        .removeSuffix("/.well-known/openid-configuration")
        .removeSuffix("/.well-known/oauth-authorization-server")
        .trimEnd('/')
}
