package io.titlis.api.routes

import io.ktor.client.request.get
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
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.config.ScoreopsClient
import io.titlis.api.domain.*
import io.titlis.api.repository.AiConfigRepository
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.DDCredentials
import io.titlis.api.repository.LabelRegistryRepository
import io.titlis.api.repository.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

private const val VALID_KEY = "tk_queue_test"
private const val TENANT = 7L

private fun Application.setupOperatorQueueTest(
    queueRepo: QueueRepository,
    apiKeyRepo: ApiKeyRepository,
    labelRegistryRepo: LabelRegistryRepository = mockk(relaxed = true),
    aiConfigRepo: AiConfigRepository = mockk(relaxed = true),
    scoreopsClient: ScoreopsClient = mockk(relaxed = true),
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    operatorQueueRoutes(
        queueRepo = queueRepo,
        labelRegistryRepo = labelRegistryRepo,
        aiConfigRepo = aiConfigRepo,
        apiKeyRepo = apiKeyRepo,
        scoreopsClient = scoreopsClient,
        scope = CoroutineScope(Dispatchers.IO),
    )
}

class OperatorQueueObserveTest {

    @Test
    fun `POST queue-observe returns 200 on valid api key`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { queueRepo.recordObservation(TENANT, any()) } returns QueueObserveResponse(
            queueId = 1L, lifecycleState = "DISCOVERING", observationCount = 1,
        )

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.post("/v1/operator/queue/observe") {
            header("X-Api-Key", VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"gcp_pubsub","externalId":"projects/p/subscriptions/s","displayName":"s","numUndeliveredMessages":0}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "DISCOVERING")
        coVerify(exactly = 1) { queueRepo.recordObservation(TENANT, any()) }
    }

    @Test
    fun `POST queue-observe returns 401 without api key`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.post("/v1/operator/queue/observe") {
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"gcp_pubsub","externalId":"s","displayName":"s"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST queue-observe returns 400 on invalid payload`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.post("/v1/operator/queue/observe") {
            header("X-Api-Key", VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

class OperatorQueueObserveBatchTest {

    @Test
    fun `POST queue-observe-batch returns lifecycles for all items`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { queueRepo.recordObservationBatch(TENANT, any()) } returns listOf(
            QueueBatchObserveResponseItem(externalId = "sub-1", queueId = 1L, lifecycleState = "DISCOVERING", observationCount = 1),
            QueueBatchObserveResponseItem(externalId = "sub-2", queueId = 2L, lifecycleState = "LEARNING", observationCount = 4),
        )

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.post("/v1/operator/queue/observe/batch") {
            header("X-Api-Key", VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""[{"provider":"gcp_pubsub","externalId":"sub-1","displayName":"sub-1"},{"provider":"gcp_pubsub","externalId":"sub-2","displayName":"sub-2"}]""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "DISCOVERING")
        assertContains(response.bodyAsText(), "LEARNING")
        assertContains(response.bodyAsText(), "sub-1")
        assertContains(response.bodyAsText(), "sub-2")
        coVerify(exactly = 1) { queueRepo.recordObservationBatch(TENANT, any()) }
    }

    @Test
    fun `POST queue-observe-batch returns empty list for empty input`() = testApplication {
        val queueRepo = mockk<QueueRepository>(relaxed = true)
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.post("/v1/operator/queue/observe/batch") {
            header("X-Api-Key", VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[]")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }

    @Test
    fun `POST queue-observe-batch returns 401 without api key`() = testApplication {
        val queueRepo = mockk<QueueRepository>(relaxed = true)
        val apiKeyRepo = mockk<ApiKeyRepository>()

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.post("/v1/operator/queue/observe/batch") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}

class OperatorQueueLifecycleTest {

    @Test
    fun `GET queue-lifecycle returns lifecycle state`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { queueRepo.getLifecycle(TENANT, "projects/p/subscriptions/s", "gcp_pubsub") } returns
            QueueLifecycleDTO(state = "LEARNING", observationCount = 4, learningTarget = 7)

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.get("/v1/operator/queue/lifecycle?externalId=projects/p/subscriptions/s&provider=gcp_pubsub") {
            header("X-Api-Key", VALID_KEY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "LEARNING")
        assertContains(response.bodyAsText(), "4")
    }

    @Test
    fun `GET queue-lifecycle returns 400 when externalId missing`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.get("/v1/operator/queue/lifecycle") {
            header("X-Api-Key", VALID_KEY)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET queue-lifecycle returns 404 when queue not found`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { queueRepo.getLifecycle(TENANT, "unknown", "gcp_pubsub") } returns null

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.get("/v1/operator/queue/lifecycle?externalId=unknown&provider=gcp_pubsub") {
            header("X-Api-Key", VALID_KEY)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}

class OperatorQueuePromoteTest {

    @Test
    fun `POST queue-promote returns thresholds on success`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { queueRepo.promoteToMonitoring(TENANT, "projects/p/subscriptions/s", "gcp_pubsub") } returns
            QueueThresholdsDTO(
                backlogWarning = 120L, backlogCritical = 150L,
                ageWarningSec = 60L, ageCriticalSec = 90L,
                observationCount = 8,
            )

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.post("/v1/operator/queue/promote?externalId=projects/p/subscriptions/s&provider=gcp_pubsub") {
            header("X-Api-Key", VALID_KEY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "120")
        assertContains(response.bodyAsText(), "150")
    }

    @Test
    fun `POST queue-promote returns 404 when queue not found`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { queueRepo.promoteToMonitoring(TENANT, "unknown", "gcp_pubsub") } returns null

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.post("/v1/operator/queue/promote?externalId=unknown&provider=gcp_pubsub") {
            header("X-Api-Key", VALID_KEY)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}

class OperatorQueueEvaluateTest {

    @Test
    fun `POST queue-evaluate returns 202 immediately`() = testApplication {
        val queueRepo = mockk<QueueRepository>(relaxed = true)
        val apiKeyRepo = mockk<ApiKeyRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT

        application { setupOperatorQueueTest(queueRepo, apiKeyRepo) }

        val response = client.post("/v1/operator/queue/evaluate") {
            header("X-Api-Key", VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody(
                """{
                  "provider":"gcp_pubsub",
                  "externalId":"projects/p/subscriptions/s",
                  "displayName":"s",
                  "tenantId":1,
                  "thresholds":{"backlogWarning":100,"backlogCritical":150,"ageWarningSec":60,"ageCriticalSec":90}
                }""".trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
    }
}

class OperatorQueueConfigTest {

    @Test
    fun `GET datadog-config returns credentials`() = testApplication {
        val apiKeyRepo = mockk<ApiKeyRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { aiConfigRepo.getDDCredentials(TENANT) } returns DDCredentials(
            ddApiKey = "dd-api-key", ddAppKey = "dd-app-key", ddSite = "datadoghq.com",
        )

        application {
            setupOperatorQueueTest(
                queueRepo = mockk(relaxed = true),
                apiKeyRepo = apiKeyRepo,
                aiConfigRepo = aiConfigRepo,
            )
        }

        val response = client.get("/v1/operator/datadog-config") {
            header("X-Api-Key", VALID_KEY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "dd-api-key")
    }

    @Test
    fun `GET datadog-config returns 404 when not configured`() = testApplication {
        val apiKeyRepo = mockk<ApiKeyRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { aiConfigRepo.getDDCredentials(TENANT) } returns null

        application {
            setupOperatorQueueTest(
                queueRepo = mockk(relaxed = true),
                apiKeyRepo = apiKeyRepo,
                aiConfigRepo = aiConfigRepo,
            )
        }

        val response = client.get("/v1/operator/datadog-config") {
            header("X-Api-Key", VALID_KEY)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET queue-config returns enabled flag`() = testApplication {
        val apiKeyRepo = mockk<ApiKeyRepository>()
        val aiConfigRepo = mockk<AiConfigRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { aiConfigRepo.isQueueMonitoringEnabled(TENANT) } returns true
        coEvery { aiConfigRepo.isMonitorCreationEnabled(TENANT) } returns false

        application {
            setupOperatorQueueTest(
                queueRepo = mockk(relaxed = true),
                apiKeyRepo = apiKeyRepo,
                aiConfigRepo = aiConfigRepo,
            )
        }

        val response = client.get("/v1/operator/queue-config") {
            header("X-Api-Key", VALID_KEY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "\"enabled\":true")
    }

    @Test
    fun `GET label-registry returns labels grouped by key`() = testApplication {
        val apiKeyRepo = mockk<ApiKeyRepository>()
        val labelRegistryRepo = mockk<LabelRegistryRepository>()
        coEvery { apiKeyRepo.resolveByToken(VALID_KEY) } returns TENANT
        coEvery { labelRegistryRepo.listForOperator(TENANT) } returns listOf(
            LabelKeyValuesDTO(key = "env", values = listOf("production", "staging")),
            LabelKeyValuesDTO(key = "team", values = listOf("platform")),
        )

        application {
            setupOperatorQueueTest(
                queueRepo = mockk(relaxed = true),
                apiKeyRepo = apiKeyRepo,
                labelRegistryRepo = labelRegistryRepo,
            )
        }

        val response = client.get("/v1/operator/label-registry") {
            header("X-Api-Key", VALID_KEY)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "\"env\"")
        assertContains(response.bodyAsText(), "production")
    }
}
