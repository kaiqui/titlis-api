package io.titlis.api.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class PrbotClient(private val baseUrl: String, private val secret: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    suspend fun triggerManifestCampaign(tenantId: Long, campaignId: String): Pair<Int, String> =
        proxy("POST", "/v1/manifest-campaigns?tenant_id=$tenantId", """{"tenant_id":$tenantId,"campaign_id":"$campaignId"}""")

    suspend fun proxy(method: String, path: String, body: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl$path"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Secret", secret)
                .timeout(Duration.ofSeconds(30))
            when (method.uppercase()) {
                "GET"    -> builder.GET()
                "POST"   -> builder.POST(body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                "PUT"    -> builder.PUT(body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                "DELETE" -> builder.DELETE()
                else     -> builder.GET()
            }
            val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            resp.statusCode() to resp.body()
        }
}
