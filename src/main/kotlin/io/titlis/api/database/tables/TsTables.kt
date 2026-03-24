package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object ResourceMetrics : Table("titlis_ts.resource_metrics") {
    val resourceMetricId     = long("resource_metric_id").autoIncrement()
    val workloadId           = long("workload_id")  // ref lógica sem FK (append-only)
    val tenantId             = long("tenant_id").nullable()
    val containerName        = varchar("container_name", 255).nullable()
    val metricSource         = varchar("metric_source", 50).default("datadog")
    val cpuAvgMillicores     = decimal("cpu_avg_millicores", 10, 3).nullable()
    val cpuP95Millicores     = decimal("cpu_p95_millicores", 10, 3).nullable()
    val memAvgMib            = decimal("mem_avg_mib", 10, 3).nullable()
    val memP95Mib            = decimal("mem_p95_mib", 10, 3).nullable()
    val suggestedCpuRequest  = varchar("suggested_cpu_request", 50).nullable()
    val suggestedCpuLimit    = varchar("suggested_cpu_limit", 50).nullable()
    val suggestedMemRequest  = varchar("suggested_mem_request", 50).nullable()
    val suggestedMemLimit    = varchar("suggested_mem_limit", 50).nullable()
    val sampleWindow         = varchar("sample_window", 20).nullable()
    val collectedAt          = timestampWithTimeZone("collected_at")
    override val primaryKey = PrimaryKey(resourceMetricId)
}

object ScorecardScores : Table("titlis_ts.scorecard_scores") {
    val scorecardScoreId = long("scorecard_score_id").autoIncrement()
    val workloadId       = long("workload_id")  // ref lógica sem FK (append-only)
    val tenantId         = long("tenant_id").nullable()
    val overallScore     = decimal("overall_score", 5, 2)
    val resilienceScore  = decimal("resilience_score", 5, 2).nullable()
    val securityScore    = decimal("security_score", 5, 2).nullable()
    val costScore        = decimal("cost_score", 5, 2).nullable()
    val performanceScore = decimal("performance_score", 5, 2).nullable()
    val operationalScore = decimal("operational_score", 5, 2).nullable()
    val complianceScore  = decimal("compliance_score", 5, 2).nullable()
    val complianceStatus = varchar("compliance_status", 50)
    val passedRules      = integer("passed_rules").nullable()
    val failedRules      = integer("failed_rules").nullable()
    val recordedAt       = timestampWithTimeZone("recorded_at")
    override val primaryKey = PrimaryKey(scorecardScoreId)
}