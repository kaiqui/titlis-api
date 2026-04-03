package io.titlis.api.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OidcDiscoveryVerifierTest {

    @Test
    fun `verify accepts valid discovery payload`() = withDiscoveryServer(
        responseCode = 200,
        responseBody = """
            {
              "issuer": "http://127.0.0.1:%PORT%/oauth2/default",
              "jwks_uri": "http://127.0.0.1:%PORT%/oauth2/default/v1/keys"
            }
        """.trimIndent(),
    ) { issuerUrl ->
        val verifier = DefaultOidcDiscoveryVerifier()
        val result = verifier.verify(issuerUrl)

        assertEquals(issuerUrl, result.issuer)
        assertEquals("$issuerUrl/.well-known/openid-configuration", result.discoveryUrl)
        assertEquals("$issuerUrl/v1/keys", result.jwksUri)
    }

    @Test
    fun `verify fails when discovery issuer mismatches configured issuer`() = withDiscoveryServer(
        responseCode = 200,
        responseBody = """
            {
              "issuer": "http://127.0.0.1:%PORT%/oauth2/other",
              "jwks_uri": "http://127.0.0.1:%PORT%/oauth2/default/v1/keys"
            }
        """.trimIndent(),
    ) { issuerUrl ->
        val verifier = DefaultOidcDiscoveryVerifier()
        val error = assertFailsWith<TenantAuthIntegrationValidationException> {
            verifier.verify(issuerUrl)
        }
        assertEquals("oidc_discovery_issuer_mismatch", error.message)
    }

    @Test
    fun `verify fails when jwks_uri is missing`() = withDiscoveryServer(
        responseCode = 200,
        responseBody = """
            {
              "issuer": "http://127.0.0.1:%PORT%/oauth2/default"
            }
        """.trimIndent(),
    ) { issuerUrl ->
        val verifier = DefaultOidcDiscoveryVerifier()
        val error = assertFailsWith<TenantAuthIntegrationValidationException> {
            verifier.verify(issuerUrl)
        }
        assertEquals("oidc_discovery_missing_jwks_uri", error.message)
    }

    @Test
    fun `verify fails when discovery payload is invalid json`() = withDiscoveryServer(
        responseCode = 200,
        responseBody = """not-json""",
    ) { issuerUrl ->
        val verifier = DefaultOidcDiscoveryVerifier()
        val error = assertFailsWith<TenantAuthIntegrationValidationException> {
            verifier.verify(issuerUrl)
        }
        assertEquals("oidc_discovery_invalid_json", error.message)
    }

    @Test
    fun `verify fails when discovery endpoint is unreachable`() {
        val verifier = DefaultOidcDiscoveryVerifier()
        val error = assertFailsWith<TenantAuthIntegrationValidationException> {
            verifier.verify("http://127.0.0.1:1/oauth2/default")
        }
        assertEquals("oidc_discovery_unreachable", error.message)
    }

    private fun withDiscoveryServer(
        responseCode: Int,
        responseBody: String,
        testBlock: (issuerUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            val port = server.address.port
            val body = responseBody.replace("%PORT%", port.toString())
            server.createContext("/oauth2/default/.well-known/openid-configuration") { exchange ->
                exchange.respond(responseCode, body)
            }
            server.start()

            testBlock("http://127.0.0.1:$port/oauth2/default")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { output ->
            output.write(bytes)
        }
    }
}
