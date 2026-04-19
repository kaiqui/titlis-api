package io.titlis.api.repository

import io.titlis.api.database.DatabaseFactory.dbQuery
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Types
import java.util.UUID

@Serializable
data class KnowledgeChunk(
    val id: String,
    val tenantId: Long?,
    val sourceType: String,
    val sourceId: String,
    val chunkText: String,
    val metadata: String?,
)

class KnowledgeRepository {

    suspend fun indexChunk(
        tenantId: Long?,
        sourceType: String,
        sourceId: String,
        chunkText: String,
        embedding: List<Float>,
        metadata: String? = null,
    ): String = dbQuery {
        val id = UUID.randomUUID().toString()
        val embeddingStr = embedding.joinToString(",", "[", "]")
        val conn = TransactionManager.current().connection.connection as java.sql.Connection

        if (tenantId == null) {
            val sql = """
                INSERT INTO titlis_ai.knowledge_chunks
                    (chunk_id, tenant_id, source_type, source_id, chunk_text, embedding, metadata)
                VALUES (?::uuid, NULL, ?, ?, ?, ?::vector, ?::jsonb)
                ON CONFLICT (source_type, source_id) WHERE tenant_id IS NULL
                DO UPDATE SET
                    chunk_text = EXCLUDED.chunk_text,
                    embedding  = EXCLUDED.embedding,
                    metadata   = EXCLUDED.metadata,
                    created_at = now()
            """.trimIndent()
            conn.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, id)
                pstmt.setString(2, sourceType)
                pstmt.setString(3, sourceId)
                pstmt.setString(4, chunkText)
                pstmt.setString(5, embeddingStr)
                if (metadata != null) pstmt.setString(6, metadata) else pstmt.setNull(6, Types.OTHER)
                pstmt.execute()
            }
        } else {
            val sql = """
                INSERT INTO titlis_ai.knowledge_chunks
                    (chunk_id, tenant_id, source_type, source_id, chunk_text, embedding, metadata)
                VALUES (?::uuid, ?, ?, ?, ?, ?::vector, ?::jsonb)
                ON CONFLICT (tenant_id, source_type, source_id) WHERE tenant_id IS NOT NULL
                DO UPDATE SET
                    chunk_text = EXCLUDED.chunk_text,
                    embedding  = EXCLUDED.embedding,
                    metadata   = EXCLUDED.metadata,
                    created_at = now()
            """.trimIndent()
            conn.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, id)
                pstmt.setLong(2, tenantId)
                pstmt.setString(3, sourceType)
                pstmt.setString(4, sourceId)
                pstmt.setString(5, chunkText)
                pstmt.setString(6, embeddingStr)
                if (metadata != null) pstmt.setString(7, metadata) else pstmt.setNull(7, Types.OTHER)
                pstmt.execute()
            }
        }
        id
    }

    suspend fun searchSimilar(
        embedding: List<Float>,
        tenantId: Long,
        limit: Int = 5,
    ): List<KnowledgeChunk> = dbQuery {
        val embeddingStr = embedding.joinToString(",", "[", "]")
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val sql = """
            SELECT chunk_id, tenant_id, source_type, source_id, chunk_text, metadata
            FROM titlis_ai.knowledge_chunks
            WHERE (tenant_id IS NULL OR tenant_id = ?)
            ORDER BY embedding <=> ?::vector
            LIMIT ?
        """.trimIndent()
        conn.prepareStatement(sql).use { pstmt ->
            pstmt.setLong(1, tenantId)
            pstmt.setString(2, embeddingStr)
            pstmt.setInt(3, limit)
            val rs = pstmt.executeQuery()
            val results = mutableListOf<KnowledgeChunk>()
            while (rs.next()) {
                val rawTenantId = rs.getLong("tenant_id")
                results.add(
                    KnowledgeChunk(
                        id = rs.getString("chunk_id"),
                        tenantId = if (rs.wasNull()) null else rawTenantId,
                        sourceType = rs.getString("source_type"),
                        sourceId = rs.getString("source_id"),
                        chunkText = rs.getString("chunk_text"),
                        metadata = rs.getString("metadata"),
                    )
                )
            }
            results
        }
    }
}
