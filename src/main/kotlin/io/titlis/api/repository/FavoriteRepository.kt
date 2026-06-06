package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import io.titlis.api.database.tables.UserFavorites
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset

class FavoriteRepository {

    suspend fun add(userId: Long, tenantId: Long, k8sUid: String) = dbQuery {
        UserFavorites.upsert(
            UserFavorites.platformUserId,
            UserFavorites.resourceType,
            UserFavorites.resourceKey,
            onUpdateExclude = listOf(UserFavorites.createdAt),
        ) {
            it[UserFavorites.platformUserId] = userId
            it[UserFavorites.tenantId] = tenantId
            it[UserFavorites.resourceType] = "workload"
            it[UserFavorites.resourceKey] = k8sUid
            it[UserFavorites.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    suspend fun remove(userId: Long, tenantId: Long, k8sUid: String) = dbQuery {
        UserFavorites.deleteWhere {
            (UserFavorites.platformUserId eq userId) and
                (UserFavorites.tenantId eq tenantId) and
                (UserFavorites.resourceType eq "workload") and
                (UserFavorites.resourceKey eq k8sUid)
        }
    }

    suspend fun listFavoriteK8sUids(userId: Long, tenantId: Long): Set<String> = dbQuery {
        UserFavorites
            .select(UserFavorites.resourceKey)
            .where {
                (UserFavorites.platformUserId eq userId) and
                    (UserFavorites.tenantId eq tenantId) and
                    (UserFavorites.resourceType eq "workload")
            }
            .mapTo(HashSet()) { it[UserFavorites.resourceKey] }
    }
}
