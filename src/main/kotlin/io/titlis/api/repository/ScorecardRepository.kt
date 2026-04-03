package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.*
import io.titlis.api.domain.NotificationSentEvent
import io.titlis.api.domain.ScorecardEvaluatedEvent
import io.titlis.api.domain.ValidationResultData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ScorecardRepository {

    suspend fun upsertScorecard(event: ScorecardEvaluatedEvent, tenantIdHint: Long? = null) = dbQuery {
        val workloadId = ensureWorkload(event)
        val tenantId = chooseTenantId(
            trustedTenantId = tenantIdHint,
            derivedTenantId = resolveTenantIdByWorkloadId(workloadId) ?: resolveSingleActiveTenantIdOrNull(),
        )
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val normalizedComplianceStatus = event.complianceStatus.uppercase()

        val existing = AppScorecards
            .select(
                AppScorecards.appScorecardId,
                AppScorecards.version,
                AppScorecards.overallScore,
                AppScorecards.complianceStatus,
                AppScorecards.totalRules,
                AppScorecards.passedRules,
                AppScorecards.failedRules,
                AppScorecards.criticalFailures,
                AppScorecards.errorCount,
                AppScorecards.warningCount,
                AppScorecards.evaluatedAt,
                AppScorecards.k8sEventType,
            )
            .where { AppScorecards.workloadId eq workloadId }
            .singleOrNull()

        if (
            existing != null &&
            existing[AppScorecards.version] != event.scorecardVersion
        ) {
            val prevScorecardId = existing[AppScorecards.appScorecardId]
            val pillarRows = PillarScores
                .select(
                    PillarScores.pillar,
                    PillarScores.pillarScore,
                    PillarScores.passedChecks,
                    PillarScores.failedChecks,
                    PillarScores.weightedScore,
                )
                .where { PillarScores.appScorecardId eq prevScorecardId }
                .toList()
            val validationRows = ValidationResults
                .innerJoin(ValidationRules)
                .select(
                    ValidationRules.ruleId,
                    ValidationRules.pillar,
                    ValidationRules.ruleSeverity,
                    ValidationResults.rulePassed,
                    ValidationResults.resultMessage,
                    ValidationResults.actualValue,
                )
                .where { ValidationResults.appScorecardId eq prevScorecardId }
                .toList()

            AppScorecardHistory.insert {
                it[AppScorecardHistory.workloadId] = workloadId
                it[AppScorecardHistory.tenantId] = tenantId
                it[AppScorecardHistory.scorecardVersion] =
                    existing[AppScorecards.version]
                it[AppScorecardHistory.overallScore] =
                    existing[AppScorecards.overallScore]
                it[AppScorecardHistory.complianceStatus] =
                    existing[AppScorecards.complianceStatus]
                it[AppScorecardHistory.totalRules] =
                    existing[AppScorecards.totalRules]
                it[AppScorecardHistory.passedRules] =
                    existing[AppScorecards.passedRules]
                it[AppScorecardHistory.failedRules] =
                    existing[AppScorecards.failedRules]
                it[AppScorecardHistory.criticalFailures] =
                    existing[AppScorecards.criticalFailures]
                it[AppScorecardHistory.errorCount] =
                    existing[AppScorecards.errorCount]
                it[AppScorecardHistory.warningCount] =
                    existing[AppScorecards.warningCount]
                it[AppScorecardHistory.pillarScores] =
                    buildPillarSnapshotJson(pillarRows).toString()
                it[AppScorecardHistory.validationResults] =
                    buildValidationSnapshotJson(validationRows).toString()
                it[AppScorecardHistory.evaluatedAt] =
                    existing[AppScorecards.evaluatedAt]
                it[AppScorecardHistory.k8sEventType] =
                    existing[AppScorecards.k8sEventType]
                it[AppScorecardHistory.createdAt] = now
            }

            pillarRows.forEach { row ->
                PillarScoreHistory.insert {
                    it[PillarScoreHistory.workloadId] = workloadId
                    it[PillarScoreHistory.scorecardVersion] =
                        existing[AppScorecards.version]
                    it[PillarScoreHistory.pillar] = row[PillarScores.pillar]
                    it[PillarScoreHistory.pillarScore] =
                        row[PillarScores.pillarScore]
                    it[PillarScoreHistory.passedChecks] =
                        row[PillarScores.passedChecks]
                    it[PillarScoreHistory.failedChecks] =
                        row[PillarScores.failedChecks]
                    it[PillarScoreHistory.weightedScore] =
                        row[PillarScores.weightedScore]
                    it[PillarScoreHistory.evaluatedAt] =
                        existing[AppScorecards.evaluatedAt]
                    it[PillarScoreHistory.createdAt] = now
                }
            }
        }

        AppScorecards.upsert(AppScorecards.workloadId) {
            it[AppScorecards.workloadId] = workloadId
            it[AppScorecards.tenantId] = tenantId
            it[AppScorecards.version] = event.scorecardVersion
            it[AppScorecards.overallScore] = event.overallScore.toBigDecimal()
            it[AppScorecards.complianceStatus] = normalizedComplianceStatus
            it[AppScorecards.totalRules] = event.totalRules
            it[AppScorecards.passedRules] = event.passedRules
            it[AppScorecards.failedRules] = event.failedRules
            it[AppScorecards.criticalFailures] = event.criticalFailures
            it[AppScorecards.errorCount] = event.errorCount
            it[AppScorecards.warningCount] = event.warningCount
            it[AppScorecards.evaluatedAt] =
                OffsetDateTime.parse(event.evaluatedAt)
            it[AppScorecards.k8sEventType] = event.k8sEventType
            it[AppScorecards.rawMetadata] = event.rawMetadata?.toString()
            it[AppScorecards.updatedAt] = now
        }

        val appScorecardId = AppScorecards
            .select(AppScorecards.appScorecardId)
            .where { AppScorecards.workloadId eq workloadId }
            .single()[AppScorecards.appScorecardId]

        PillarScores.deleteWhere { PillarScores.appScorecardId eq appScorecardId }
        event.pillarScores.forEach { ps ->
            PillarScores.insert {
                it[PillarScores.appScorecardId] = appScorecardId
                it[PillarScores.pillar] = ps.pillar.uppercase()
                it[PillarScores.pillarScore] = ps.score.toBigDecimal()
                it[PillarScores.passedChecks] = ps.passedChecks
                it[PillarScores.failedChecks] = ps.failedChecks
                it[PillarScores.weightedScore] = ps.weightedScore?.toBigDecimal()
                it[PillarScores.createdAt] = now
                it[PillarScores.updatedAt] = now
            }
        }

        upsertValidationRules(event.validationResults, now)
        ValidationResults.deleteWhere {
            ValidationResults.appScorecardId eq appScorecardId
        }
        event.validationResults.forEach { validation ->
            val validationRuleId = resolveValidationRuleId(validation.ruleId)
            ValidationResults.insert {
                it[ValidationResults.appScorecardId] = appScorecardId
                it[ValidationResults.validationRuleId] = validationRuleId
                it[ValidationResults.rulePassed] = validation.passed
                it[ValidationResults.resultMessage] = validation.message
                it[ValidationResults.actualValue] = validation.actualValue
                it[ValidationResults.evaluatedAt] =
                    OffsetDateTime.parse(event.evaluatedAt)
                it[ValidationResults.createdAt] = now
            }
        }

        ScorecardScores.insert {
            it[ScorecardScores.workloadId] = workloadId
            it[ScorecardScores.tenantId] = tenantId
            it[ScorecardScores.overallScore] = event.overallScore.toBigDecimal()
            it[ScorecardScores.complianceStatus] = normalizedComplianceStatus
            it[ScorecardScores.passedRules] = event.passedRules
            it[ScorecardScores.failedRules] = event.failedRules
            it[ScorecardScores.recordedAt] = now
            event.pillarScores.forEach { ps ->
                when (ps.pillar.uppercase()) {
                    "RESILIENCE"   -> it[ScorecardScores.resilienceScore] = ps.score.toBigDecimal()
                    "SECURITY"     -> it[ScorecardScores.securityScore] = ps.score.toBigDecimal()
                    "COST"         -> it[ScorecardScores.costScore] = ps.score.toBigDecimal()
                    "PERFORMANCE"  -> it[ScorecardScores.performanceScore] = ps.score.toBigDecimal()
                    "OPERATIONAL"  -> it[ScorecardScores.operationalScore] = ps.score.toBigDecimal()
                    "COMPLIANCE"   -> it[ScorecardScores.complianceScore] = ps.score.toBigDecimal()
                }
            }
        }
    }

    suspend fun insertNotificationLog(event: NotificationSentEvent, tenantIdHint: Long? = null) = dbQuery {
        val resolvedWorkloadId = event.workloadId?.let { id -> resolveWorkloadId(id) }
        val resolvedNamespaceId =
            resolvedWorkloadId?.let { workloadId -> resolveNamespaceIdByWorkload(workloadId) }
        val resolvedTenantId =
            chooseTenantId(
                trustedTenantId = tenantIdHint,
                derivedTenantId = resolvedWorkloadId?.let { workloadId -> resolveTenantIdByWorkloadId(workloadId) }
                    ?: resolveSingleActiveTenantIdOrNull(),
            )

        NotificationLog.insert {
            it[NotificationLog.workloadId] = resolvedWorkloadId
            it[NotificationLog.namespaceId] = resolvedNamespaceId
            it[NotificationLog.tenantId] = resolvedTenantId
            it[NotificationLog.notificationType] = event.notificationType
            it[NotificationLog.notificationSeverity] = event.severity.uppercase()
            it[NotificationLog.channel] = event.channel
            it[NotificationLog.notificationTitle] = event.title?.take(500)
            it[NotificationLog.messagePreview] = event.messagePreview?.take(500)
            it[NotificationLog.success] = event.success
            it[NotificationLog.errorMessage] = event.errorMessage
            it[NotificationLog.sentAt] = OffsetDateTime.now(ZoneOffset.UTC)
            it[NotificationLog.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    suspend fun getByWorkloadId(k8sUid: String, tenantId: Long): Map<String, Any?>? = dbQuery {
        val workloadId = tenantScopedWorkloadRow(k8sUid, tenantId)
            ?.get(Workloads.workloadId)
            ?: return@dbQuery null

        val scorecardRow = (Workloads innerJoin Namespaces innerJoin Clusters)
            .leftJoin(AppScorecards, { Workloads.workloadId }, { AppScorecards.workloadId })
            .select(
                Workloads.k8sUid,
                Workloads.workloadName,
                Workloads.workloadKind,
                Namespaces.namespaceName,
                Clusters.clusterName,
                Clusters.environment,
                AppScorecards.appScorecardId,
                AppScorecards.overallScore,
                AppScorecards.complianceStatus,
                AppScorecards.version,
                AppScorecards.totalRules,
                AppScorecards.passedRules,
                AppScorecards.failedRules,
                AppScorecards.criticalFailures,
                AppScorecards.errorCount,
                AppScorecards.warningCount,
                AppScorecards.evaluatedAt,
            )
            .where {
                (Workloads.workloadId eq workloadId) and
                    (Clusters.tenantId eq tenantId)
            }
            .singleOrNull()
            ?: return@dbQuery null

        val appScorecardId = scorecardRow[AppScorecards.appScorecardId]
        val pillarScores = appScorecardId?.let { scorecardId ->
            PillarScores
                .select(
                    PillarScores.pillar,
                    PillarScores.pillarScore,
                    PillarScores.passedChecks,
                    PillarScores.failedChecks,
                    PillarScores.weightedScore,
                )
                .where { PillarScores.appScorecardId eq scorecardId }
                .map { row ->
                    mapOf(
                        "pillar" to row[PillarScores.pillar],
                        "score" to row[PillarScores.pillarScore],
                        "passed_checks" to row[PillarScores.passedChecks],
                        "failed_checks" to row[PillarScores.failedChecks],
                        "weighted_score" to row[PillarScores.weightedScore],
                    )
                }
        } ?: emptyList()

        val validationResults = appScorecardId?.let { scorecardId ->
            (ValidationResults innerJoin ValidationRules)
                .select(
                    ValidationRules.ruleId,
                    ValidationRules.ruleName,
                    ValidationRules.pillar,
                    ValidationRules.ruleSeverity,
                    ValidationRules.ruleType,
                    ValidationRules.weight,
                    ValidationRules.isRemediable,
                    ValidationRules.remediationCategory,
                    ValidationResults.rulePassed,
                    ValidationResults.resultMessage,
                    ValidationResults.actualValue,
                    ValidationResults.evaluatedAt,
                )
                .where { ValidationResults.appScorecardId eq scorecardId }
                .orderBy(ValidationResults.rulePassed, SortOrder.ASC)
                .orderBy(ValidationRules.ruleSeverity, SortOrder.DESC)
                .map { row ->
                    mapOf(
                        "rule_id" to row[ValidationRules.ruleId],
                        "rule_name" to row[ValidationRules.ruleName],
                        "pillar" to row[ValidationRules.pillar],
                        "severity" to row[ValidationRules.ruleSeverity],
                        "rule_type" to row[ValidationRules.ruleType],
                        "weight" to row[ValidationRules.weight],
                        "passed" to row[ValidationResults.rulePassed],
                        "message" to row[ValidationResults.resultMessage],
                        "actual_value" to row[ValidationResults.actualValue],
                        "is_remediable" to row[ValidationRules.isRemediable],
                        "remediation_category" to row[ValidationRules.remediationCategory],
                        "evaluated_at" to row[ValidationResults.evaluatedAt].toString(),
                    )
                }
        } ?: emptyList()

        mapOf(
            "workload_id" to scorecardRow[Workloads.k8sUid],
            "workload" to scorecardRow[Workloads.workloadName],
            "workload_kind" to scorecardRow[Workloads.workloadKind],
            "namespace" to scorecardRow[Namespaces.namespaceName],
            "cluster" to scorecardRow[Clusters.clusterName],
            "environment" to scorecardRow[Clusters.environment],
            "overall_score" to scorecardRow[AppScorecards.overallScore],
            "compliance_status" to scorecardRow[AppScorecards.complianceStatus],
            "version" to scorecardRow[AppScorecards.version],
            "total_rules" to scorecardRow[AppScorecards.totalRules],
            "passed_rules" to scorecardRow[AppScorecards.passedRules],
            "failed_rules" to scorecardRow[AppScorecards.failedRules],
            "critical_failures" to scorecardRow[AppScorecards.criticalFailures],
            "error_count" to scorecardRow[AppScorecards.errorCount],
            "warning_count" to scorecardRow[AppScorecards.warningCount],
            "evaluated_at" to scorecardRow[AppScorecards.evaluatedAt]?.toString(),
            "pillar_scores" to pillarScores,
            "validation_results" to validationResults,
        )
    }

    private fun resolveWorkloadId(k8sUid: String): Long =
        Workloads
            .select(Workloads.workloadId)
            .where { Workloads.k8sUid eq k8sUid }
            .singleOrNull()
            ?.get(Workloads.workloadId)
            ?: error("Workload não encontrado para k8s_uid=$k8sUid")

    private fun resolveNamespaceIdByWorkload(workloadIdValue: Long): Long? =
        Workloads
            .select(Workloads.namespaceId)
            .where { Workloads.workloadId eq workloadIdValue }
            .singleOrNull()
            ?.get(Workloads.namespaceId)

    private fun ensureWorkload(event: ScorecardEvaluatedEvent): Long {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val clusterId = ensureCluster(event.cluster, event.environment, now)
        val namespaceId = ensureNamespace(clusterId, event.namespace, now)

        Workloads.upsert(Workloads.namespaceId, Workloads.workloadName, Workloads.workloadKind) {
            it[Workloads.namespaceId] = namespaceId
            it[Workloads.workloadName] = event.workload
            it[Workloads.workloadKind] = event.workloadKind
            it[Workloads.k8sUid] = event.workloadId
            it[Workloads.resourceVersion] = event.resourceVersion
            it[Workloads.ddGitRepositoryUrl] = event.ddGitRepositoryUrl
            it[Workloads.labels] = event.labels?.toString()
            it[Workloads.annotations] = event.annotations?.toString()
            it[Workloads.isActive] = true
            it[Workloads.updatedAt] = now
        }

        return resolveWorkloadId(event.workloadId)
    }

    private fun ensureCluster(
        clusterNameValue: String,
        environmentValue: String,
        now: OffsetDateTime,
    ): Long {
        val tenantId = resolveSingleActiveTenantIdOrNull()
        Clusters.upsert(Clusters.clusterName) {
            it[Clusters.clusterName] = clusterNameValue
            it[Clusters.tenantId] = tenantId
            it[Clusters.environment] = environmentValue
            it[Clusters.isActive] = true
            it[Clusters.updatedAt] = now
        }

        return Clusters
            .select(Clusters.clusterId)
            .where { Clusters.clusterName eq clusterNameValue }
            .single()[Clusters.clusterId]
    }

    private fun ensureNamespace(
        clusterIdValue: Long,
        namespaceNameValue: String,
        now: OffsetDateTime,
    ): Long {
        Namespaces.upsert(Namespaces.clusterId, Namespaces.namespaceName) {
            it[Namespaces.clusterId] = clusterIdValue
            it[Namespaces.namespaceName] = namespaceNameValue
            it[Namespaces.updatedAt] = now
        }

        return Namespaces
            .select(Namespaces.namespaceId)
            .where {
                (Namespaces.clusterId eq clusterIdValue) and
                    (Namespaces.namespaceName eq namespaceNameValue)
            }
            .single()[Namespaces.namespaceId]
    }

    private fun upsertValidationRules(
        validations: List<ValidationResultData>,
        now: OffsetDateTime,
    ) {
        validations
            .distinctBy { it.ruleId }
            .forEach { validation ->
                ValidationRules.upsert(ValidationRules.ruleId) {
                    it[ValidationRules.ruleId] = validation.ruleId
                    it[ValidationRules.pillar] = validation.pillar.uppercase()
                    it[ValidationRules.ruleSeverity] = validation.severity.uppercase()
                    it[ValidationRules.ruleType] = validation.ruleType.uppercase()
                    it[ValidationRules.weight] = validation.weight.toBigDecimal()
                    it[ValidationRules.ruleName] = validation.ruleName
                    it[ValidationRules.description] = validation.message
                    it[ValidationRules.isRemediable] = validation.isRemediable
                    it[ValidationRules.remediationCategory] =
                        validation.remediationCategory
                    it[ValidationRules.isActive] = true
                    it[ValidationRules.updatedAt] = now
                }
            }
    }

    private fun buildPillarSnapshotJson(rows: List<ResultRow>): JsonArray =
        buildJsonArray {
            rows.forEach { row ->
                add(
                    buildJsonObject {
                        put("pillar", JsonPrimitive(row[PillarScores.pillar]))
                        put("score", JsonPrimitive(row[PillarScores.pillarScore]))
                        put(
                            "passed_checks",
                            JsonPrimitive(row[PillarScores.passedChecks]),
                        )
                        put(
                            "failed_checks",
                            JsonPrimitive(row[PillarScores.failedChecks]),
                        )
                        row[PillarScores.weightedScore]?.let {
                            put("weighted_score", JsonPrimitive(it))
                        }
                    },
                )
            }
        }

    private fun buildValidationSnapshotJson(rows: List<ResultRow>): JsonArray =
        buildJsonArray {
            rows.forEach { row ->
                add(
                    buildJsonObject {
                        put("rule_id", JsonPrimitive(row[ValidationRules.ruleId]))
                        put("pillar", JsonPrimitive(row[ValidationRules.pillar]))
                        put("severity", JsonPrimitive(row[ValidationRules.ruleSeverity]))
                        put("passed", JsonPrimitive(row[ValidationResults.rulePassed]))
                        row[ValidationResults.resultMessage]?.let {
                            put("message", JsonPrimitive(it))
                        }
                        row[ValidationResults.actualValue]?.let {
                            put("actual_value", JsonPrimitive(it))
                        }
                    },
                )
            }
        }

    private fun resolveValidationRuleId(ruleIdValue: String): Long =
        ValidationRules
            .select(ValidationRules.validationRuleId)
            .where { ValidationRules.ruleId eq ruleIdValue }
            .single()[ValidationRules.validationRuleId]

    suspend fun getDashboard(tenantId: Long, clusterName: String? = null): List<Map<String, Any?>> = dbQuery {
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
                (Workloads.isActive eq true) and
                    (Namespaces.isExcluded eq false) and
                    (Clusters.tenantId eq tenantId)
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
