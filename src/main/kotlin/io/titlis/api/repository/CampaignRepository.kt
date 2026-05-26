package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.EventStorePrCampaign
import io.titlis.api.database.tables.PrCampaignEnvSteps
import io.titlis.api.database.tables.PrCampaignItems
import io.titlis.api.database.tables.PrCampaigns
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class EnvStepDetail(
    val stepId: Long,
    val environment: String,
    val status: String,
    val prNumber: Int?,
    val prUrl: String?,
    val startedAt: String?,
    val finishedAt: String?,
)

@Serializable
data class CampaignItemDetail(
    val itemId: Long,
    val workloadId: String,
    val deploymentName: String,
    val namespace: String,
    val clusterName: String,
    val repoUrl: String,
    val cascadeUpTo: String,
    val status: String,
    val errorMessage: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val envSteps: List<EnvStepDetail>,
)

@Serializable
data class CampaignSummary(
    val id: String,
    val tenantId: Long,
    val workflowId: String,
    val actorEmail: String?,
    val triggerSource: String,
    val ruleId: String?,
    val title: String,
    val status: String,
    val totalItems: Int,
    val succeededItems: Int,
    val failedItems: Int,
    val skippedItems: Int,
    val createdAt: String,
    val updatedAt: String,
)

class CampaignRepository {
    suspend fun insert(
        id: String,
        tenantId: Long,
        workflowId: String,
        actorEmail: String?,
        triggerSource: String,
        ruleId: String?,
        title: String,
        description: String?,
        status: String,
        idempotencyKey: String,
        totalItems: Int,
    ): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        PrCampaigns.insert {
            it[PrCampaigns.prCampaignId]   = id
            it[PrCampaigns.tenantId]       = tenantId
            it[PrCampaigns.workflowId]     = workflowId
            it[PrCampaigns.actorEmail]     = actorEmail
            it[PrCampaigns.triggerSource]  = triggerSource
            it[PrCampaigns.ruleId]         = ruleId
            it[PrCampaigns.title]          = title
            it[PrCampaigns.description]    = description
            it[PrCampaigns.status]         = status
            it[PrCampaigns.idempotencyKey] = idempotencyKey
            it[PrCampaigns.totalItems]     = totalItems
            it[PrCampaigns.createdAt]      = now
            it[PrCampaigns.updatedAt]      = now
        }
    }

    suspend fun list(tenantId: Long, limit: Int = 50): List<CampaignSummary> = dbQuery {
        PrCampaigns
            .select(
                PrCampaigns.prCampaignId, PrCampaigns.tenantId, PrCampaigns.workflowId,
                PrCampaigns.actorEmail, PrCampaigns.triggerSource, PrCampaigns.ruleId,
                PrCampaigns.title, PrCampaigns.status, PrCampaigns.totalItems,
                PrCampaigns.succeededItems, PrCampaigns.failedItems, PrCampaigns.skippedItems,
                PrCampaigns.createdAt, PrCampaigns.updatedAt,
            )
            .where { PrCampaigns.tenantId eq tenantId }
            .orderBy(PrCampaigns.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { mapCampaign(it) }
    }

    suspend fun findById(id: String, tenantId: Long): CampaignSummary? = dbQuery {
        PrCampaigns
            .select(
                PrCampaigns.prCampaignId, PrCampaigns.tenantId, PrCampaigns.workflowId,
                PrCampaigns.actorEmail, PrCampaigns.triggerSource, PrCampaigns.ruleId,
                PrCampaigns.title, PrCampaigns.status, PrCampaigns.totalItems,
                PrCampaigns.succeededItems, PrCampaigns.failedItems, PrCampaigns.skippedItems,
                PrCampaigns.createdAt, PrCampaigns.updatedAt,
            )
            .where { (PrCampaigns.prCampaignId eq id) and (PrCampaigns.tenantId eq tenantId) }
            .singleOrNull()
            ?.let { mapCampaign(it) }
    }

    suspend fun updateStatus(
        id: String,
        tenantId: Long,
        status: String,
        succeededItems: Int? = null,
        failedItems: Int? = null,
        skippedItems: Int? = null,
    ): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        PrCampaigns.update({ (PrCampaigns.prCampaignId eq id) and (PrCampaigns.tenantId eq tenantId) }) { row ->
            row[PrCampaigns.status]    = status
            row[PrCampaigns.updatedAt] = now
            succeededItems?.let { row[PrCampaigns.succeededItems] = it }
            failedItems?.let    { row[PrCampaigns.failedItems]    = it }
            skippedItems?.let   { row[PrCampaigns.skippedItems]   = it }
        }
    }

    suspend fun findActiveByRuleId(tenantId: Long, ruleId: String): CampaignSummary? = dbQuery {
        PrCampaigns
            .select(
                PrCampaigns.prCampaignId, PrCampaigns.tenantId, PrCampaigns.workflowId,
                PrCampaigns.actorEmail, PrCampaigns.triggerSource, PrCampaigns.ruleId,
                PrCampaigns.title, PrCampaigns.status, PrCampaigns.totalItems,
                PrCampaigns.succeededItems, PrCampaigns.failedItems, PrCampaigns.skippedItems,
                PrCampaigns.createdAt, PrCampaigns.updatedAt,
            )
            .where {
                (PrCampaigns.tenantId eq tenantId) and
                (PrCampaigns.ruleId eq ruleId) and
                (PrCampaigns.status inList listOf("QUEUED", "RUNNING"))
            }
            .orderBy(PrCampaigns.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { mapCampaign(it) }
    }

    suspend fun incrementItem(campaignId: String, tenantId: Long, outcome: String): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        PrCampaigns.update({ (PrCampaigns.prCampaignId eq campaignId) and (PrCampaigns.tenantId eq tenantId) }) { row ->
            when (outcome.lowercase()) {
                "succeeded" -> row[PrCampaigns.succeededItems] = PrCampaigns.succeededItems + 1
                "failed"    -> row[PrCampaigns.failedItems]    = PrCampaigns.failedItems    + 1
                else        -> row[PrCampaigns.skippedItems]   = PrCampaigns.skippedItems   + 1
            }
            row[PrCampaigns.updatedAt] = now
        }
    }

    suspend fun appendEvent(campaignId: String, tenantId: Long, eventType: String, payload: String): Unit = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        EventStorePrCampaign.insert {
            it[EventStorePrCampaign.campaignId]  = campaignId
            it[EventStorePrCampaign.tenantId]    = tenantId
            it[EventStorePrCampaign.eventType]   = eventType
            it[EventStorePrCampaign.payload]     = payload
            it[EventStorePrCampaign.occurredAt]  = now
            it[EventStorePrCampaign.createdAt]   = now
        }
    }

    suspend fun listItems(campaignId: String, tenantId: Long): List<CampaignItemDetail> = dbQuery {
        PrCampaignItems
            .leftJoin(PrCampaignEnvSteps)
            .select(
                PrCampaignItems.prCampaignItemId, PrCampaignItems.workloadId,
                PrCampaignItems.deploymentName, PrCampaignItems.namespace,
                PrCampaignItems.clusterName, PrCampaignItems.repoUrl,
                PrCampaignItems.cascadeUpTo, PrCampaignItems.status,
                PrCampaignItems.errorMessage, PrCampaignItems.startedAt,
                PrCampaignItems.finishedAt,
                PrCampaignEnvSteps.prCampaignEnvStepId, PrCampaignEnvSteps.environment,
                PrCampaignEnvSteps.status, PrCampaignEnvSteps.prNumber,
                PrCampaignEnvSteps.prUrl, PrCampaignEnvSteps.startedAt,
                PrCampaignEnvSteps.finishedAt,
            )
            .where {
                (PrCampaignItems.prCampaignId eq campaignId) and
                (PrCampaignItems.tenantId eq tenantId)
            }
            .orderBy(PrCampaignItems.prCampaignItemId, SortOrder.ASC)
            .let { rows ->
                val grouped = LinkedHashMap<Long, Pair<ResultRow, MutableList<ResultRow>>>()
                for (row in rows) {
                    val itemId = row[PrCampaignItems.prCampaignItemId]
                    grouped.getOrPut(itemId) { Pair(row, mutableListOf()) }
                    if (row.getOrNull(PrCampaignEnvSteps.prCampaignEnvStepId) != null) {
                        grouped[itemId]!!.second.add(row)
                    }
                }
                grouped.values.map { (itemRow, stepRows) ->
                    CampaignItemDetail(
                        itemId         = itemRow[PrCampaignItems.prCampaignItemId],
                        workloadId     = itemRow[PrCampaignItems.workloadId],
                        deploymentName = itemRow[PrCampaignItems.deploymentName],
                        namespace      = itemRow[PrCampaignItems.namespace],
                        clusterName    = itemRow[PrCampaignItems.clusterName],
                        repoUrl        = itemRow[PrCampaignItems.repoUrl],
                        cascadeUpTo    = itemRow[PrCampaignItems.cascadeUpTo],
                        status         = itemRow[PrCampaignItems.status],
                        errorMessage   = itemRow[PrCampaignItems.errorMessage],
                        startedAt      = itemRow[PrCampaignItems.startedAt]?.toString(),
                        finishedAt     = itemRow[PrCampaignItems.finishedAt]?.toString(),
                        envSteps       = stepRows.sortedBy { it[PrCampaignEnvSteps.prCampaignEnvStepId] }.map { step ->
                            EnvStepDetail(
                                stepId      = step[PrCampaignEnvSteps.prCampaignEnvStepId],
                                environment = step[PrCampaignEnvSteps.environment],
                                status      = step[PrCampaignEnvSteps.status],
                                prNumber    = step[PrCampaignEnvSteps.prNumber],
                                prUrl       = step[PrCampaignEnvSteps.prUrl],
                                startedAt   = step[PrCampaignEnvSteps.startedAt]?.toString(),
                                finishedAt  = step[PrCampaignEnvSteps.finishedAt]?.toString(),
                            )
                        },
                    )
                }
            }
    }

    private fun mapCampaign(row: ResultRow) = CampaignSummary(
        id             = row[PrCampaigns.prCampaignId],
        tenantId       = row[PrCampaigns.tenantId],
        workflowId     = row[PrCampaigns.workflowId],
        actorEmail     = row[PrCampaigns.actorEmail],
        triggerSource  = row[PrCampaigns.triggerSource],
        ruleId         = row[PrCampaigns.ruleId],
        title          = row[PrCampaigns.title],
        status         = row[PrCampaigns.status],
        totalItems     = row[PrCampaigns.totalItems],
        succeededItems = row[PrCampaigns.succeededItems],
        failedItems    = row[PrCampaigns.failedItems],
        skippedItems   = row[PrCampaigns.skippedItems],
        createdAt      = row[PrCampaigns.createdAt].toString(),
        updatedAt      = row[PrCampaigns.updatedAt].toString(),
    )
}
