package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.*
import io.titlis.api.domain.NotificationSentEvent
import io.titlis.api.domain.ScorecardEvaluatedEvent
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

class ScorecardRepository {

    // Resolve k8s_uid (string UUID do evento) para o workload_id interno (Long BIGINT IDENTITY).
    // O workload deve existir antes de receber scorecard — inserido pelo WorkloadRepository
    // ou pela primeira chamada ao endpoint de upsert de workload.
    private fun resolveWorkloadId(k8sUid: String): Long =
        Workloads
            .select(Workloads.workloadId)
            .where { Workloads.k8sUid eq k8sUid }
            .singleOrNull()?.get(Workloads.workloadId)
            ?: error("Workload não encontrado para k8s_uid=$k8sUid")

    suspend fun upsertScorecard(event: ScorecardEvaluatedEvent) = dbQuery {
        val workloadId = resolveWorkloadId(event.workloadId)
        val now = Instant.now().toKotlinInstant()

        // SCD Type 4 — a aplicação gerencia o histórico antes do upsert (sem triggers DML).
        // Se já existe scorecard e a versão mudou, arquiva o estado anterior em app_scorecard_history.
        val existing = AppScorecards
            .select(AppScorecards.appScorecardId, AppScorecards.version,
                    AppScorecards.overallScore, AppScorecards.complianceStatus,
                    AppScorecards.totalRules, AppScorecards.passedRules,
                    AppScorecards.failedRules, AppScorecards.criticalFailures,
                    AppScorecards.errorCount, AppScorecards.warningCount,
                    AppScorecards.evaluatedAt, AppScorecards.k8sEventType)
            .where { AppScorecards.workloadId eq workloadId }
            .singleOrNull()

        if (existing != null && existing[AppScorecards.version] != event.scorecardVersion) {
            val prevScorecardId = existing[AppScorecards.appScorecardId]
            val pillarSnap = PillarScores
                .select(PillarScores.pillar, PillarScores.pillarScore,
                        PillarScores.passedChecks, PillarScores.failedChecks,
                        PillarScores.weightedScore)
                .where { PillarScores.appScorecardId eq prevScorecardId }
                .map { r ->
                    mapOf("pillar" to r[PillarScores.pillar],
                          "score" to r[PillarScores.pillarScore],
                          "passed_checks" to r[PillarScores.passedChecks],
                          "failed_checks" to r[PillarScores.failedChecks],
                          "weighted_score" to r[PillarScores.weightedScore])
                }

            AppScorecardHistory.insert {
                it[AppScorecardHistory.workloadId]        = workloadId
                it[AppScorecardHistory.scorecardVersion]  = existing[AppScorecards.version]
                it[AppScorecardHistory.overallScore]      = existing[AppScorecards.overallScore]
                it[AppScorecardHistory.complianceStatus]  = existing[AppScorecards.complianceStatus]
                it[AppScorecardHistory.totalRules]        = existing[AppScorecards.totalRules]
                it[AppScorecardHistory.passedRules]       = existing[AppScorecards.passedRules]
                it[AppScorecardHistory.failedRules]       = existing[AppScorecards.failedRules]
                it[AppScorecardHistory.criticalFailures]  = existing[AppScorecards.criticalFailures]
                it[AppScorecardHistory.errorCount]        = existing[AppScorecards.errorCount]
                it[AppScorecardHistory.warningCount]      = existing[AppScorecards.warningCount]
                it[AppScorecardHistory.pillarScores]      = org.jetbrains.exposed.sql.json.json(pillarSnap)
                it[AppScorecardHistory.validationResults] = org.jetbrains.exposed.sql.json.json(emptyList<Any>())
                it[AppScorecardHistory.evaluatedAt]       = existing[AppScorecards.evaluatedAt]
                it[AppScorecardHistory.k8sEventType]      = existing[AppScorecards.k8sEventType]
                it[AppScorecardHistory.createdAt]         = now
            }
        }

        // Upsert scorecard atual (UNIQUE workload_id — SCD Type 4 "current table")
        AppScorecards.upsert(AppScorecards.workloadId) {
            it[AppScorecards.workloadId] = workloadId
            it[version]          = event.scorecardVersion
            it[overallScore]     = event.overallScore.toBigDecimal()
            it[complianceStatus] = event.complianceStatus
            it[totalRules]       = event.totalRules
            it[passedRules]      = event.passedRules
            it[failedRules]      = event.failedRules
            it[criticalFailures] = event.criticalFailures
            it[errorCount]       = event.errorCount
            it[warningCount]     = event.warningCount
            it[evaluatedAt]      = Instant.parse(event.evaluatedAt).toKotlinInstant()
            it[k8sEventType]     = event.k8sEventType
            it[updatedAt]        = now
        }

        // Obter app_scorecard_id recém-upsertado
        val appScorecardId = AppScorecards
            .select(AppScorecards.appScorecardId)
            .where { AppScorecards.workloadId eq workloadId }
            .single()[AppScorecards.appScorecardId]

        // Substituir pillar_scores (delete + insert — scorecard atual tem CASCADE)
        PillarScores.deleteWhere { PillarScores.appScorecardId eq appScorecardId }
        event.pillarScores.forEach { ps ->
            PillarScores.insert {
                it[PillarScores.appScorecardId] = appScorecardId
                it[pillar]        = ps.pillar
                it[pillarScore]   = ps.score.toBigDecimal()
                it[passedChecks]  = ps.passedChecks
                it[failedChecks]  = ps.failedChecks
                it[weightedScore] = ps.weightedScore?.toBigDecimal()
                it[createdAt]     = now
                it[updatedAt]     = now
            }
        }

        // Inserir na série temporal (append-only)
        ScorecardScores.insert {
            it[ScorecardScores.workloadId]    = workloadId
            it[overallScore]     = event.overallScore.toBigDecimal()
            it[complianceStatus] = event.complianceStatus
            it[passedRules]      = event.passedRules
            it[failedRules]      = event.failedRules
            it[recordedAt]       = now
            event.pillarScores.forEach { ps ->
                when (ps.pillar) {
                    "RESILIENCE"   -> it[resilienceScore]  = ps.score.toBigDecimal()
                    "SECURITY"     -> it[securityScore]    = ps.score.toBigDecimal()
                    "COST"         -> it[costScore]        = ps.score.toBigDecimal()
                    "PERFORMANCE"  -> it[performanceScore] = ps.score.toBigDecimal()
                    "OPERATIONAL"  -> it[operationalScore] = ps.score.toBigDecimal()
                    "COMPLIANCE"   -> it[complianceScore]  = ps.score.toBigDecimal()
                }
            }
        }
    }

    suspend fun insertNotificationLog(event: NotificationSentEvent) = dbQuery {
        NotificationLog.insert {
            it[workloadId]       = event.workloadId?.let { id -> resolveWorkloadId(id) }
            it[notificationType] = event.notificationType
            it[severity]         = event.severity
            it[channel]          = event.channel
            it[title]            = event.title
            it[messagePreview]   = event.messagePreview?.take(500)
            it[success]          = event.success
            it[errorMessage]     = event.errorMessage
            it[createdAt]        = Instant.now().toKotlinInstant()
        }
    }

    suspend fun getDashboard(clusterName: String? = null): List<Map<String, Any?>> = dbQuery {
        val query = (Workloads innerJoin Namespaces innerJoin Clusters)
            .leftJoin(AppScorecards, { Workloads.workloadId }, { AppScorecards.workloadId })
            .leftJoin(AppRemediations, { Workloads.workloadId }, { AppRemediations.workloadId })
            .select(
                Workloads.workloadId, Workloads.k8sUid,
                Clusters.clusterName, Clusters.environment,
                Namespaces.namespaceName,
                Workloads.workloadName, Workloads.workloadKind,
                Workloads.serviceTier, Workloads.ownerTeam,
                AppScorecards.overallScore, AppScorecards.complianceStatus,
                AppScorecards.passedRules, AppScorecards.failedRules,
                AppScorecards.criticalFailures, AppScorecards.version,
                AppScorecards.evaluatedAt,
                AppRemediations.appRemediationStatus,
                AppRemediations.githubPrUrl, AppRemediations.githubPrNumber,
            )
            .where {
                (Workloads.isActive eq true) and (Namespaces.isExcluded eq false)
            }

        if (clusterName != null) {
            query.andWhere { Clusters.clusterName eq clusterName }
        }

        query.map { row ->
            mapOf(
                "workload_id"        to row[Workloads.k8sUid],
                "cluster"            to row[Clusters.clusterName],
                "environment"        to row[Clusters.environment],
                "namespace"          to row[Namespaces.namespaceName],
                "workload"           to row[Workloads.workloadName],
                "overall_score"      to row[AppScorecards.overallScore],
                "compliance_status"  to row[AppScorecards.complianceStatus],
                "remediation_status" to row[AppRemediations.appRemediationStatus],
                "github_pr_url"      to row[AppRemediations.githubPrUrl],
            )
        }
    }
}