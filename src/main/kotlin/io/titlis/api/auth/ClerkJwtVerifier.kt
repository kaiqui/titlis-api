package io.titlis.api.auth

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

data class ClerkIdentity(
    val clerkUserId: String,
    val email: String?,
)

class ClerkJwtVerifier(jwksUrl: String) {
    private val log = LoggerFactory.getLogger(ClerkJwtVerifier::class.java)

    private val jwkProvider = JwkProviderBuilder(URI.create(jwksUrl).toURL())
        .cached(10, 1, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        // Tokens de sessão Clerk são short-lived; leeway cobre clock skew entre API e Clerk.
        .build()

    fun verify(token: String): ClerkIdentity? = runCatching {
        val decoded = JWT.decode(token)
        val keyId = decoded.keyId ?: run {
            log.warn("clerk_verify sem kid no header do token")
            return null
        }
        val jwk = jwkProvider.get(keyId)
        val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
        val verified = JWT.require(algorithm)
            .acceptLeeway(30)
            .build()
            .verify(token)
        ClerkIdentity(
            clerkUserId = verified.subject,
            // O session token padrão do Clerk NÃO traz email. Para o fluxo de convite
            // funcionar, configure no Clerk Dashboard → Sessions → Customize session token:
            //   { "email": "{{user.primary_email_address}}" }
            // Aqui aceitamos os nomes de claim mais comuns.
            email = firstNonBlankClaim(verified, "email", "email_address", "primary_email_address"),
        )
    }.onFailure {
        log.warn("clerk_verify falhou: {} — {}", it::class.simpleName, it.message)
    }.getOrNull()

    private fun firstNonBlankClaim(
        verified: com.auth0.jwt.interfaces.DecodedJWT,
        vararg names: String,
    ): String? = names.firstNotNullOfOrNull { name ->
        verified.getClaim(name)?.asString()?.trim()?.takeIf { it.isNotBlank() }
    }
}
