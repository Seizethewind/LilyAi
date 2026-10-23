package com.seizethewind.lilyai.api.providers

import com.seizethewind.lilyai.api.ApiService
import com.seizethewind.lilyai.memory.CloudCrypto
import com.seizethewind.lilyai.memory.SyncRow
import org.json.JSONObject

/** Turso database that holds the synced memories. Every memory is encrypted before upload. */
class TursoMemoryProvider(
    private val client: TursoClient,
    private val crypto: CloudCrypto
) : ApiService {

    override val id: String = "turso-memory"
    override val displayName: String = "Turso memory"

    suspend fun ensureSchema(): Result<Unit> =
        client.run(
            listOf(
                "CREATE TABLE IF NOT EXISTS memories (" +
                    "id TEXT PRIMARY KEY, " +
                    "payload TEXT NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "deleted INTEGER NOT NULL DEFAULT 0)" to emptyList<Any?>()
            )
        ).map { }

    /** Memories changed at or after [since] (milliseconds). Rows that can't be decrypted are skipped. */
    suspend fun pull(since: Long): Result<List<SyncRow>> =
        client.run(
            listOf(
                "SELECT id, payload, updated_at, deleted FROM memories " +
                    "WHERE updated_at >= ? ORDER BY updated_at" to listOf<Any?>(since)
            )
        ).mapCatching { results ->
            results.first().mapNotNull { row -> decode(row) }
        }

    /** Uploads rows. A row only replaces the cloud copy if it is newer. */
    suspend fun push(rows: List<SyncRow>): Result<Unit> {
        for (chunk in rows.chunked(CHUNK)) {
            val statements = chunk.map { row ->
                val plain = JSONObject()
                    .put("text", row.text)
                    .put("createdAt", row.createdAt)
                    .toString()
                UPSERT to listOf<Any?>(
                    row.id,
                    crypto.encrypt(plain),
                    row.updatedAt,
                    if (row.deleted) 1L else 0L
                )
            }
            val result = client.run(statements)
            if (result.isFailure) return Result.failure(result.exceptionOrNull()!!)
        }
        return Result.success(Unit)
    }

    private fun decode(row: List<String?>): SyncRow? {
        val id = row.getOrNull(0) ?: return null
        val payload = row.getOrNull(1) ?: return null
        val updatedAt = row.getOrNull(2)?.toLongOrNull() ?: return null
        val deleted = row.getOrNull(3) == "1"
        val plain = crypto.decrypt(payload) ?: return null
        return try {
            val json = JSONObject(plain)
            SyncRow(id, json.optString("text", ""), json.optLong("createdAt", updatedAt), updatedAt, deleted)
        } catch (e: org.json.JSONException) {
            null
        }
    }

    companion object {
        private const val CHUNK = 25
        private const val UPSERT =
            "INSERT INTO memories (id, payload, updated_at, deleted) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET payload = excluded.payload, " +
                "updated_at = excluded.updated_at, deleted = excluded.deleted " +
                "WHERE excluded.updated_at > memories.updated_at"
    }
}
