package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.repository.KnowledgeRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class IndexChunkRequest(
    val tenantId: Long?,
    val sourceType: String,
    val sourceId: String,
    val chunkText: String,
    val embedding: List<Float>,
    val metadata: String? = null,
)

@Serializable
data class SearchChunksRequest(
    val tenantId: Long,
    val embedding: List<Float>,
    val limit: Int = 5,
)

fun Application.ragRoutes(
    knowledgeRepo: KnowledgeRepository,
    internalSecret: String,
) {
    routing {
        route("/v1/internal/rag") {

            post("/chunks") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@post
                }
                val body = call.receive<IndexChunkRequest>()
                if (body.embedding.size != 1536) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "embedding must have exactly 1536 dimensions") },
                    )
                    return@post
                }
                val id = knowledgeRepo.indexChunk(
                    tenantId = body.tenantId,
                    sourceType = body.sourceType,
                    sourceId = body.sourceId,
                    chunkText = body.chunkText,
                    embedding = body.embedding,
                    metadata = body.metadata,
                )
                call.respond(HttpStatusCode.OK, buildJsonObject { put("id", id) })
            }

            post("/search") {
                val secret = call.request.headers["X-Internal-Secret"] ?: ""
                if (secret != internalSecret) {
                    call.respond(HttpStatusCode.Forbidden, buildJsonObject { put("error", "internal_secret_invalid") })
                    return@post
                }
                val body = call.receive<SearchChunksRequest>()
                if (body.embedding.size != 1536) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "embedding must have exactly 1536 dimensions") },
                    )
                    return@post
                }
                val chunks = knowledgeRepo.searchSimilar(
                    embedding = body.embedding,
                    tenantId = body.tenantId,
                    limit = body.limit.coerceIn(1, 20),
                )
                call.respond(HttpStatusCode.OK, chunks)
            }
        }
    }
}
