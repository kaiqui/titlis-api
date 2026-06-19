package io.titlis.api.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.domain.*
import io.titlis.api.repository.QueueRepository
import io.titlis.api.repository.ServiceDefinitionRepository
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

private val SAMPLE_QUEUE_LIST = listOf(
    QueueSummaryDTO(
        queueId = 1L,
        externalId = "projects/proj/subscriptions/orders-sub",
        displayName = "orders-sub",
        provider = "gcp_pubsub",
        isDlq = false,
        lifecycleState = "MONITORING",
        observationCount = 10,
        overallScore = 87.5,
        complianceStatus = "NON_COMPLIANT",
        firstSeenAt = "2026-01-01T00:00:00Z",
        lastSeenAt = "2026-06-01T00:00:00Z",
    ),
    QueueSummaryDTO(
        queueId = 2L,
        externalId = "projects/proj/subscriptions/payments-sub",
        displayName = "payments-sub",
        provider = "gcp_pubsub",
        isDlq = false,
        lifecycleState = "LEARNING",
        observationCount = 3,
        firstSeenAt = "2026-01-01T00:00:00Z",
        lastSeenAt = "2026-06-01T00:00:00Z",
    ),
)

private val SAMPLE_DETAIL = QueueDetailDTO(
    queueId = 1L,
    externalId = "projects/proj/subscriptions/orders-sub",
    displayName = "orders-sub",
    provider = "gcp_pubsub",
    isDlq = false,
    lifecycleState = "MONITORING",
    observationCount = 10,
    overallScore = 87.5,
    complianceStatus = "NON_COMPLIANT",
    totalRules = 10,
    passedRules = 7,
    failedRules = 3,
    criticalFailures = 1,
    pillarScores = listOf(
        QueuePillarScoreDTO(pillar = "RELIABILITY", pillarScore = 85.0, passedChecks = 3, failedChecks = 1),
    ),
    validationResults = listOf(
        QueueValidationResultDTO(ruleId = "QS-001", rulePassed = false, resultMessage = "Missing env label"),
    ),
    firstSeenAt = "2026-01-01T00:00:00Z",
    lastSeenAt = "2026-06-01T00:00:00Z",
)

private val SAMPLE_THRESHOLDS = QueueThresholdsDTO(
    backlogWarning = 120L,
    backlogCritical = 150L,
    ageWarningSec = 60L,
    ageCriticalSec = 90L,
    p50Backlog = 80L,
    p75Backlog = 100L,
    p95Backlog = 110L,
    observationCount = 8,
)

class QueueRoutesListTest {

    @Test
    fun `GET queues returns list for authenticated user`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { queueRepo.listQueues(1L) } returns SAMPLE_QUEUE_LIST

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.get("/v1/queues") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "orders-sub")
        assertContains(body, "payments-sub")
        assertContains(body, "MONITORING")
        assertContains(body, "LEARNING")
    }

    @Test
    fun `GET queues returns 401 without authentication`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.get("/v1/queues")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET queues returns empty list when no queues`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { queueRepo.listQueues(1L) } returns emptyList()

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.get("/v1/queues") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText().trim())
    }
}

class QueueRoutesScorecardTest {

    @Test
    fun `GET queues id scorecard returns detail`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { queueRepo.getQueueScorecard(1L, 1L) } returns SAMPLE_DETAIL

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.get("/v1/queues/1/scorecard") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "orders-sub")
        assertContains(body, "87.5")
        assertContains(body, "QS-001")
        assertContains(body, "RELIABILITY")
    }

    @Test
    fun `GET queues id scorecard returns 404 when not found`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { queueRepo.getQueueScorecard(1L, 999L) } returns null

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.get("/v1/queues/999/scorecard") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET queues id scorecard returns 400 for non-numeric id`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.get("/v1/queues/not-a-number/scorecard") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

class QueueRoutesLinkTest {

    @Test
    fun `GET queues id suggestions returns candidates`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { queueRepo.getSuggestions(1L, 5L) } returns listOf(
            QueueLinkSuggestionDTO(serviceDefinitionId = 11L, serviceName = "payments-api", team = "pagamentos", confidence = 0.66, source = "name"),
        )

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.get("/v1/queues/5/suggestions") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "payments-api")
    }

    @Test
    fun `POST queues id link assigns service for admin`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { queueRepo.linkQueue(1L, 5L, 11L) } returns true

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.post("/v1/queues/5/link") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceDefinitionId":11}""")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        coVerify { queueRepo.linkQueue(1L, 5L, 11L) }
    }

    @Test
    fun `POST queues id link returns 404 when queue or service missing`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { queueRepo.linkQueue(1L, 5L, 99L) } returns false

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.post("/v1/queues/5/link") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceDefinitionId":99}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST queues id link forbidden for non-admin`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.post("/v1/queues/5/link") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.viewer")
            contentType(ContentType.Application.Json)
            setBody("""{"serviceDefinitionId":11}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET queues services returns options`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val serviceDefRepo = mockk<ServiceDefinitionRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { serviceDefRepo.listForTenant(1L) } returns listOf(
            ServiceOptionDTO(serviceDefinitionId = 10L, serviceName = "orders-api", team = "plataforma"),
        )

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, serviceDefRepo)
        }

        val response = client.get("/v1/queues/services") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "orders-api")
    }
}

class QueueRoutesThresholdsTest {

    @Test
    fun `GET queues id thresholds returns thresholds`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { queueRepo.getQueueThresholds(1L, 1L) } returns SAMPLE_THRESHOLDS

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.get("/v1/queues/1/thresholds") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "120")
        assertContains(body, "150")
        assertContains(body, "80")
    }

    @Test
    fun `GET queues id thresholds returns 404 when queue not in MONITORING`() = testApplication {
        val queueRepo = mockk<QueueRepository>()
        val authenticator = testRequestAuthenticator()
        coEvery { queueRepo.getQueueThresholds(1L, 2L) } returns null

        application {
            installTestSecurity(authenticator)
            queueRoutes(queueRepo, mockk())
        }

        val response = client.get("/v1/queues/2/thresholds") {
            header("X-Dev-Auth", "true")
            header("X-Dev-Tenant-Id", "1")
            header("X-Dev-Roles", "titlis.admin")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
