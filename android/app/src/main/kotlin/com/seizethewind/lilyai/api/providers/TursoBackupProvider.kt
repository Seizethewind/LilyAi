package com.seizethewind.lilyai.api.providers

import com.seizethewind.lilyai.api.ApiService
import com.seizethewind.lilyai.memory.CloudCrypto
import java.io.IOException
import java.util.UUID

/**
 * Turso database that holds backup files: encrypted snapshots of everything worth restoring.
 * The 10 newest are kept.
 */
class TursoBackupProvider(
    private val client: TursoClient,
    private val crypto: CloudCrypto
) : ApiService {

    override val id: String = "turso-backup"
    override val displayName: String = "Turso backup"

    suspend fun ensureSchema(): Result<Unit> =
        client.run(
            listOf(
                "CREATE TABLE IF NOT EXISTS backups (" +
                    "id TEXT PRIMARY KEY, " +
                    "created_at INTEGER NOT NULL, " +
                    "size INTEGER NOT NULL, " +
                    "payload TEXT NOT NULL)" to emptyList<Any?>()
            )
        ).map { }

    suspend fun save(plainJson: String, createdAt: Long): Result<Unit> {
        val payload = crypto.encrypt(plainJson)
        return client.run(
            listOf(
                "INSERT INTO backups (id, created_at, size, payload) VALUES (?, ?, ?, ?)" to
                    listOf<Any?>(UUID.randomUUID().toString(), createdAt, payload.length.toLong(), payload),
                "DELETE FROM backups WHERE id NOT IN " +
                    "(SELECT id FROM backups ORDER BY created_at DESC LIMIT $KEEP)" to emptyList<Any?>()
            )
        ).map { }
    }

    /** The newest backup, decrypted, or null if there is none. */
    suspend fun latest(): Result<String?> =
        client.run(
            listOf(
                "SELECT payload FROM backups ORDER BY created_at DESC LIMIT 1" to emptyList<Any?>()
            )
        ).mapCatching { results ->
            val payload = results.first().firstOrNull()?.firstOrNull()
            if (payload == null) {
                null
            } else {
                crypto.decrypt(payload)
                    ?: throw IOException("Could not decrypt the backup. Is the passphrase the same one used to make it?")
            }
        }

    companion object {
        private const val KEEP = 10
    }
}
