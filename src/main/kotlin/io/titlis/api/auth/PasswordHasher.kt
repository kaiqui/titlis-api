package io.titlis.api.auth

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PasswordHasher(
    private val iterations: Int = 210_000,
    private val keyLength: Int = 256,
) {
    private val secureRandom = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val hash = pbkdf2(password.toCharArray(), salt)
        return buildString {
            append("pbkdf2_sha256$")
            append(iterations)
            append('$')
            append(Base64.getEncoder().encodeToString(salt))
            append('$')
            append(Base64.getEncoder().encodeToString(hash))
        }
    }

    fun verify(password: String, encodedHash: String): Boolean {
        val parts = encodedHash.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false

        val parsedIterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val computed = pbkdf2(password.toCharArray(), salt, parsedIterations)
        return expected.contentEquals(computed)
    }

    private fun pbkdf2(
        password: CharArray,
        salt: ByteArray,
        overrideIterations: Int = iterations,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, overrideIterations, keyLength)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
