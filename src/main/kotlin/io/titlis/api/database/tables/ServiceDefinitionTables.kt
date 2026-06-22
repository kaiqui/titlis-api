package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object ServiceDefinitions : Table("titlis_oltp.service_definitions") {
    val serviceDefinitionId = long("service_definition_id").autoIncrement()
    val tenantId            = long("tenant_id").references(Tenants.tenantId)
    val serviceName         = text("service_name")
    val team                = text("team")
    val product             = text("product").nullable()
    val tier                = text("tier").nullable()
    val description         = text("description").nullable()
    val repoUrl             = text("repo_url").nullable()
    val rawYaml             = text("raw_yaml").nullable()
    // Fase 0 (service-yaml-discovery): gitops/remediation consumidos pela remediação + lifecycle.
    val gitopsPaths         = jsonbText("gitops_paths").nullable()
    val remediation         = jsonbText("remediation").nullable()
    val lastSeenAt          = timestampWithTimeZone("last_seen_at").nullable()
    val isStale             = bool("is_stale").default(false)
    val syncedAt            = timestampWithTimeZone("synced_at")
    val createdAt           = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(serviceDefinitionId)
}

// Padrões de correlação workload (namespaces + name_pattern regex).
// Populado na Fase 1; espelha titlis_oltp.service_queue_patterns.
object ServiceWorkloadPatterns : Table("titlis_oltp.service_workload_patterns") {
    val serviceWorkloadPatternId = long("service_workload_pattern_id").autoIncrement()
    val tenantId                 = long("tenant_id").references(Tenants.tenantId)
    val serviceDefinitionId      = long("service_definition_id").references(ServiceDefinitions.serviceDefinitionId)
    val namespaces               = jsonbText("namespaces").nullable()
    val namePattern              = text("name_pattern")
    val createdAt                = timestampWithTimeZone("created_at")
    override val primaryKey       = PrimaryKey(serviceWorkloadPatternId)
}
