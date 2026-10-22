package com.seizethewind.lilyai.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class MemoryItem(
    val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** One memory as it travels to and from the cloud. Deleted memories are kept as empty tombstones. */
data class SyncRow(
    val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean
)

/** Memories the user asked LilyAi to keep. Stored in a private SQLite database on the phone. */
class LongTermMemory(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lilyai_memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE memories (" +
                "id TEXT PRIMARY KEY, " +
                "text TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "deleted INTEGER NOT NULL DEFAULT 0, " +
                "dirty INTEGER NOT NULL DEFAULT 1)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(text: String): MemoryItem {
        val now = System.currentTimeMillis()
        val item = MemoryItem(UUID.randomUUID().toString(), text.trim(), now, now)
        val values = ContentValues().apply {
            put("id", item.id)
            put("text", item.text)
            put("created_at", now)
            put("updated_at", now)
            put("deleted", 0)
            put("dirty", 1)
        }
        writableDatabase.insert("memories", null, values)
        return item
    }

    /** Removes the text right away and keeps an empty tombstone so the deletion can sync. */
    @Synchronized
    fun delete(id: String) {
        val values = ContentValues().apply {
            put("text", "")
            put("deleted", 1)
            put("updated_at", System.currentTimeMillis())
            put("dirty", 1)
        }
        writableDatabase.update("memories", values, "id = ?", arrayOf(id))
    }

    @Synchronized
    fun all(): List<MemoryItem> =
        readableDatabase.query(
            "memories",
            arrayOf("id", "text", "created_at", "updated_at"),
            "deleted = 0", null, null, null, "created_at DESC"
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(MemoryItem(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)))
                }
            }
        }

    /** Simple keyword match: memories sharing the most words with the query come first. */
    @Synchronized
    fun search(query: String, limit: Int): List<MemoryItem> {
        val words = query.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.length >= 3 }
            .toSet()
        if (words.isEmpty()) return emptyList()
        return all()
            .map { m ->
                val lower = m.text.lowercase()
                m to words.count { w -> lower.contains(w) }
            }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryItem, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .take(limit)
            .map { it.first }
    }

    @Synchronized
    fun dirtyRows(): List<SyncRow> =
        readableDatabase.query(
            "memories",
            arrayOf("id", "text", "created_at", "updated_at", "deleted"),
            "dirty = 1", null, null, null, null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(SyncRow(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4) == 1))
                }
            }
        }

    /** Marks a row as uploaded, unless it was changed again while the upload was running. */
    @Synchronized
    fun markClean(id: String, updatedAt: Long) {
        writableDatabase.execSQL(
            "UPDATE memories SET dirty = 0 WHERE id = ? AND updated_at = ?",
            arrayOf<Any>(id, updatedAt)
        )
    }

    /** Last write wins: a row from the cloud is applied only if it is newer than the local one. */
    @Synchronized
    fun applyRemote(row: SyncRow, markDirty: Boolean = false): Boolean {
        val db = writableDatabase
        val localUpdated = db.rawQuery(
            "SELECT updated_at FROM memories WHERE id = ?", arrayOf(row.id)
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        if (localUpdated != null && localUpdated >= row.updatedAt) return false
        val values = ContentValues().apply {
            put("id", row.id)
            put("text", row.text)
            put("created_at", row.createdAt)
            put("updated_at", row.updatedAt)
            put("deleted", if (row.deleted) 1 else 0)
            put("dirty", if (markDirty) 1 else 0)
        }
        db.insertWithOnConflict("memories", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }
}
