package io.titlis.api.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object GcpBillingConfigs : Table("titlis_oltp.gcp_billing_configs") {
    val gcpBillingConfigId = long("gcp_billing_config_id").autoIncrement()
    val tenantId           = long("tenant_id").references(Tenants.tenantId)
    val isActive           = bool("is_active").default(true)
    val billingAccountId   = text("billing_account_id")
    val projectId          = text("project_id")
    val bigqueryDataset    = text("bigquery_dataset")
    val bigqueryLocation   = text("bigquery_location").default("US")
    val credentialsEnc     = text("credentials_enc")
    val lastCollectionAt   = timestampWithTimeZone("last_collection_at").nullable()
    val workloadsCovered   = integer("workloads_covered").default(0)
    val createdAt          = timestampWithTimeZone("created_at")
    val updatedAt          = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(gcpBillingConfigId)
}

object WorkloadCostMetrics : Table("titlis_ts.workload_cost_metrics") {
    val workloadCostMetricId = long("workload_cost_metric_id").autoIncrement()
    val workloadId           = long("workload_id")
    val tenantId             = long("tenant_id")
    val namespace            = text("namespace")
    val clusterName          = text("cluster_name")
    val workloadName         = text("workload_name")
    val team                 = text("team").nullable()
    val collectedDate        = date("collected_date")
    val provider             = varchar("provider", 50)
    val currency             = varchar("currency", 10).default("USD")
    val computeCost          = decimal("compute_cost", 12, 6).default(0.toBigDecimal())
    val storageCost          = decimal("storage_cost", 12, 6).default(0.toBigDecimal())
    val networkCost          = decimal("network_cost", 12, 6).default(0.toBigDecimal())
    val totalCost            = decimal("total_cost", 12, 6).default(0.toBigDecimal())
    val allocationMethod     = varchar("allocation_method", 50).default("proportional")
    val costBreakdown        = jsonbText("cost_breakdown").default("{}")
    val collectedAt          = timestampWithTimeZone("collected_at")
    override val primaryKey = PrimaryKey(workloadCostMetricId)
}

object NamespaceCostMetrics : Table("titlis_ts.namespace_cost_metrics") {
    val namespaceCostMetricId = long("namespace_cost_metric_id").autoIncrement()
    val tenantId              = long("tenant_id")
    val namespace             = text("namespace")
    val clusterName           = text("cluster_name")
    val collectedDate         = date("collected_date")
    val provider              = varchar("provider", 50)
    val currency              = varchar("currency", 10).default("USD")
    val totalCost             = decimal("total_cost", 12, 6).default(0.toBigDecimal())
    val rawClusterCost        = decimal("raw_cluster_cost", 12, 6).default(0.toBigDecimal())
    val workloadCount         = integer("workload_count").default(0)
    val allocationMethod      = varchar("allocation_method", 50).default("proportional")
    val collectedAt           = timestampWithTimeZone("collected_at")
    override val primaryKey = PrimaryKey(namespaceCostMetricId)
}
