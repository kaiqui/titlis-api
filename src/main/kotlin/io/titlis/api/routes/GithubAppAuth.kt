package io.titlis.api.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.titlis.api.repository.TenantAiConfigRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

// ── resultado da resolução de credencial GitHub ─────────────────────────────

sealed interface GithubTokenResult {
    data class Ok(val token: String) : GithubTokenResult
    data object NotConfigured : GithubTokenResult
    data class Error(val message: String) : GithubTokenResult
}

private val log = LoggerFactory.getLogger("io.titlis.api.routes.GithubAppAuth")

private const val GITHUB_API = "https://api.github.com"

private val githubAppHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

private val githubAppJson = Json { ignoreUnknownKeys = true }

// ── resolução de token usável (PAT ou installation token do GitHub App) ─────

suspend fun resolveGithubToken(config: TenantAiConfigRecord?): GithubTokenResult {
    if (config == null) return GithubTokenResult.NotConfigured

    val pat       = config.githubTokenEnc?.takeIf { it.isNotBlank() }
    val appId     = config.githubAppIdEnc?.takeIf { it.isNotBlank() }
    val privKey   = config.githubAppPrivKeyEnc?.takeIf { it.isNotBlank() }
    val installId = config.githubAppInstallIdEnc?.takeIf { it.isNotBlank() }
    val appConfigured = appId != null && privKey != null

    // Modo explícito github_app tem prioridade (espelha _github_session_kwargs em titlis-ai)
    val preferApp = config.githubAuthMode == "github_app" && appConfigured

    if (preferApp || (pat == null && appConfigured)) {
        return fetchInstallationToken(appId!!, privKey!!, installId)
    }

    if (pat != null) return GithubTokenResult.Ok(pat)

    return GithubTokenResult.NotConfigured
}

// ── installation token via GitHub App ───────────────────────────────────────

private suspend fun fetchInstallationToken(
    appId: String,
    privateKeyPem: String,
    installationId: String?,
): GithubTokenResult = withContext(Dispatchers.IO) {
    val appJwt = try {
        generateAppJwt(appId, privateKeyPem)
    } catch (e: Exception) {
        log.warn("Falha ao gerar JWT do GitHub App (app_id={})", appId, e)
        return@withContext GithubTokenResult.Error("Chave privada do GitHub App inválida")
    }

    val resolvedInstallId = installationId ?: resolveInstallationId(appJwt, appId)
        ?: return@withContext GithubTokenResult.Error(
            "GitHub App não está instalado em nenhuma conta acessível",
        )

    val req = HttpRequest.newBuilder()
        .uri(URI.create("$GITHUB_API/app/installations/$resolvedInstallId/access_tokens"))
        .header("Authorization", "Bearer $appJwt")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .POST(HttpRequest.BodyPublishers.noBody())
        .build()

    val resp = githubAppHttpClient.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() !in 200..299) {
        log.warn("Falha ao obter installation token (status={}, app_id={})", resp.statusCode(), appId)
        return@withContext GithubTokenResult.Error(
            "GitHub recusou a credencial do App (HTTP ${resp.statusCode()})",
        )
    }

    val token = githubAppJson.parseToJsonElement(resp.body()).jsonObject["token"]?.jsonPrimitive?.content
        ?: return@withContext GithubTokenResult.Error("Resposta do GitHub sem campo 'token'")

    GithubTokenResult.Ok(token)
}

private fun resolveInstallationId(appJwt: String, appId: String): String? {
    val req = HttpRequest.newBuilder()
        .uri(URI.create("$GITHUB_API/app/installations"))
        .header("Authorization", "Bearer $appJwt")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .GET()
        .build()

    val resp = githubAppHttpClient.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() !in 200..299) {
        log.warn("Falha ao listar instalações do GitHub App (status={}, app_id={})", resp.statusCode(), appId)
        return null
    }

    val installations = githubAppJson.parseToJsonElement(resp.body()).jsonArray
    if (installations.isEmpty()) return null
    if (installations.size > 1) {
        log.warn("GitHub App tem múltiplas instalações — usando a primeira (app_id={})", appId)
    }
    return installations.first().jsonObject["id"]?.jsonPrimitive?.content
}

private fun generateAppJwt(appId: String, privateKeyPem: String): String {
    val privateKey = parseRsaPrivateKey(privateKeyPem)
    val algorithm  = Algorithm.RSA256(null, privateKey)
    val now        = Instant.now()
    return JWT.create()
        .withIssuer(appId)
        .withIssuedAt(Date.from(now.minusSeconds(60)))
        .withExpiresAt(Date.from(now.plusSeconds(600)))
        .sign(algorithm)
}

// ── parsing de chave privada RSA (aceita PKCS#1 e PKCS#8) ────────────────────

internal fun parseRsaPrivateKey(pem: String): RSAPrivateKey {
    val normalized = pem.trim()
    val der = Base64.getMimeDecoder().decode(
        normalized
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s"), ""),
    )

    // GitHub distribui chaves em PKCS#1 (-----BEGIN RSA PRIVATE KEY-----).
    // O KeyFactory nativo só lê PKCS#8, então embrulhamos o DER PKCS#1 em PKCS#8.
    val pkcs8 = if (normalized.contains("BEGIN RSA PRIVATE KEY")) wrapPkcs1AsPkcs8(der) else der

    val keyFactory = KeyFactory.getInstance("RSA")
    return keyFactory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateKey
}

private fun wrapPkcs1AsPkcs8(pkcs1Der: ByteArray): ByteArray {
    // PrivateKeyInfo ::= SEQUENCE { version INTEGER(0), algorithm AlgorithmIdentifier, privateKey OCTET STRING }
    val version   = derTlv(0x02, byteArrayOf(0x00))
    // AlgorithmIdentifier { OID rsaEncryption (1.2.840.113549.1.1.1), NULL }
    val rsaOid    = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01)
    val nullParam = byteArrayOf(0x05, 0x00)
    val algorithm = derTlv(0x30, rsaOid + nullParam)
    val keyOctet  = derTlv(0x04, pkcs1Der)
    return derTlv(0x30, version + algorithm + keyOctet)
}

private fun derTlv(tag: Int, content: ByteArray): ByteArray =
    byteArrayOf(tag.toByte()) + derLength(content.size) + content

private fun derLength(len: Int): ByteArray {
    if (len < 0x80) return byteArrayOf(len.toByte())
    val bytes = ArrayList<Byte>()
    var v = len
    while (v > 0) {
        bytes.add(0, (v and 0xFF).toByte())
        v = v ushr 8
    }
    return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
}
