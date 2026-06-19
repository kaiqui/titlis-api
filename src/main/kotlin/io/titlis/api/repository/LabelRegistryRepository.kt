package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.TenantLabelRegistry
import io.titlis.api.domain.LabelGroupDTO
import io.titlis.api.domain.LabelKeyValuesDTO
import io.titlis.api.domain.LabelRegistryDTO
import io.titlis.api.domain.LabelRegistryEntryDTO
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import java.time.OffsetDateTime
import java.time.ZoneOffset

class LabelRegistryRepository {

    suspend fun listByTenant(tenantId: Long): LabelRegistryDTO = dbQuery {
        val rows = TenantLabelRegistry
            .select(TenantLabelRegistry.columns)
            .where { (TenantLabelRegistry.tenantId eq tenantId) and (TenantLabelRegistry.isActive eq true) }
            .orderBy(TenantLabelRegistry.labelKey to SortOrder.ASC, TenantLabelRegistry.labelValue to SortOrder.ASC)
            .toList()

        val grouped = rows.groupBy { it[TenantLabelRegistry.labelKey] }
            .map { (key, entries) ->
                LabelGroupDTO(
                    key = key,
                    values = entries.map { r ->
                        LabelRegistryEntryDTO(
                            labelRegistryId = r[TenantLabelRegistry.labelRegistryId],
                            labelKey        = r[TenantLabelRegistry.labelKey],
                            labelValue      = r[TenantLabelRegistry.labelValue],
                            isActive        = r[TenantLabelRegistry.isActive],
                        )
                    },
                )
            }

        LabelRegistryDTO(labels = grouped)
    }

    suspend fun listForOperator(tenantId: Long): List<LabelKeyValuesDTO> = dbQuery {
        TenantLabelRegistry
            .select(TenantLabelRegistry.labelKey, TenantLabelRegistry.labelValue)
            .where { (TenantLabelRegistry.tenantId eq tenantId) and (TenantLabelRegistry.isActive eq true) }
            .orderBy(TenantLabelRegistry.labelKey to SortOrder.ASC, TenantLabelRegistry.labelValue to SortOrder.ASC)
            .toList()
            .groupBy { it[TenantLabelRegistry.labelKey] }
            .map { (key, rows) ->
                LabelKeyValuesDTO(key = key, values = rows.map { it[TenantLabelRegistry.labelValue] })
            }
    }

    suspend fun addValue(tenantId: Long, labelKey: String, labelValue: String): LabelRegistryEntryDTO = dbQuery {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        val existing = TenantLabelRegistry
            .select(TenantLabelRegistry.labelRegistryId, TenantLabelRegistry.isActive)
            .where {
                (TenantLabelRegistry.tenantId eq tenantId) and
                (TenantLabelRegistry.labelKey eq labelKey) and
                (TenantLabelRegistry.labelValue eq labelValue)
            }
            .singleOrNull()

        val id = if (existing != null) {
            val regId = existing[TenantLabelRegistry.labelRegistryId]
            if (!existing[TenantLabelRegistry.isActive]) {
                TenantLabelRegistry.update({ TenantLabelRegistry.labelRegistryId eq regId }) {
                    it[isActive] = true
                }
            }
            regId
        } else {
            TenantLabelRegistry.insert {
                it[TenantLabelRegistry.tenantId]   = tenantId
                it[TenantLabelRegistry.labelKey]   = labelKey
                it[TenantLabelRegistry.labelValue] = labelValue
                it[isActive]                       = true
                it[createdAt]                      = now
            }[TenantLabelRegistry.labelRegistryId]
        }

        LabelRegistryEntryDTO(labelRegistryId = id, labelKey = labelKey, labelValue = labelValue)
    }

    suspend fun removeValue(tenantId: Long, labelRegistryId: Long): Boolean = dbQuery {
        val updated = TenantLabelRegistry.update({
            (TenantLabelRegistry.tenantId eq tenantId) and
            (TenantLabelRegistry.labelRegistryId eq labelRegistryId)
        }) {
            it[isActive] = false
        }
        updated > 0
    }
}
