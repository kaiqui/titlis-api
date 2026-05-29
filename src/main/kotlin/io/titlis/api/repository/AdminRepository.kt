package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.AppRemediations
import io.titlis.api.database.tables.AppScorecards
import io.titlis.api.database.tables.Clusters
import io.titlis.api.database.tables.Namespaces
import io.titlis.api.database.tables.PillarScores
import io.titlis.api.database.tables.PlatformUsers
import io.titlis.api.database.tables.TenantAiConfigs
import io.titlis.api.database.tables.Workloads
import io.titlis.api.domain.AdminAiStats
import io.titlis.api.domain.AdminComplianceStats
import io.titlis.api.domain.AdminOverviewResponse
import io.titlis.api.domain.AdminPillarScore
import io.titlis.api.domain.AdminRemediationStats
import io.titlis.api.domain.AdminUserItem
import io.titlis.api.domain.AdminUserStats
import io.titlis.api.domain.AdminUsersResponse
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.avg
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AdminRepository {

    suspend fun getOverview(tenantId: Long): AdminOverviewResponse = dbQuery {
        val thirtyDaysAgo = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30)

        // Q1 — Compliance: captures cada aggregate expression para reuso seguro no row access
        val scorecardCountExpr = AppScorecards.appScorecardId.count()
        val totalScorecards = AppScorecards
            .select(scorecardCountExpr)
            .where { AppScorecards.tenantId eq tenantId }
            .single()[scorecardCountExpr]

        val avgScoreExpr = AppScorecards.overallScore.avg()
        val criticalSumExpr = AppScorecards.criticalFailures.sum()
        val scorecardAggs = AppScorecards
            .select(avgScoreExpr, criticalSumExpr)
            .where { AppScorecards.tenantId eq tenantId }
            .single()
        val avgScore = scorecardAggs[avgScoreExpr]?.toDouble() ?: 0.0
        val totalCriticalFailures = scorecardAggs[criticalSumExpr]?.toLong() ?: 0L

        val compliantCountExpr = AppScorecards.appScorecardId.count()
        val compliantCount = AppScorecards
            .select(compliantCountExpr)
            .where { (AppScorecards.tenantId eq tenantId) and (AppScorecards.complianceStatus eq "COMPLIANT") }
            .single()[compliantCountExpr]

        val criticalWorkloadsCountExpr = AppScorecards.appScorecardId.count()
        val criticalWorkloads = AppScorecards
            .select(criticalWorkloadsCountExpr)
            .where { (AppScorecards.tenantId eq tenantId) and (AppScorecards.overallScore less 50.toBigDecimal()) }
            .single()[criticalWorkloadsCountExpr]

        val workloadCountExpr = Workloads.workloadId.count()
        val totalWorkloads = Workloads
            .innerJoin(Namespaces)
            .innerJoin(Clusters)
            .select(workloadCountExpr)
            .where { (Clusters.tenantId eq tenantId) and (Workloads.isActive eq true) }
            .single()[workloadCountExpr]

        // Q2 — Remediações por status
        val remediationCountExpr = AppRemediations.appRemediationId.count()
        val remediationMap = AppRemediations
            .select(AppRemediations.appRemediationStatus, remediationCountExpr)
            .where { AppRemediations.tenantId eq tenantId }
            .groupBy(AppRemediations.appRemediationStatus)
            .associate { it[AppRemediations.appRemediationStatus] to it[remediationCountExpr] }

        val merged = remediationMap["PR_MERGED"] ?: 0L
        val inProgress = (remediationMap["PR_OPEN"] ?: 0L) +
            (remediationMap["IN_PROGRESS"] ?: 0L) +
            (remediationMap["PENDING"] ?: 0L)
        val failed = remediationMap["FAILED"] ?: 0L
        val totalAutomated = merged + inProgress + failed
        val successRate = if (merged + failed > 0) merged.toDouble() / (merged + failed).toDouble() * 100.0 else 0.0

        // Q3 — Média por pilar
        val pillarAvgExpr = PillarScores.pillarScore.avg()
        val pillars = PillarScores
            .join(AppScorecards, JoinType.INNER, PillarScores.appScorecardId, AppScorecards.appScorecardId)
            .select(PillarScores.pillar, pillarAvgExpr)
            .where { AppScorecards.tenantId eq tenantId }
            .groupBy(PillarScores.pillar)
            .map { AdminPillarScore(it[PillarScores.pillar], it[pillarAvgExpr]?.toDouble() ?: 0.0) }
            .sortedBy { it.pillar }

        // Q4 — Usuários: query única, filtragem in-Kotlin para evitar comparação de timestamp nullable
        val userRows = PlatformUsers
            .select(PlatformUsers.lastLoginAt, PlatformUsers.platformRole)
            .where {
                (PlatformUsers.tenantId eq tenantId) and
                    (PlatformUsers.isActive eq true) and
                    PlatformUsers.deletedAt.isNull()
            }
            .toList()

        val totalUsers = userRows.size.toLong()
        val activeLastThirty = userRows.count { it[PlatformUsers.lastLoginAt]?.isAfter(thirtyDaysAgo) == true }.toLong()
        val neverAccessed = userRows.count { it[PlatformUsers.lastLoginAt] == null }.toLong()
        val byRole = userRows
            .groupBy { it[PlatformUsers.platformRole] }
            .mapValues { (_, rows) -> rows.size.toLong() }

        // Q5 — Configuração de IA
        val aiRow = TenantAiConfigs
            .select(
                TenantAiConfigs.provider,
                TenantAiConfigs.model,
                TenantAiConfigs.tokensUsedMonth,
                TenantAiConfigs.monthlyTokenBudget,
                TenantAiConfigs.isActive,
            )
            .where { TenantAiConfigs.tenantId eq tenantId }
            .singleOrNull()

        val ai = if (aiRow != null && aiRow[TenantAiConfigs.isActive]) {
            val tokens = aiRow[TenantAiConfigs.tokensUsedMonth]
            val budget = aiRow[TenantAiConfigs.monthlyTokenBudget]
            AdminAiStats(
                isConfigured = true,
                provider = aiRow[TenantAiConfigs.provider].takeIf { it != "pending" },
                model = aiRow[TenantAiConfigs.model].takeIf { it != "pending" },
                tokensUsedMonth = tokens,
                monthlyTokenBudget = budget,
                usagePercent = if (budget != null && budget > 0) tokens.toDouble() / budget.toDouble() * 100.0 else null,
            )
        } else {
            AdminAiStats(isConfigured = false, provider = null, model = null, tokensUsedMonth = 0, monthlyTokenBudget = null, usagePercent = null)
        }

        AdminOverviewResponse(
            compliance = AdminComplianceStats(
                averageScore = avgScore,
                compliancePercent = if (totalScorecards > 0) compliantCount.toDouble() / totalScorecards.toDouble() * 100.0 else 0.0,
                totalWorkloads = totalWorkloads,
                compliantWorkloads = compliantCount,
                criticalWorkloads = criticalWorkloads,
                totalCriticalFailures = totalCriticalFailures,
                workloadsWithoutEvaluation = maxOf(totalWorkloads - totalScorecards, 0L),
            ),
            remediations = AdminRemediationStats(
                totalAutomated = totalAutomated,
                merged = merged,
                inProgress = inProgress,
                failed = failed,
                successRate = successRate,
            ),
            pillars = pillars,
            users = AdminUserStats(
                total = totalUsers,
                activeLastThirtyDays = activeLastThirty,
                neverAccessed = neverAccessed,
                byRole = byRole,
            ),
            ai = ai,
        )
    }

    suspend fun listUsers(tenantId: Long): AdminUsersResponse = dbQuery {
        val users = PlatformUsers
            .selectAll()
            .where {
                (PlatformUsers.tenantId eq tenantId) and
                    (PlatformUsers.isActive eq true) and
                    PlatformUsers.deletedAt.isNull()
            }
            .orderBy(PlatformUsers.lastLoginAt to SortOrder.DESC_NULLS_LAST)
            .map { row ->
                AdminUserItem(
                    id = row[PlatformUsers.platformUserId],
                    email = row[PlatformUsers.email],
                    displayName = row[PlatformUsers.displayName],
                    role = row[PlatformUsers.platformRole],
                    isActive = row[PlatformUsers.isActive],
                    lastLoginAt = row[PlatformUsers.lastLoginAt]?.toString(),
                    createdAt = row[PlatformUsers.createdAt].toString(),
                )
            }
        AdminUsersResponse(users = users)
    }
}
