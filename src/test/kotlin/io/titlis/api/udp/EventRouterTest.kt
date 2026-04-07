package io.titlis.api.udp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.titlis.api.repository.ApiKeyRepository
import io.titlis.api.repository.MetricsRepository
import io.titlis.api.repository.RemediationRepository
import io.titlis.api.repository.ScorecardRepository
import io.titlis.api.repository.SloRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class EventRouterTest {

    private fun makeRouter(
        scorecardRepo: ScorecardRepository = mockk(relaxed = true),
        remediationRepo: RemediationRepository = mockk(relaxed = true),
        sloRepo: SloRepository = mockk(relaxed = true),
        metricsRepo: MetricsRepository = mockk(relaxed = true),
        apiKeyRepo: ApiKeyRepository = mockk(relaxed = true),
        scope: CoroutineScope,
    ) = EventRouter(scorecardRepo, remediationRepo, sloRepo, metricsRepo, apiKeyRepo, scope)

    @Test
    fun `event is discarded when envelope has no api_key`() = runTest {
        val metricsRepo = mockk<MetricsRepository>(relaxed = true)
        val router = makeRouter(metricsRepo = metricsRepo, scope = this)

        val payload = """
            {"v":1,"t":"resource_metrics","ts":1710000000000,"data":{
              "workload_id":"123e4567-e89b-12d3-a456-426614174000",
              "namespace":"production","workload":"api"
            }}
        """.trimIndent().toByteArray()

        router.route(payload)

        coVerify(exactly = 0) { metricsRepo.insertResourceMetrics(any(), any()) }
    }

    @Test
    fun `event is discarded when tenant_id present but no api_key`() = runTest {
        val scorecardRepo = mockk<ScorecardRepository>(relaxed = true)
        val router = makeRouter(scorecardRepo = scorecardRepo, scope = this)

        val payload = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,"tenant_id":99,"data":{
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

        coVerify(exactly = 0) { scorecardRepo.upsertScorecard(any(), any()) }
    }

    @Test
    fun `api_key in envelope resolves tenant_id`() = runTest {
        val scorecardRepo = mockk<ScorecardRepository>(relaxed = true)
        val apiKeyRepo = mockk<ApiKeyRepository>(relaxed = true)
        coEvery { apiKeyRepo.resolveByToken("tls_k_validkey1234567890abcdef1234567890ab") } returns 7L
        val router = makeRouter(scorecardRepo = scorecardRepo, apiKeyRepo = apiKeyRepo, scope = this)

        val payload = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,
             "api_key":"tls_k_validkey1234567890abcdef1234567890ab","data":{
              "workload_id":"123e4567-e89b-12d3-a456-426614174000",
              "namespace":"production","workload":"api","cluster":"prod",
              "k8s_event_type":"update","overall_score":90.0,
              "compliance_status":"COMPLIANT","total_rules":26,
              "passed_rules":24,"failed_rules":2,"critical_failures":0,
              "error_count":0,"warning_count":2,"scorecard_version":1,
              "pillar_scores":[],"evaluated_at":"2026-04-01T10:00:00Z"
            }}
        """.trimIndent().toByteArray()

        router.route(payload)

        coVerify(exactly = 1) { scorecardRepo.upsertScorecard(any(), 7L) }
    }

    @Test
    fun `event is discarded when api_key is invalid or revoked`() = runTest {
        val scorecardRepo = mockk<ScorecardRepository>(relaxed = true)
        val apiKeyRepo = mockk<ApiKeyRepository>(relaxed = true)
        coEvery { apiKeyRepo.resolveByToken(any()) } returns null
        val router = makeRouter(scorecardRepo = scorecardRepo, apiKeyRepo = apiKeyRepo, scope = this)

        val payload = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,
             "api_key":"tls_k_revokedkey1234567890abcdef1234567890","data":{
              "workload_id":"123e4567-e89b-12d3-a456-426614174000",
              "namespace":"production","workload":"api","cluster":"prod",
              "k8s_event_type":"update","overall_score":80.0,
              "compliance_status":"COMPLIANT","total_rules":26,
              "passed_rules":20,"failed_rules":6,"critical_failures":0,
              "error_count":0,"warning_count":6,"scorecard_version":1,
              "pillar_scores":[],"evaluated_at":"2026-04-01T10:00:00Z"
            }}
        """.trimIndent().toByteArray()

        router.route(payload)

        coVerify(exactly = 0) { scorecardRepo.upsertScorecard(any(), any()) }
    }

    @Test
    fun `api_key takes priority and tenant_id in envelope is ignored`() = runTest {
        val scorecardRepo = mockk<ScorecardRepository>(relaxed = true)
        val apiKeyRepo = mockk<ApiKeyRepository>(relaxed = true)
        coEvery { apiKeyRepo.resolveByToken("tls_k_validkey1234567890abcdef1234567890ab") } returns 55L
        val router = makeRouter(scorecardRepo = scorecardRepo, apiKeyRepo = apiKeyRepo, scope = this)

        val payload = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,
             "tenant_id":1,"api_key":"tls_k_validkey1234567890abcdef1234567890ab","data":{
              "workload_id":"123e4567-e89b-12d3-a456-426614174000",
              "namespace":"production","workload":"api","cluster":"prod",
              "k8s_event_type":"update","overall_score":75.0,
              "compliance_status":"COMPLIANT","total_rules":26,
              "passed_rules":18,"failed_rules":8,"critical_failures":0,
              "error_count":0,"warning_count":8,"scorecard_version":1,
              "pillar_scores":[],"evaluated_at":"2026-04-01T10:00:00Z"
            }}
        """.trimIndent().toByteArray()

        router.route(payload)

        coVerify(exactly = 1) { scorecardRepo.upsertScorecard(any(), 55L) }
    }

    @Test
    fun `dois tenants podem registrar o mesmo cluster sem conflito`() = runTest {
        val scorecardRepo = mockk<ScorecardRepository>(relaxed = true)
        val apiKeyRepo = mockk<ApiKeyRepository>(relaxed = true)
        coEvery { apiKeyRepo.resolveByToken("tls_k_tenant1key000000000000000000000000000") } returns 1L
        coEvery { apiKeyRepo.resolveByToken("tls_k_tenant2key000000000000000000000000000") } returns 2L
        val router = makeRouter(scorecardRepo = scorecardRepo, apiKeyRepo = apiKeyRepo, scope = this)

        val payloadTenant1 = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,
             "api_key":"tls_k_tenant1key000000000000000000000000000","data":{
              "workload_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "namespace":"default","workload":"my-app","cluster":"shared-cluster",
              "k8s_event_type":"update","overall_score":80.0,
              "compliance_status":"COMPLIANT","total_rules":26,
              "passed_rules":21,"failed_rules":5,"critical_failures":0,
              "error_count":1,"warning_count":4,"scorecard_version":1,
              "pillar_scores":[],"evaluated_at":"2026-04-07T10:00:00Z"
            }}
        """.trimIndent().toByteArray()

        val payloadTenant2 = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,
             "api_key":"tls_k_tenant2key000000000000000000000000000","data":{
              "workload_id":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
              "namespace":"default","workload":"my-app","cluster":"shared-cluster",
              "k8s_event_type":"update","overall_score":70.0,
              "compliance_status":"NON_COMPLIANT","total_rules":26,
              "passed_rules":18,"failed_rules":8,"critical_failures":0,
              "error_count":2,"warning_count":6,"scorecard_version":1,
              "pillar_scores":[],"evaluated_at":"2026-04-07T10:00:00Z"
            }}
        """.trimIndent().toByteArray()

        router.route(payloadTenant1)
        router.route(payloadTenant2)

        coVerify(exactly = 1) { scorecardRepo.upsertScorecard(any(), 1L) }
        coVerify(exactly = 1) { scorecardRepo.upsertScorecard(any(), 2L) }
        coVerify(exactly = 2) { scorecardRepo.upsertScorecard(any(), any()) }
    }

    @Test
    fun `evento de tenant invalido nao contamina dados de outro tenant`() = runTest {
        val scorecardRepo = mockk<ScorecardRepository>(relaxed = true)
        val apiKeyRepo = mockk<ApiKeyRepository>(relaxed = true)
        coEvery { apiKeyRepo.resolveByToken("tls_k_tenant1key000000000000000000000000000") } returns 1L
        coEvery { apiKeyRepo.resolveByToken("tls_k_revokedkey00000000000000000000000000") } returns null
        val router = makeRouter(scorecardRepo = scorecardRepo, apiKeyRepo = apiKeyRepo, scope = this)

        val payloadValid = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,
             "api_key":"tls_k_tenant1key000000000000000000000000000","data":{
              "workload_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "namespace":"default","workload":"my-app","cluster":"shared-cluster",
              "k8s_event_type":"update","overall_score":80.0,
              "compliance_status":"COMPLIANT","total_rules":26,
              "passed_rules":21,"failed_rules":5,"critical_failures":0,
              "error_count":1,"warning_count":4,"scorecard_version":1,
              "pillar_scores":[],"evaluated_at":"2026-04-07T10:00:00Z"
            }}
        """.trimIndent().toByteArray()

        val payloadRevoked = """
            {"v":1,"t":"scorecard_evaluated","ts":1710000000000,
             "api_key":"tls_k_revokedkey00000000000000000000000000","data":{
              "workload_id":"cccccccc-cccc-cccc-cccc-cccccccccccc",
              "namespace":"default","workload":"my-app","cluster":"shared-cluster",
              "k8s_event_type":"update","overall_score":99.0,
              "compliance_status":"COMPLIANT","total_rules":26,
              "passed_rules":26,"failed_rules":0,"critical_failures":0,
              "error_count":0,"warning_count":0,"scorecard_version":1,
              "pillar_scores":[],"evaluated_at":"2026-04-07T10:00:00Z"
            }}
        """.trimIndent().toByteArray()

        router.route(payloadValid)
        router.route(payloadRevoked)

        coVerify(exactly = 1) { scorecardRepo.upsertScorecard(any(), 1L) }
        coVerify(exactly = 1) { scorecardRepo.upsertScorecard(any(), any()) }
    }
}
