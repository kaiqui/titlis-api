package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.config.ScoreopsClient
import io.titlis.api.dto.WorkloadSnapshotDTO
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.TagRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

fun Application.operatorScoringRoutes(
    scoreopsClient: ScoreopsClient,
    apiKeyRepo: ApiKeyRepository,
    tagRepo: TagRepository,
    scope: CoroutineScope,
) {
    val log = LoggerFactory.getLogger("OperatorScoringRoutes")
    routing {
        route("/v1/operator/scoring") {
            post("/evaluate") {
                val tenantId = resolveApiKeyTenant(call, apiKeyRepo)
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "invalid_api_key"),
                    )
                val snapshot = call.receive<WorkloadSnapshotDTO>()
                    .withTenant(tenantId)
                    .withEnrichedTags(tenantId, tagRepo)

                call.respond(HttpStatusCode.Accepted)
                scope.launch {
                    try {
                        scoreopsClient.evaluateWorkload(snapshot)
                    } catch (e: Exception) {
                        log.warn("scoreops evaluate failed uid={} tenant={}", snapshot.uid, tenantId, e)
                    }
                }
            }
        }
    }
}

private val ENV_TAG_VALUES = setOf("dev", "hml", "prd")

private suspend fun WorkloadSnapshotDTO.withEnrichedTags(
    tenantId: Long,
    tagRepo: TagRepository,
): WorkloadSnapshotDTO {
    val clusterId = tagRepo.findClusterIdByName(tenantId, cluster)
        ?: return this
    val clusterTags = tagRepo.listTagsForResource(tenantId, "cluster", clusterId)
    val namespaceTags = tagRepo.findNamespaceIdByName(clusterId, namespace)
        ?.let { tagRepo.listTagsForResource(tenantId, "namespace", it) }
        ?: emptyList()

    // Priority: env:* tag on cluster or namespace > Clusters.environment column
    val envFromTag = (clusterTags + namespaceTags)
        .firstOrNull { it.startsWith("env:") }
        ?.removePrefix("env:")
        ?.takeIf { it in ENV_TAG_VALUES }
    val resolvedEnv = envFromTag
        ?: tagRepo.findClusterEnvironment(tenantId, cluster)
        ?: ""

    return withTags(clusterTags, namespaceTags, resolvedEnv)
}
