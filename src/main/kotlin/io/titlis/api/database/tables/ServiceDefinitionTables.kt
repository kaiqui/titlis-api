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
    val syncedAt            = timestampWithTimeZone("synced_at")
    val createdAt           = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(serviceDefinitionId)
}
