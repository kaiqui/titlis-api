package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

// Padrões obrigatórios:
// - PKs: BIGINT IDENTITY com nome composto (<tabela>_id)
// - Nomes compostos: cluster_name, workload_kind, rule_severity, pillar_score, etc.
// - VARCHAR(n) quando tamanho máximo conhecido; TEXT apenas quando indefinido
// - Sem triggers DML — updated_at e audit trail gerenciados pela aplicação

// Fase 1 — multi-tenant foundation. tenant_id nullable enquanto operador é single-tenant.
object Tenants : Table("titlis_oltp.tenants") {
    val tenantId   = long("tenant_id").autoIncrement()
    val tenantName = varchar("tenant_name", 255)
    val slug       = varchar("slug", 100)
    val isActive   = bool("is_active").default(true)
    val plan       = varchar("plan", 50).default("free")
    val createdAt  = timestampWithTimeZone("created_at")
    val updatedAt  = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantId)
}

object Clusters : Table("titlis_oltp.clusters") {
    val clusterId   = long("cluster_id").autoIncrement()
    val tenantId    = long("tenant_id").references(Tenants.tenantId).nullable()
    val clusterName = varchar("cluster_name", 255)
    val environment = varchar("environment", 100)
    val region      = varchar("region", 100).nullable()
    val provider    = varchar("provider", 100).nullable()
    val k8sVersion  = varchar("k8s_version", 50).nullable()
    val isActive    = bool("is_active").default(true)
    val createdAt   = timestampWithTimeZone("created_at")
    val updatedAt   = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(clusterId)
}

object Namespaces : Table("titlis_oltp.namespaces") {
    val namespaceId   = long("namespace_id").autoIncrement()
    val clusterId     = long("cluster_id").references(Clusters.clusterId)
    val namespaceName = varchar("namespace_name", 255)
    val isExcluded    = bool("is_excluded").default(false)
    val labels        = jsonb("labels").nullable()
    val annotations   = jsonb("annotations").nullable()
    val createdAt     = timestampWithTimeZone("created_at")
    val updatedAt     = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(namespaceId)
}

object Workloads : Table("titlis_oltp.workloads") {
    val workloadId           = long("workload_id").autoIncrement()
    val namespaceId          = long("namespace_id").references(Namespaces.namespaceId)
    val workloadName         = varchar("workload_name", 255)
    val workloadKind         = varchar("workload_kind", 100).default("Deployment")
    val k8sUid               = varchar("k8s_uid", 255).nullable()  // metadata.uid — chave de negócio para lookups por evento
    val serviceTier          = varchar("service_tier", 20).nullable()
    val ddGitRepositoryUrl   = varchar("dd_git_repository_url", 500).nullable()
    val backstageComponent   = varchar("backstage_component", 255).nullable()
    val ownerTeam            = varchar("owner_team", 255).nullable()
    val labels               = jsonb("labels").nullable()
    val annotations          = jsonb("annotations").nullable()
    val resourceVersion      = varchar("resource_version", 100).nullable()
    val isActive             = bool("is_active").default(true)
    val createdAt            = timestampWithTimeZone("created_at")
    val updatedAt            = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(workloadId)
}

object ValidationRules : Table("titlis_oltp.validation_rules") {
    val validationRuleId    = long("validation_rule_id").autoIncrement()
    val ruleId              = varchar("rule_id", 50)             // RES-001, PERF-002...
    val pillar              = varchar("pillar", 50)
    val ruleSeverity        = varchar("rule_severity", 50)
    val ruleType            = varchar("rule_type", 50)
    val weight              = decimal("weight", 5, 2).default(1.0.toBigDecimal())
    val ruleName            = varchar("rule_name", 255)
    val description         = text("description").nullable()
    val isRemediable        = bool("is_remediable").default(false)
    val remediationCategory = varchar("remediation_category", 50).nullable()  // resources | hpa
    val isActive            = bool("is_active").default(true)
    val createdAt           = timestampWithTimeZone("created_at")
    val updatedAt           = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(validationRuleId)
}

object AppScorecards : Table("titlis_oltp.app_scorecards") {
    val appScorecardId   = long("app_scorecard_id").autoIncrement()
    val workloadId       = long("workload_id").references(Workloads.workloadId)
    val tenantId         = long("tenant_id").references(Tenants.tenantId).nullable()
    val version          = integer("version").default(1)
    val overallScore     = decimal("overall_score", 5, 2)
    val complianceStatus = varchar("compliance_status", 50).default("UNKNOWN")
    val totalRules       = integer("total_rules").default(0)
    val passedRules      = integer("passed_rules").default(0)
    val failedRules      = integer("failed_rules").default(0)
    val criticalFailures = integer("critical_failures").default(0)
    val errorCount       = integer("error_count").default(0)
    val warningCount     = integer("warning_count").default(0)
    val evaluatedAt      = timestampWithTimeZone("evaluated_at")
    val k8sEventType     = varchar("k8s_event_type", 50).nullable()
    val rawMetadata      = jsonb("raw_metadata").nullable()
    val createdAt        = timestampWithTimeZone("created_at")
    val updatedAt        = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(appScorecardId)
}

object PillarScores : Table("titlis_oltp.pillar_scores") {
    val pillarScoreId  = long("pillar_score_id").autoIncrement()
    val appScorecardId = long("app_scorecard_id").references(AppScorecards.appScorecardId)
    val pillar         = varchar("pillar", 50)
    val pillarScore    = decimal("pillar_score", 5, 2)
    val passedChecks   = integer("passed_checks").default(0)
    val failedChecks   = integer("failed_checks").default(0)
    val weightedScore  = decimal("weighted_score", 8, 4).nullable()
    val createdAt      = timestampWithTimeZone("created_at")
    val updatedAt      = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(pillarScoreId)
}

object ValidationResults : Table("titlis_oltp.validation_results") {
    val validationResultId = long("validation_result_id").autoIncrement()
    val appScorecardId     = long("app_scorecard_id").references(AppScorecards.appScorecardId)
    val validationRuleId   = long("validation_rule_id").references(ValidationRules.validationRuleId)
    val rulePassed         = bool("rule_passed")
    val resultMessage      = text("result_message").nullable()
    val actualValue        = text("actual_value").nullable()
    val evaluatedAt        = timestampWithTimeZone("evaluated_at")
    val createdAt          = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(validationResultId)
}

object AppRemediations : Table("titlis_oltp.app_remediations") {
    val appRemediationId     = long("app_remediation_id").autoIncrement()
    val workloadId           = long("workload_id").references(Workloads.workloadId)
    val tenantId             = long("tenant_id").references(Tenants.tenantId).nullable()
    val version              = integer("version").default(1)
    val appScorecardId       = long("app_scorecard_id").references(AppScorecards.appScorecardId).nullable()
    val appRemediationStatus = varchar("app_remediation_status", 50).default("PENDING")
    val githubPrNumber       = integer("github_pr_number").nullable()
    val githubPrUrl          = varchar("github_pr_url", 500).nullable()
    val githubPrTitle        = varchar("github_pr_title", 500).nullable()
    val githubBranch         = varchar("github_branch", 255).nullable()
    val repositoryUrl        = varchar("repository_url", 500).nullable()
    val errorMessage         = text("error_message").nullable()
    val triggeredAt          = timestampWithTimeZone("triggered_at")
    val resolvedAt           = timestampWithTimeZone("resolved_at").nullable()
    val createdAt            = timestampWithTimeZone("created_at")
    val updatedAt            = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(appRemediationId)
}

object RemediationIssues : Table("titlis_oltp.remediation_issues") {
    val remediationIssueId = long("remediation_issue_id").autoIncrement()
    val appRemediationId   = long("app_remediation_id").references(AppRemediations.appRemediationId)
    val validationRuleId   = long("validation_rule_id").references(ValidationRules.validationRuleId)
    val issueCategory      = varchar("issue_category", 50)    // resources | hpa
    val suggestedValue     = varchar("suggested_value", 100).nullable()
    val appliedValue       = varchar("applied_value", 100).nullable()
    val createdAt          = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(remediationIssueId)
}

// SloConfigs espelha o SLOConfig CRD completo incluindo auto-detecção de framework (H-13)
// e k8sResourceUid para Path B de idempotência do SLOService (Three-Path idempotency).
object SloConfigs : Table("titlis_oltp.slo_configs") {
    val sloConfigId          = long("slo_config_id").autoIncrement()
    val namespaceId          = long("namespace_id").references(Namespaces.namespaceId)
    val tenantId             = long("tenant_id").references(Tenants.tenantId).nullable()
    val sloConfigName        = varchar("slo_config_name", 255)
    val sloType              = varchar("slo_type", 50)
    val timeframe            = varchar("timeframe", 10)
    val target               = decimal("target", 6, 4)
    val warning              = decimal("warning", 6, 4).nullable()
    // Framework detection (SLOConfigSpec + SLOConfigStatus)
    val autoDetectFramework  = bool("auto_detect_framework").default(false)
    val appFramework         = varchar("app_framework", 50).nullable()  // WSGI | FASTAPI | AIOHTTP
    val detectedFramework    = varchar("detected_framework", 50).nullable()  // status.detected_framework
    val detectionSource      = varchar("detection_source", 50).nullable()    // annotation | datadog_tag | fallback
    // Idempotency — Path B (titlis_resource_uid tag no Datadog)
    val k8sResourceUid       = varchar("k8s_resource_uid", 255).nullable()
    // Datadog sync state
    val datadogSloId         = varchar("datadog_slo_id", 255).nullable()
    val datadogSloState      = varchar("datadog_slo_state", 50).nullable()
    val lastSyncAt           = timestampWithTimeZone("last_sync_at").nullable()
    val syncError            = text("sync_error").nullable()
    val specRaw              = jsonb("spec_raw").nullable()
    val version              = integer("version").default(1)
    val createdAt            = timestampWithTimeZone("created_at")
    val updatedAt            = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(sloConfigId)
}