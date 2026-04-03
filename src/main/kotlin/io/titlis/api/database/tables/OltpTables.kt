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
    val tenantPlan = varchar("tenant_plan", 50).default("free")
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
    val labels        = jsonbText("labels").nullable()
    val annotations   = jsonbText("annotations").nullable()
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
    val labels               = jsonbText("labels").nullable()
    val annotations          = jsonbText("annotations").nullable()
    val resourceVersion      = varchar("resource_version", 100).nullable()
    val isActive             = bool("is_active").default(true)
    val createdAt            = timestampWithTimeZone("created_at")
    val updatedAt            = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(workloadId)
}

object ValidationRules : Table("titlis_oltp.validation_rules") {
    val validationRuleId    = long("validation_rule_id").autoIncrement()
    val ruleId              = varchar("rule_id", 50)             // RES-001, PERF-002...
    val pillar              = pgEnum("pillar", "titlis_oltp.validation_pillar")
    val ruleSeverity        = pgEnum("rule_severity", "titlis_oltp.validation_severity")
    val ruleType            = pgEnum("rule_type", "titlis_oltp.validation_rule_type")
    val weight              = decimal("weight", 5, 2).default(1.0.toBigDecimal())
    val ruleName            = varchar("rule_name", 255)
    val description         = text("description").nullable()
    val isRemediable        = bool("is_remediable").default(false)
    val remediationCategory = pgEnum("remediation_category", "titlis_oltp.remediation_category").nullable()  // resources | hpa
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
    val complianceStatus = pgEnum("compliance_status", "titlis_oltp.compliance_status").default("UNKNOWN")
    val totalRules       = integer("total_rules").default(0)
    val passedRules      = integer("passed_rules").default(0)
    val failedRules      = integer("failed_rules").default(0)
    val criticalFailures = integer("critical_failures").default(0)
    val errorCount       = integer("error_count").default(0)
    val warningCount     = integer("warning_count").default(0)
    val evaluatedAt      = timestampWithTimeZone("evaluated_at")
    val k8sEventType     = varchar("k8s_event_type", 50).nullable()
    val rawMetadata      = jsonbText("raw_metadata").nullable()
    val createdAt        = timestampWithTimeZone("created_at")
    val updatedAt        = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(appScorecardId)
}

object PillarScores : Table("titlis_oltp.pillar_scores") {
    val pillarScoreId  = long("pillar_score_id").autoIncrement()
    val appScorecardId = long("app_scorecard_id").references(AppScorecards.appScorecardId)
    val pillar         = pgEnum("pillar", "titlis_oltp.validation_pillar")
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
    val appRemediationStatus = pgEnum("app_remediation_status", "titlis_oltp.remediation_status").default("PENDING")
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
    val issueCategory      = pgEnum("issue_category", "titlis_oltp.remediation_category")    // resources | hpa
    val description        = text("description").nullable()
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
    val sloType              = pgEnum("slo_type", "titlis_oltp.slo_type")
    val timeframe            = pgEnum("timeframe", "titlis_oltp.slo_timeframe")
    val target               = decimal("target", 6, 4)
    val warning              = decimal("warning", 6, 4).nullable()
    // Framework detection (SLOConfigSpec + SLOConfigStatus)
    val autoDetectFramework  = bool("auto_detect_framework").default(false)
    val appFramework         = pgEnum("app_framework", "titlis_oltp.slo_app_framework").nullable()  // WSGI | FASTAPI | AIOHTTP
    val detectedFramework    = varchar("detected_framework", 50).nullable()  // status.detected_framework
    val detectionSource      = varchar("detection_source", 50).nullable()    // annotation | datadog_tag | fallback
    // Idempotency — Path B (titlis_resource_uid tag no Datadog)
    val k8sResourceUid       = varchar("k8s_resource_uid", 255).nullable()
    // Datadog sync state
    val datadogSloId         = varchar("datadog_slo_id", 255).nullable()
    val datadogSloState      = pgEnum("datadog_slo_state", "titlis_oltp.slo_state").nullable()
    val lastSyncAt           = timestampWithTimeZone("last_sync_at").nullable()
    val syncError            = text("sync_error").nullable()
    val specRaw              = jsonbText("spec_raw").nullable()
    val version              = integer("version").default(1)
    val createdAt            = timestampWithTimeZone("created_at")
    val updatedAt            = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(sloConfigId)
}

object PlatformUsers : Table("titlis_oltp.platform_users") {
    val platformUserId = long("platform_user_id").autoIncrement()
    val tenantId = long("tenant_id").references(Tenants.tenantId)
    val email = varchar("email", 320)
    val displayName = varchar("display_name", 255).nullable()
    val passwordHash = text("password_hash").nullable()
    val platformRole = varchar("platform_role", 50).default("viewer")
    val isActive = bool("is_active").default(true)
    val isBreakGlass = bool("is_break_glass").default(false)
    val lastLoginAt = timestampWithTimeZone("last_login_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(platformUserId)
}

object TenantAuthIntegrations : Table("titlis_oltp.tenant_auth_integrations") {
    val tenantAuthIntegrationId = long("tenant_auth_integration_id").autoIncrement()
    val tenantId = long("tenant_id").references(Tenants.tenantId)
    val providerType = varchar("provider_type", 50)
    val integrationKind = varchar("integration_kind", 50).default("sso_oidc")
    val integrationName = varchar("integration_name", 255)
    val isEnabled = bool("is_enabled").default(true)
    val isPrimary = bool("is_primary").default(false)
    val issuerUrl = varchar("issuer_url", 500).nullable()
    val clientId = varchar("client_id", 255).nullable()
    val audience = varchar("audience", 255).nullable()
    val scopes = varchar("scopes", 500).nullable()
    val configJson = jsonbText("config_json").nullable()
    val configuredByPlatformUserId = long("configured_by_platform_user_id").references(PlatformUsers.platformUserId).nullable()
    val verifiedAt = timestampWithTimeZone("verified_at").nullable()
    val activatedAt = timestampWithTimeZone("activated_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tenantAuthIntegrationId)
}

object UserAuthIdentities : Table("titlis_oltp.user_auth_identities") {
    val userAuthIdentityId = long("user_auth_identity_id").autoIncrement()
    val platformUserId = long("platform_user_id").references(PlatformUsers.platformUserId)
    val tenantAuthIntegrationId = long("tenant_auth_integration_id").references(TenantAuthIntegrations.tenantAuthIntegrationId)
    val providerSubject = varchar("provider_subject", 255)
    val issuerUrl = varchar("issuer_url", 500).nullable()
    val emailSnapshot = varchar("email_snapshot", 320).nullable()
    val claimsSnapshot = jsonbText("claims_snapshot").nullable()
    val lastAuthenticatedAt = timestampWithTimeZone("last_authenticated_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(userAuthIdentityId)
}

object PlatformUserInvites : Table("titlis_oltp.platform_user_invites") {
    val platformUserInviteId = long("platform_user_invite_id").autoIncrement()
    val tenantId = long("tenant_id").references(Tenants.tenantId)
    val email = varchar("email", 320)
    val targetRole = varchar("target_role", 50).default("viewer")
    val inviteStatus = varchar("invite_status", 50).default("pending")
    val tenantAuthIntegrationId = long("tenant_auth_integration_id").references(TenantAuthIntegrations.tenantAuthIntegrationId).nullable()
    val invitedByPlatformUserId = long("invited_by_platform_user_id").references(PlatformUsers.platformUserId).nullable()
    val acceptedByPlatformUserId = long("accepted_by_platform_user_id").references(PlatformUsers.platformUserId).nullable()
    val inviteToken = varchar("invite_token", 255).nullable()
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val acceptedAt = timestampWithTimeZone("accepted_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(platformUserInviteId)
}
