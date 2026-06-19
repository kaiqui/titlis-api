package io.titlis.api.domain

import kotlinx.serialization.Serializable

@Serializable
data class AdminComplianceStats(
    val averageScore: Double,
    val compliancePercent: Double,
    val totalWorkloads: Long,
    val compliantWorkloads: Long,
    val criticalWorkloads: Long,
    val totalCriticalFailures: Long,
    val workloadsWithoutEvaluation: Long,
)

@Serializable
data class AdminRemediationStats(
    val totalAutomated: Long,
    val merged: Long,
    val inProgress: Long,
    val failed: Long,
    val successRate: Double,
)

@Serializable
data class AdminPillarScore(
    val pillar: String,
    val averageScore: Double,
)

@Serializable
data class AdminUserStats(
    val total: Long,
    val activeLastThirtyDays: Long,
    val neverAccessed: Long,
    val byRole: Map<String, Long>,
)

@Serializable
data class AdminAiStats(
    val isConfigured: Boolean,
    val provider: String?,
    val model: String?,
    val tokensUsedMonth: Int,
    val monthlyTokenBudget: Int?,
    val usagePercent: Double?,
)

@Serializable
data class AdminOverviewResponse(
    val compliance: AdminComplianceStats,
    val remediations: AdminRemediationStats,
    val pillars: List<AdminPillarScore>,
    val users: AdminUserStats,
    val ai: AdminAiStats,
)

@Serializable
data class AdminUserItem(
    val id: Long,
    val email: String,
    val displayName: String?,
    val role: String,
    val isActive: Boolean,
    val lastLoginAt: String?,
    val createdAt: String,
)

@Serializable
data class AdminUsersResponse(
    val users: List<AdminUserItem>,
)

@Serializable
data class UpdateUserRoleRequest(
    val role: String,
)
