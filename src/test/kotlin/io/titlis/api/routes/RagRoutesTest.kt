package io.titlis.api.routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import io.titlis.api.repository.KnowledgeChunk
import io.titlis.api.repository.KnowledgeRepository
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

private const val RAG_SECRET = "test-rag-secret"

private fun fakeEmbedding(size: Int = 1536) = List(size) { 0.0f }

private fun indexBody(
    dims: Int = 1536,
    tenantId: Long? = null,
    sourceType: String = "global_rule_doc",
    sourceId: String = "RES-001",
) = buildString {
    append("""{"tenantId":${tenantId ?: "null"},"sourceType":"$sourceType","sourceId":"$sourceId",""")
    append(""""chunkText":"test chunk","embedding":[""")
    append(fakeEmbedding(dims).joinToString(","))
    append("]}")
}

private fun searchBody(dims: Int = 1536, tenantId: Long = 1L) = buildString {
    append("""{"tenantId":$tenantId,"embedding":[""")
    append(fakeEmbedding(dims).joinToString(","))
    append("]}")
}

private fun Application.installContentNegotiation() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

class RagRoutesChunksTest {

    @Test
    fun `POST chunks returns 200 with chunk id`() = testApplication {
        val repo = mockk<KnowledgeRepository>()
        coEvery { repo.indexChunk(any(), any(), any(), any(), any(), any()) } returns "uuid-123"

        application {
            installContentNegotiation()
            ragRoutes(repo, RAG_SECRET)
        }

        val response = client.post("/v1/internal/rag/chunks") {
            header("X-Internal-Secret", RAG_SECRET)
            contentType(ContentType.Application.Json)
            setBody(indexBody())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "uuid-123")
    }

    @Test
    fun `POST chunks returns 403 without secret`() = testApplication {
        val repo = mockk<KnowledgeRepository>()

        application {
            installContentNegotiation()
            ragRoutes(repo, RAG_SECRET)
        }

        val response = client.post("/v1/internal/rag/chunks") {
            contentType(ContentType.Application.Json)
            setBody(indexBody())
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST chunks returns 400 when embedding has wrong dimensions`() = testApplication {
        val repo = mockk<KnowledgeRepository>()

        application {
            installContentNegotiation()
            ragRoutes(repo, RAG_SECRET)
        }

        val response = client.post("/v1/internal/rag/chunks") {
            header("X-Internal-Secret", RAG_SECRET)
            contentType(ContentType.Application.Json)
            setBody(indexBody(dims = 128))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "1536")
    }
}

class RagRoutesSearchTest {

    @Test
    fun `POST search returns matching chunks`() = testApplication {
        val repo = mockk<KnowledgeRepository>()
        coEvery { repo.searchSimilar(any(), any(), any()) } returns listOf(
            KnowledgeChunk(
                id = "chunk-1",
                tenantId = null,
                sourceType = "global_rule_doc",
                sourceId = "RES-001",
                chunkText = "Liveness probe doc",
                metadata = null,
            )
        )

        application {
            installContentNegotiation()
            ragRoutes(repo, RAG_SECRET)
        }

        val response = client.post("/v1/internal/rag/search") {
            header("X-Internal-Secret", RAG_SECRET)
            contentType(ContentType.Application.Json)
            setBody(searchBody())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "Liveness probe doc")
    }

    @Test
    fun `POST search returns 403 without secret`() = testApplication {
        val repo = mockk<KnowledgeRepository>()

        application {
            installContentNegotiation()
            ragRoutes(repo, RAG_SECRET)
        }

        val response = client.post("/v1/internal/rag/search") {
            contentType(ContentType.Application.Json)
            setBody(searchBody())
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `POST search returns 400 when embedding has wrong dimensions`() = testApplication {
        val repo = mockk<KnowledgeRepository>()

        application {
            installContentNegotiation()
            ragRoutes(repo, RAG_SECRET)
        }

        val response = client.post("/v1/internal/rag/search") {
            header("X-Internal-Secret", RAG_SECRET)
            contentType(ContentType.Application.Json)
            setBody(searchBody(dims = 512))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
