package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

// Padrão audit: PKs BIGINT IDENTITY com nome composto, sem FK constraints
// (histórico sobrevive à deleção de workloads/SLOs em titlis_oltp).

object AppScorecardHistory : Table("titlis_audit.app_scorecard_history") {
    val appScorecardHistoryId = long("app_scorecard_history_id").autoIncrement()
    val workloadId            = long("workload_id")   // ref lógica sem FK
    val tenantId              = long("tenant_id").nullable()
    val scorecardVersion      = integer("scorecard_version")
    val overallScore          = decimal("overall_score", 5, 2)
    val complianceStatus      = varchar("compliance_status", 50)
    val totalRules            = integer("total_rules")
    val passedRules           = integer("passed_rules")
    val failedRules           = integer("failed_rules")
    val criticalFailures      = integer("critical_failures")
    val errorCount            = integer("error_count")
    val warningCount          = integer("warning_count")
    val pillarScores          = jsonb("pillar_scores")        // [{pillar, score, passed_checks, failed_checks, weighted_score}]
    val validationResults     = jsonb("validation_results")   // [{rule_ref, pillar, severity, passed, message, actual_value}]
    val evaluatedAt           = timestampWithTimeZone("evaluated_at")
    val k8sEventType          = varchar("k8s_event_type", 50).nullable()
    val createdAt             = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(appScorecardHistoryId)
}

object PillarScoreHistory : Table("titlis_audit.pillar_score_history") {
    val pillarScoreHistoryId = long("pillar_score_history_id").autoIncrement()
    val workloadId           = long("workload_id")    // ref lógica sem FK
    val scorecardVersion     = integer("scorecard_version")
    val pillar               = varchar("pillar", 50)
    val pillarScore          = decimal("pillar_score", 5, 2)
    val passedChecks         = integer("passed_checks")
    val failedChecks         = integer("failed_checks")
    val weightedScore        = decimal("weighted_score", 8, 4).nullable()
    val evaluatedAt          = timestampWithTimeZone("evaluated_at")
    val createdAt            = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(pillarScoreHistoryId)
}

// Registra cada transição da máquina de estados PENDING → IN_PROGRESS → PR_OPEN → PR_MERGED/FAILED
object RemediationHistory : Table("titlis_audit.remediation_history") {
    val remediationHistoryId          = long("remediation_history_id").autoIncrement()
    val workloadId                    = long("workload_id")   // ref lógica sem FK
    val remediationVersion            = integer("remediation_version")
    val appRemediationStatus          = varchar("app_remediation_status", 50)
    val previousAppRemediationStatus  = varchar("previous_app_remediation_status", 50).nullable()
    val issuesSnapshot                = jsonb("issues_snapshot").nullable()
    val createdAt                     = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(remediationHistoryId)
}

object NotificationLog : Table("titlis_audit.notification_log") {
    val notificationLogId = long("notification_log_id").autoIncrement()
    val workloadId        = long("workload_id").nullable()
    val namespaceId       = long("namespace_id").nullable()
    val tenantId          = long("tenant_id").nullable()
    val notificationType  = varchar("notification_type", 50)
    val severity          = varchar("severity", 50)
    val channel           = varchar("channel", 255).nullable()
    val title             = text("title").nullable()
    val messagePreview    = varchar("message_preview", 500).nullable()
    val sentAt            = timestampWithTimeZone("sent_at").nullable()
    val success           = bool("success").default(false)
    val errorMessage      = text("error_message").nullable()
    val createdAt         = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(notificationLogId)
}

// detectedFramework / detectionSource: auditoria de H-13 ao longo do tempo
object SloComplianceHistory : Table("titlis_audit.slo_compliance_history") {
    val sloComplianceHistoryId = long("slo_compliance_history_id").autoIncrement()
    val sloConfigId            = long("slo_config_id")    // ref lógica sem FK
    val namespaceId            = long("namespace_id")     // ref lógica sem FK
    val tenantId               = long("tenant_id").nullable()
    val sloName                = varchar("slo_name", 255)
    val datadogSloId           = varchar("datadog_slo_id", 255).nullable()
    val sloType                = varchar("slo_type", 50)
    val timeframe              = varchar("timeframe", 10)
    val target                 = decimal("target", 6, 4)
    val actualValue            = decimal("actual_value", 6, 4).nullable()
    val sloState               = varchar("slo_state", 50).nullable()
    val syncAction             = varchar("sync_action", 50).nullable()
    val syncError              = text("sync_error").nullable()
    val detectedFramework      = varchar("detected_framework", 50).nullable()
    val detectionSource        = varchar("detection_source", 50).nullable()
    val recordedAt             = timestampWithTimeZone("recorded_at")
    override val primaryKey = PrimaryKey(sloComplianceHistoryId)
}