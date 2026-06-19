package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.GcpBillingConfigs
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.update
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class GcpBillingConfigRecord(
    val gcpBillingConfigId: Long,
    val tenantId: Long,
    val isActive: Boolean,
    val billingAccountId: String,
    val projectId: String,
    val bigqueryDataset: String,
    val bigqueryLocation: String,
    val credentialsEnc: String,
    val lastCollectionAt: OffsetDateTime?,
    val workloadsCovered: Int,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

@Serializable
data class GcpBillingStatusResponse(
    val configured: Boolean,
    val lastCollectionAt: String?,
    val workloadsCovered: Int,
    val nextCollectionAt: String?,
)

class GcpBillingConfigRepository {

    suspend fun upsert(
        tenantId: Long,
        billingAccountId: String,
        projectId: String,
        bigqueryDataset: String,
        bigqueryLocation: String,
        credentialsEnc: String,
    ): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        GcpBillingConfigs.upsert(
            GcpBillingConfigs.tenantId,
            onUpdateExclude = listOf(GcpBillingConfigs.createdAt, GcpBillingConfigs.lastCollectionAt, GcpBillingConfigs.workloadsCovered),
        ) {
            it[GcpBillingConfigs.tenantId]         = tenantId
            it[GcpBillingConfigs.isActive]         = true
            it[GcpBillingConfigs.billingAccountId] = billingAccountId
            it[GcpBillingConfigs.projectId]        = projectId
            it[GcpBillingConfigs.bigqueryDataset]  = bigqueryDataset
            it[GcpBillingConfigs.bigqueryLocation] = bigqueryLocation.ifBlank { "US" }
            it[GcpBillingConfigs.credentialsEnc]   = credentialsEnc
            it[GcpBillingConfigs.createdAt]        = now
            it[GcpBillingConfigs.updatedAt]        = now
        }
    }

    suspend fun getStatus(tenantId: Long): GcpBillingStatusResponse = dbQuery {
        val row = GcpBillingConfigs
            .select(GcpBillingConfigs.columns)
            .where { GcpBillingConfigs.tenantId eq tenantId }
            .singleOrNull()

        if (row == null) {
            return@dbQuery GcpBillingStatusResponse(
                configured = false,
                lastCollectionAt = null,
                workloadsCovered = 0,
                nextCollectionAt = null,
            )
        }

        GcpBillingStatusResponse(
            configured = row[GcpBillingConfigs.isActive],
            lastCollectionAt = row[GcpBillingConfigs.lastCollectionAt]?.toString(),
            workloadsCovered = row[GcpBillingConfigs.workloadsCovered],
            nextCollectionAt = null,
        )
    }

    suspend fun getAllActive(): List<GcpBillingConfigRecord> = dbQuery {
        GcpBillingConfigs
            .selectAll()
            .where { GcpBillingConfigs.isActive eq true }
            .map { mapRow(it) }
    }

    suspend fun markCollected(tenantId: Long, workloadsCovered: Int): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        GcpBillingConfigs.update({ GcpBillingConfigs.tenantId eq tenantId }) {
            it[GcpBillingConfigs.lastCollectionAt]  = now
            it[GcpBillingConfigs.workloadsCovered]  = workloadsCovered
            it[GcpBillingConfigs.updatedAt]         = now
        }
    }

    private fun mapRow(row: ResultRow) = GcpBillingConfigRecord(
        gcpBillingConfigId = row[GcpBillingConfigs.gcpBillingConfigId],
        tenantId           = row[GcpBillingConfigs.tenantId],
        isActive           = row[GcpBillingConfigs.isActive],
        billingAccountId   = row[GcpBillingConfigs.billingAccountId],
        projectId          = row[GcpBillingConfigs.projectId],
        bigqueryDataset    = row[GcpBillingConfigs.bigqueryDataset],
        bigqueryLocation   = row[GcpBillingConfigs.bigqueryLocation],
        credentialsEnc     = row[GcpBillingConfigs.credentialsEnc],
        lastCollectionAt   = row[GcpBillingConfigs.lastCollectionAt]?.let {
            OffsetDateTime.ofInstant(it.toInstant(), ZoneOffset.UTC)
        },
        workloadsCovered   = row[GcpBillingConfigs.workloadsCovered],
        createdAt          = OffsetDateTime.ofInstant(row[GcpBillingConfigs.createdAt].toInstant(), ZoneOffset.UTC),
        updatedAt          = OffsetDateTime.ofInstant(row[GcpBillingConfigs.updatedAt].toInstant(), ZoneOffset.UTC),
    )
}
