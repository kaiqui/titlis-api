package io.titlis.api.config

import io.titlis.api.dto.ScoreResultDTO
import io.titlis.api.dto.WorkloadSnapshotDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ScoreopsClient(
    private val baseUrl: String,
    private val secret: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun evaluateWorkload(snapshot: WorkloadSnapshotDTO): ScoreResultDTO {
        val body = json.encodeToString(snapshot)
        val resp = post("/v1/scoring/evaluate", body)
        if (resp.statusCode() >= 400) {
            throw IllegalStateException("scoreops evaluate failed: ${resp.statusCode()}")
        }
        return json.decodeFromString<ScoreResultDTO>(resp.body())
    }

    suspend fun get(path: String): HttpResponse<String> = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("X-Internal-Secret", secret)
            .header("Accept", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()
        http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    suspend fun post(path: String, body: String): HttpResponse<String> = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("X-Internal-Secret", secret)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build()
        http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    suspend fun put(path: String, body: String): HttpResponse<String> = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("X-Internal-Secret", secret)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build()
        http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    suspend fun delete(path: String): HttpResponse<String> = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("X-Internal-Secret", secret)
            .DELETE()
            .timeout(Duration.ofSeconds(10))
            .build()
        http.send(req, HttpResponse.BodyHandlers.ofString())
    }
}
