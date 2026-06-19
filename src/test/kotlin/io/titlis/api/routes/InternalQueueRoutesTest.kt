package io.titlis.api.routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.repository.QueueRepository
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

private const val QUEUE_INTERNAL_SECRET = "queue-internal-test-secret"

private fun Application.setupInternalQueueTest(queueRepo: QueueRepository) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    internalQueueRoutes(queueRepo, QUEUE_INTERNAL_SECRET)
}

class InternalQueueEvaluatedTest {

    private val validEvent = """
        {
          "tenantId": 1,
          "provider": "gcp_pubsub",
          "externalId": "projects/p/subscriptions/my-sub",
          "overallScore": 87.5,
          "complianceStatus": "NON_COMPLIANT",
          "totalRules": 10,
          "passedRules": 7,
          "failedRules": 3,
          "criticalFailures": 1,
          "pillarScores": [],
          "validationResults": []
        }
    """.trimIndent()

    @Test
    fun `POST queue-evaluated returns 204 with valid secret`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        coEvery { queueRepo.upsertQueueScorecard(any()) } returns Unit

        application { setupInternalQueueTest(queueRepo) }

        val response = client.post("/v1/internal/scoreops/queue-evaluated") {
            header("X-Internal-Secret", QUEUE_INTERNAL_SECRET)
            contentType(ContentType.Application.Json)
            setBody(validEvent)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify(exactly = 1) { queueRepo.upsertQueueScorecard(any()) }
    }

    @Test
    fun `POST queue-evaluated returns 401 with wrong secret`() = testApplication {
        val queueRepo = mockk<QueueRepository>()

        application { setupInternalQueueTest(queueRepo) }

        val response = client.post("/v1/internal/scoreops/queue-evaluated") {
            header("X-Internal-Secret", "wrong-secret")
            contentType(ContentType.Application.Json)
            setBody(validEvent)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        coVerify(exactly = 0) { queueRepo.upsertQueueScorecard(any()) }
    }

    @Test
    fun `POST queue-evaluated returns 401 when secret header missing`() = testApplication {
        val queueRepo = mockk<QueueRepository>()

        application { setupInternalQueueTest(queueRepo) }

        val response = client.post("/v1/internal/scoreops/queue-evaluated") {
            contentType(ContentType.Application.Json)
            setBody(validEvent)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST queue-evaluated returns 400 on invalid payload`() = testApplication {
        val queueRepo = mockk<QueueRepository>()

        application { setupInternalQueueTest(queueRepo) }

        val response = client.post("/v1/internal/scoreops/queue-evaluated") {
            header("X-Internal-Secret", QUEUE_INTERNAL_SECRET)
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST queue-evaluated passes event data to repository`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        var capturedTenantId = 0L
        var capturedExternalId = ""
        coEvery { queueRepo.upsertQueueScorecard(any()) } answers {
            val event = firstArg<io.titlis.api.domain.QueueEvaluatedEvent>()
            capturedTenantId = event.tenantId
            capturedExternalId = event.externalId
        }

        application { setupInternalQueueTest(queueRepo) }

        client.post("/v1/internal/scoreops/queue-evaluated") {
            header("X-Internal-Secret", QUEUE_INTERNAL_SECRET)
            contentType(ContentType.Application.Json)
            setBody(validEvent)
        }

        assertEquals(1L, capturedTenantId)
        assertEquals("projects/p/subscriptions/my-sub", capturedExternalId)
    }
}
