package io.titlis.api.auth

import io.titlis.api.config.AuthConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class LocalAccessTokenPayload(
    val iss: String,
    val aud: String,
    val sub: String,
    val tenantId: Long,
    val role: String,
    val authProvider: String,
    val iat: Long,
    val exp: Long,
)

data class IssuedLocalToken(
    val value: String,
    val expiresAt: Instant,
)

class LocalTokenService(
    private val config: AuthConfig,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun issue(user: AuthenticatedUser): IssuedLocalToken {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plusSeconds(config.accessTokenTtlMinutes * 60)
        val header = """{"alg":"HS256","typ":"TITLIS"}"""
        val payload = json.encodeToString(
            LocalAccessTokenPayload(
                iss = config.issuer,
                aud = config.audience,
                sub = user.id.toString(),
                tenantId = user.tenantId,
                role = user.role.dbValue,
                authProvider = user.authProvider,
                iat = issuedAt.epochSecond,
                exp = expiresAt.epochSecond,
            ),
        )

        val encodedHeader = encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8))
        val encodedPayload = encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = encoder.encodeToString(sign("$encodedHeader.$encodedPayload"))

        return IssuedLocalToken(
            value = "$encodedHeader.$encodedPayload.$signature",
            expiresAt = expiresAt,
        )
    }

    fun verify(token: String): LocalAccessTokenPayload? {
        val segments = token.split('.')
        if (segments.size != 3) return null

        val signedPayload = "${segments[0]}.${segments[1]}"
        val expectedSignature = sign(signedPayload)
        val actualSignature = runCatching { decoder.decode(segments[2]) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) return null

        val payloadBytes = runCatching { decoder.decode(segments[1]) }.getOrNull() ?: return null
        val payload = runCatching {
            json.decodeFromString<LocalAccessTokenPayload>(payloadBytes.toString(StandardCharsets.UTF_8))
        }.getOrNull() ?: return null

        if (payload.iss != config.issuer || payload.aud != config.audience) return null
        if (payload.exp <= Instant.now().epochSecond) return null
        return payload
    }

    private fun sign(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(config.accessTokenSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }
}
