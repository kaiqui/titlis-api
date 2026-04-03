package io.titlis.api.udp

import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.repository.MetricsRepository
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.SloRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class EventRouterTest {

    @Test
    fun `tenant_id from envelope is preferred over data payload`() = runTest {
        val scorecardRepo = mockk<ScorecardRepository>(relaxed = true)
        val remediationRepo = mockk<RemediationRepository>(relaxed = true)
        val sloRepo = mockk<SloRepository>(relaxed = true)
        val metricsRepo = mockk<MetricsRepository>(relaxed = true)
        val router = EventRouter(scorecardRepo, remediationRepo, sloRepo, metricsRepo)

        val payload = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,"tenant_id":99,"data":{
              "tenant_id":12,
              "workload_id":"123e4567-e89b-12d3-a456-426614174000",
              "namespace":"production","workload":"api","cluster":"prod",
              "k8s_event_type":"update","overall_score":85.0,
              "compliance_status":"COMPLIANT","total_rules":26,
              "passed_rules":22,"failed_rules":4,"critical_failures":0,
              "error_count":1,"warning_count":3,"scorecard_version":5,
              "pillar_scores":[],"evaluated_at":"2026-03-16T10:00:00Z"
            }}
        """.trimIndent().toByteArray()

        router.route(payload)

        coVerify(exactly = 1) { scorecardRepo.upsertScorecard(any(), 99) }
    }

    @Test
    fun `tenant_id from data payload is used when envelope does not provide it`() = runTest {
        val scorecardRepo = mockk<ScorecardRepository>(relaxed = true)
        val remediationRepo = mockk<RemediationRepository>(relaxed = true)
        val sloRepo = mockk<SloRepository>(relaxed = true)
        val metricsRepo = mockk<MetricsRepository>(relaxed = true)
        val router = EventRouter(scorecardRepo, remediationRepo, sloRepo, metricsRepo)

        val payload = """
            {"v":1,"t":"notification_sent","ts":1710000000000,"data":{
              "tenant_id":42,
              "workload_id":"123e4567-e89b-12d3-a456-426614174000",
              "namespace":"production",
              "notification_type":"scorecard",
              "severity":"WARNING",
              "channel":"alerts",
              "success":true
            }}
        """.trimIndent().toByteArray()

        router.route(payload)

        coVerify(exactly = 1) { scorecardRepo.insertNotificationLog(any(), 42) }
    }

    @Test
    fun `tenant_id hint is null when envelope and payload do not provide it`() = runTest {
        val scorecardRepo = mockk<ScorecardRepository>(relaxed = true)
        val remediationRepo = mockk<RemediationRepository>(relaxed = true)
        val sloRepo = mockk<SloRepository>(relaxed = true)
        val metricsRepo = mockk<MetricsRepository>(relaxed = true)
        val router = EventRouter(scorecardRepo, remediationRepo, sloRepo, metricsRepo)

        val payload = """
            {"v":1,"t":"resource_metrics","ts":1710000000000,"data":{
              "workload_id":"123e4567-e89b-12d3-a456-426614174000",
              "namespace":"production",
              "workload":"api"
            }}
        """.trimIndent().toByteArray()

        router.route(payload)

        coVerify(exactly = 1) { metricsRepo.insertResourceMetrics(any(), null) }
    }
}
