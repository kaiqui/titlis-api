package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object Queues : Table("titlis_oltp.queues") {
    val queueId          = long("queue_id").autoIncrement()
    val tenantId         = long("tenant_id").references(Tenants.tenantId)
    val provider         = varchar("provider", 50).default("gcp_pubsub")
    val externalId       = varchar("external_id", 500)
    val displayName      = varchar("display_name", 255)
    val projectId        = varchar("project_id", 255).nullable()
    val topicId          = varchar("topic_id", 255).nullable()
    val isDlq            = bool("is_dlq").default(false)
    val parentQueueId    = long("parent_queue_id").nullable()
    val lifecycleState   = varchar("lifecycle_state", 20).default("DISCOVERING")
    val observationCount = integer("observation_count").default(0)
    val isActive         = bool("is_active").default(true)
    val firstSeenAt      = timestampWithTimeZone("first_seen_at")
    val lastSeenAt       = timestampWithTimeZone("last_seen_at")
    val serviceDefinitionId = long("service_definition_id").references(ServiceDefinitions.serviceDefinitionId).nullable()
    val linkSource       = varchar("link_source", 20).nullable()
    val linkConfidence   = decimal("link_confidence", 4, 3).nullable()
    val linkedAt         = timestampWithTimeZone("linked_at").nullable()
    val labels           = jsonbText("labels").nullable()
    override val primaryKey = PrimaryKey(queueId)
}

object ServiceQueuePatterns : Table("titlis_oltp.service_queue_patterns") {
    val serviceQueuePatternId = long("service_queue_pattern_id").autoIncrement()
    val tenantId              = long("tenant_id").references(Tenants.tenantId)
    val serviceDefinitionId   = long("service_definition_id").references(ServiceDefinitions.serviceDefinitionId)
    val provider              = varchar("provider", 50).default("gcp_pubsub")
    val pattern               = varchar("pattern", 500)
    val matchType             = varchar("match_type", 20).default("glob")
    val matchField            = varchar("match_field", 20).default("display_name")
    val createdAt             = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(serviceQueuePatternId)
}

object QueueLinkSuggestions : Table("titlis_oltp.queue_link_suggestions") {
    val queueLinkSuggestionId = long("queue_link_suggestion_id").autoIncrement()
    val tenantId              = long("tenant_id").references(Tenants.tenantId)
    val queueId               = long("queue_id").references(Queues.queueId)
    val serviceDefinitionId   = long("service_definition_id").references(ServiceDefinitions.serviceDefinitionId)
    val confidence            = decimal("confidence", 4, 3)
    val suggestionSource      = varchar("source", 20)
    val createdAt             = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(queueLinkSuggestionId)
}

object QueueObservations : Table("titlis_oltp.queue_observations") {
    val queueObservationId       = long("queue_observation_id").autoIncrement()
    val queueId                  = long("queue_id").references(Queues.queueId)
    val tenantId                 = long("tenant_id")
    val numUndeliveredMessages   = long("num_undelivered_messages").nullable()
    val oldestUnackedAgeSeconds  = long("oldest_unacked_age_seconds").nullable()
    val pullMessageCountRate     = double("pull_message_count_rate").nullable()
    val sendMessageCountRate     = double("send_message_count_rate").nullable()
    val ackMessageCountRate      = double("ack_message_count_rate").nullable()
    val observedAt               = timestampWithTimeZone("observed_at")
    override val primaryKey = PrimaryKey(queueObservationId)
}

object QueueThresholds : Table("titlis_oltp.queue_thresholds") {
    val queueThresholdId = long("queue_threshold_id").autoIncrement()
    val queueId          = long("queue_id").references(Queues.queueId)
    val tenantId         = long("tenant_id")
    val p50Backlog       = long("p50_backlog").nullable()
    val p75Backlog       = long("p75_backlog").nullable()
    val p95Backlog       = long("p95_backlog").nullable()
    val p50AgeSec        = long("p50_age_sec").nullable()
    val p75AgeSec        = long("p75_age_sec").nullable()
    val p95AgeSec        = long("p95_age_sec").nullable()
    val backlogWarning   = long("backlog_warning")
    val backlogCritical  = long("backlog_critical")
    val ageWarningSec    = long("age_warning_sec")
    val ageCriticalSec   = long("age_critical_sec")
    val observationCount = integer("observation_count")
    val calculatedAt     = timestampWithTimeZone("calculated_at")
    override val primaryKey = PrimaryKey(queueThresholdId)
}

object QueueScorecards : Table("titlis_oltp.queue_scorecards") {
    val queueScorecardId  = long("queue_scorecard_id").autoIncrement()
    val queueId           = long("queue_id").references(Queues.queueId)
    val tenantId          = long("tenant_id").references(Tenants.tenantId)
    val version           = integer("version").default(1)
    val overallScore      = decimal("overall_score", 5, 2).nullable()
    val complianceStatus  = varchar("compliance_status", 20).nullable()
    val totalRules        = integer("total_rules").nullable()
    val passedRules       = integer("passed_rules").nullable()
    val failedRules       = integer("failed_rules").nullable()
    val criticalFailures  = integer("critical_failures").nullable()
    val errorCount        = integer("error_count").nullable()
    val warningCount      = integer("warning_count").nullable()
    val evaluatedAt       = timestampWithTimeZone("evaluated_at").nullable()
    val rawMetadata       = jsonbText("raw_metadata").nullable()
    override val primaryKey = PrimaryKey(queueScorecardId)
}

object QueuePillarScores : Table("titlis_oltp.queue_pillar_scores") {
    val queuePillarScoreId = long("queue_pillar_score_id").autoIncrement()
    val queueScorecardId   = long("queue_scorecard_id").references(QueueScorecards.queueScorecardId)
    val pillar             = varchar("pillar", 50)
    val pillarScore        = decimal("pillar_score", 5, 2).nullable()
    val passedChecks       = integer("passed_checks").nullable()
    val failedChecks       = integer("failed_checks").nullable()
    val weightedScore      = decimal("weighted_score", 8, 4).nullable()
    override val primaryKey = PrimaryKey(queuePillarScoreId)
}

object QueueValidationResults : Table("titlis_oltp.queue_validation_results") {
    val queueValidationResultId = long("queue_validation_result_id").autoIncrement()
    val queueScorecardId        = long("queue_scorecard_id").references(QueueScorecards.queueScorecardId)
    val ruleId                  = varchar("rule_id", 20)
    val ruleName                = varchar("rule_name", 255).nullable()
    val pillar                  = varchar("pillar", 50).nullable()
    val severity                = varchar("severity", 20).nullable()
    val rulePassed              = bool("rule_passed")
    val resultMessage           = text("result_message").nullable()
    val actualValue             = text("actual_value").nullable()
    val evaluatedAt             = timestampWithTimeZone("evaluated_at")
    override val primaryKey = PrimaryKey(queueValidationResultId)
}

object TenantLabelRegistry : Table("titlis_oltp.tenant_label_registry") {
    val labelRegistryId = long("label_registry_id").autoIncrement()
    val tenantId        = long("tenant_id").references(Tenants.tenantId)
    val labelKey        = varchar("label_key", 100)
    val labelValue      = varchar("label_value", 255)
    val isActive        = bool("is_active").default(true)
    val createdAt       = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(labelRegistryId)
}
