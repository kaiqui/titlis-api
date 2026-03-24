package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.SloComplianceHistory
import io.titlis.api.database.tables.SloConfigs
import io.titlis.api.domain.SloReconciledEvent
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

class SloRepository {

    suspend fun upsertSloConfig(event: SloReconciledEvent) = dbQuery {
        val now = Instant.now().toKotlinInstant()

        // Upsert estado atual por slo_config_name (chave de negócio no CRD)
        SloConfigs.upsert(SloConfigs.sloConfigName) {
            it[sloConfigName]       = event.sloName
            it[sloType]             = event.sloType
            it[timeframe]           = event.timeframe
            it[target]              = event.target.toBigDecimal()
            it[warning]             = event.warning?.toBigDecimal()
            it[autoDetectFramework] = event.autoDetectFramework
            it[detectedFramework]   = event.detectedFramework
            it[detectionSource]     = event.detectionSource
            it[k8sResourceUid]      = event.k8sResourceUid
            it[datadogSloId]        = event.datadogSloId
            it[datadogSloState]     = event.datadogSloState
            it[syncError]           = event.syncError
            it[lastSyncAt]          = now
            it[updatedAt]           = now
        }

        // Resolver slo_config_id para o registro de histórico
        val resolvedSloConfigId = SloConfigs
            .select(SloConfigs.sloConfigId)
            .where { SloConfigs.sloConfigName eq event.sloName }
            .single()[SloConfigs.sloConfigId]

        // Append ao histórico de compliance (sempre — mesmo em noop)
        SloComplianceHistory.insert {
            it[sloConfigId]       = resolvedSloConfigId
            it[sloName]           = event.sloName
            it[sloType]           = event.sloType
            it[timeframe]         = event.timeframe
            it[target]            = event.target.toBigDecimal()
            it[actualValue]       = event.actualValue?.toBigDecimal()
            it[sloState]          = event.datadogSloState
            it[syncAction]        = event.syncAction
            it[syncError]         = event.syncError
            it[datadogSloId]      = event.datadogSloId
            it[detectedFramework] = event.detectedFramework
            it[detectionSource]   = event.detectionSource
            it[recordedAt]        = now
        }
    }

    suspend fun getByName(namespace: String, name: String): Map<String, Any?>? = dbQuery {
        SloConfigs
            .select(SloConfigs.columns)
            .where { SloConfigs.sloConfigName eq name }
            .singleOrNull()
            ?.let { row ->
                mapOf(
                    "slo_config_id"     to row[SloConfigs.sloConfigId],
                    "slo_type"          to row[SloConfigs.sloType],
                    "timeframe"         to row[SloConfigs.timeframe],
                    "target"            to row[SloConfigs.target],
                    "datadog_slo_id"    to row[SloConfigs.datadogSloId],
                    "datadog_slo_state" to row[SloConfigs.datadogSloState],
                    "detected_framework" to row[SloConfigs.detectedFramework],
                    "detection_source"  to row[SloConfigs.detectionSource],
                    "last_sync_at"      to row[SloConfigs.lastSyncAt]?.toString(),
                )
            }
    }
}