package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.requireRole
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.ScorecardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// ── request / response models ────────────────────────────────────────────────

@Serializable
data class SetGithubLinkRequest(
    @SerialName("repoUrl")         val repoUrl: String,
    @SerialName("serviceYamlPath") val serviceYamlPath: String = ".titlis/service.yaml",
)

@Serializable
data class GithubLinkNotLinked(val linked: Boolean = false)

@Serializable
data class GithubLinkResponse(
    val linked: Boolean = true,
    @SerialName("repo_url")          val repoUrl: String,
    @SerialName("service_yaml_path") val serviceYamlPath: String,
)

@Serializable
data class GithubLinkSetResponse(
    val linked: Boolean = true,
    @SerialName("repo_url")           val repoUrl: String,
    @SerialName("service_yaml_path")  val serviceYamlPath: String,
    @SerialName("service_yaml_found") val serviceYamlFound: Boolean,
)

@Serializable
data class RepoSearchItem(
    @SerialName("full_name")    val fullName: String,
    @SerialName("html_url")     val htmlUrl: String,
    val description: String,
)

@Serializable
data class RepoSearchResponse(val items: List<RepoSearchItem>)

// ── helpers ──────────────────────────────────────────────────────────────────

private val githubHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

private val jsonParser = Json { ignoreUnknownKeys = true }

private fun parseOwnerRepo(repoUrl: String): Pair<String, String>? {
    val normalized = repoUrl.trim().trimEnd('/')
    val match = Regex("github\\.com/([^/]+)/([^/]+)").find(normalized) ?: return null
    val owner = match.groupValues[1]
    val repo  = match.groupValues[2].removeSuffix(".git")
    return owner to repo
}

private suspend fun checkServiceYamlAt(owner: String, repo: String, path: String, token: String): Boolean =
    withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/repos/$owner/$repo/contents/${path.trimStart('/')}"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build()
        githubHttpClient.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    }

private suspend fun searchRepos(query: String, token: String): List<RepoSearchItem> =
    withContext(Dispatchers.IO) {
        val encodedQ = java.net.URLEncoder.encode(query, "UTF-8")
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/search/repositories?q=$encodedQ&per_page=8&sort=updated"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build()
        val resp = githubHttpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return@withContext emptyList()

        val root = jsonParser.parseToJsonElement(resp.body()).jsonObject
        root["items"]?.jsonArray?.mapNotNull { item ->
            val obj      = item.jsonObject
            val fullName = obj["full_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val htmlUrl  = obj["html_url"]?.jsonPrimitive?.content  ?: return@mapNotNull null
            val desc     = obj["description"]?.jsonPrimitive?.content ?: ""
            RepoSearchItem(fullName, htmlUrl, desc)
        } ?: emptyList()
    }

// ── rotas ────────────────────────────────────────────────────────────────────

fun Application.gitHubLinkRoutes(
    scorecardRepo: ScorecardRepository,
    aiConfigRepo: AiConfigRepository,
    requestAuthenticator: RequestAuthenticator? = null,
) {
    routing {
        route("/v1") {
            fun Route.endpoints() {

                get("/workloads/{workloadId}/github-link") {
                    val principal  = call.requireRole() ?: return@get
                    val workloadId = call.parameters["workloadId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "workloadId required"))

                    val link = scorecardRepo.getGithubLink(workloadId, principal.tenantId)
                    if (link == null) {
                        call.respond(GithubLinkNotLinked())
                        return@get
                    }
                    call.respond(GithubLinkResponse(repoUrl = link.repoUrl, serviceYamlPath = link.serviceYamlPath))
                }

                post("/workloads/{workloadId}/github-link") {
                    val principal  = call.requireRole() ?: return@post
                    val workloadId = call.parameters["workloadId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "workloadId required"))

                    val body            = call.receive<SetGithubLinkRequest>()
                    val repoUrl         = body.repoUrl.trim()
                    val serviceYamlPath = body.serviceYamlPath.trim().ifBlank { ".titlis/service.yaml" }

                    val ownerRepo = parseOwnerRepo(repoUrl)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "invalid_repo_url", "message" to "Use o formato https://github.com/org/repo"),
                        )

                    val token = aiConfigRepo.getByTenant(principal.tenantId)?.githubTokenEnc?.takeIf { it.isNotBlank() }
                        ?: return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            mapOf("error" to "github_not_configured", "message" to "Configure o token GitHub em Configurações › ARIA"),
                        )

                    val (owner, repo)    = ownerRepo
                    val serviceYamlFound = checkServiceYamlAt(owner, repo, serviceYamlPath, token)

                    scorecardRepo.setGithubLink(workloadId, principal.tenantId, repoUrl, serviceYamlPath)

                    call.respond(
                        GithubLinkSetResponse(
                            repoUrl         = repoUrl,
                            serviceYamlPath = serviceYamlPath,
                            serviceYamlFound = serviceYamlFound,
                        ),
                    )
                }

                delete("/workloads/{workloadId}/github-link") {
                    val principal  = call.requireRole() ?: return@delete
                    val workloadId = call.parameters["workloadId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "workloadId required"))

                    scorecardRepo.setGithubLink(workloadId, principal.tenantId, null, null)
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/github/repos/search") {
                    val principal = call.requireRole() ?: return@get
                    val q         = call.request.queryParameters["q"]?.trim()
                    if (q.isNullOrBlank()) {
                        call.respond(RepoSearchResponse(emptyList()))
                        return@get
                    }

                    val token = aiConfigRepo.getByTenant(principal.tenantId)?.githubTokenEnc?.takeIf { it.isNotBlank() }
                        ?: return@get call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            mapOf("error" to "github_not_configured", "message" to "Configure o token GitHub em Configurações › ARIA"),
                        )

                    call.respond(RepoSearchResponse(searchRepos(q, token)))
                }
            }

            if (requestAuthenticator == null) {
                endpoints()
            } else {
                authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
                    endpoints()
                }
            }
        }
    }
}
