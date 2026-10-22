package com.seizethewind.lilyai.memory

import com.seizethewind.lilyai.api.ApiKeyStore
import com.seizethewind.lilyai.api.providers.TursoBackupProvider
import com.seizethewind.lilyai.api.providers.TursoClient
import com.seizethewind.lilyai.api.providers.TursoMemoryProvider
import com.seizethewind.lilyai.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

sealed interface SyncStatus {
    object Idle : SyncStatus
    object Working : SyncStatus
    data class Done(val message: String, val at: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * Keeps memories in step with the Turso memory database and makes encrypted backups in the
 * Turso backup database. All settings (URLs, tokens, passphrase) come from the encrypted key store.
 */
class CloudSync(
    private val keyStore: ApiKeyStore,
    private val settings: SettingsManager,
    private val memory: LongTermMemory
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var debounce: Job? = null

    private var cachedMemory: Pair<String, TursoMemoryProvider>? = null
    private var cachedBackup: Pair<String, TursoBackupProvider>? = null

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    fun isMemoryConfigured(): Boolean =
        keyStore.has(ID_MEMORY_URL) && keyStore.has(ID_MEMORY_TOKEN) && keyStore.has(ID_PASSPHRASE)

    fun isBackupConfigured(): Boolean =
        keyStore.has(ID_BACKUP_URL) && keyStore.has(ID_BACKUP_TOKEN) && keyStore.has(ID_PASSPHRASE)

    /** Syncs shortly after a change. Several quick changes become one sync. */
    fun requestSync() {
        if (!isMemoryConfigured()) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(DEBOUNCE_MS)
            syncNow()
        }
    }

    suspend fun syncNow(): Result<String> = track { doSync() }

    suspend fun backupNow(): Result<String> = track { doBackup() }

    suspend fun restoreLatest(): Result<String> = track { doRestore() }

    private suspend fun track(block: suspend () -> String): Result<String> =
        lock.withLock {
            _status.value = SyncStatus.Working
            val result = runCatching { block() }
            _status.value = result.fold(
                onSuccess = { SyncStatus.Done(it, System.currentTimeMillis()) },
                onFailure = { SyncStatus.Failed(it.message ?: "The cloud operation failed.") }
            )
            result
        }

    private suspend fun doSync(): String {
        val provider = openMemory().getOrThrow()
        val since = settings.lastPull
        val remote = provider.pull(since).getOrThrow()
        var newest = since
        var received = 0
        for (row in remote) {
            if (memory.applyRemote(row)) received++
            if (row.updatedAt > newest) newest = row.updatedAt
        }
        val dirty = memory.dirtyRows()
        provider.push(dirty).getOrThrow()
        dirty.forEach { memory.markClean(it.id, it.updatedAt) }
        settings.lastPull = newest
        return "Synced: $received received, ${dirty.size} sent."
    }

    private suspend fun doBackup(): String {
        val provider = openBackup().getOrThrow()
        val items = memory.all()
        val now = System.currentTimeMillis()
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("text", it.text)
                    .put("createdAt", it.createdAt)
                    .put("updatedAt", it.updatedAt)
            )
        }
        val json = JSONObject()
            .put("version", 1)
            .put("createdAt", now)
            .put("memories", array)
        provider.save(json.toString(), now).getOrThrow()
        settings.lastBackupAt = now
        return "Backed up ${items.size} memories."
    }

    private suspend fun doRestore(): String {
        val provider = openBackup().getOrThrow()
        val text = provider.latest().getOrThrow() ?: throw IOException("No backup found yet.")
        val array = JSONObject(text).getJSONArray("memories")
        var restored = 0
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val row = SyncRow(
                id = o.getString("id"),
                text = o.getString("text"),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
                deleted = false
            )
            if (memory.applyRemote(row, markDirty = true)) restored++
        }
        requestSync()
        return "Restored $restored memories from the latest backup."
    }

    private suspend fun openMemory(): Result<TursoMemoryProvider> {
        val url = keyStore.get(ID_MEMORY_URL)
        val token = keyStore.get(ID_MEMORY_TOKEN)
        val pass = keyStore.get(ID_PASSPHRASE)
        if (url == null || token == null || pass == null) {
            return Result.failure(
                IllegalStateException("Cloud memory is not set up. Add the Turso URL, token and passphrase.")
            )
        }
        val key = "$url|$token|$pass"
        cachedMemory?.let { if (it.first == key) return Result.success(it.second) }
        val client = TursoClient(url, token)
        val crypto = CloudCrypto.open(client, pass).getOrElse { return Result.failure(it) }
        val provider = TursoMemoryProvider(client, crypto)
        provider.ensureSchema().getOrElse { return Result.failure(it) }
        cachedMemory = key to provider
        return Result.success(provider)
    }

    private suspend fun openBackup(): Result<TursoBackupProvider> {
        val url = keyStore.get(ID_BACKUP_URL)
        val token = keyStore.get(ID_BACKUP_TOKEN)
        val pass = keyStore.get(ID_PASSPHRASE)
        if (url == null || token == null || pass == null) {
            return Result.failure(
                IllegalStateException("Cloud backup is not set up. Add the Turso URL, token and passphrase.")
            )
        }
        val key = "$url|$token|$pass"
        cachedBackup?.let { if (it.first == key) return Result.success(it.second) }
        val client = TursoClient(url, token)
        val crypto = CloudCrypto.open(client, pass).getOrElse { return Result.failure(it) }
        val provider = TursoBackupProvider(client, crypto)
        provider.ensureSchema().getOrElse { return Result.failure(it) }
        cachedBackup = key to provider
        return Result.success(provider)
    }

    companion object {
        const val ID_MEMORY_URL = "turso-memory-url"
        const val ID_MEMORY_TOKEN = "turso-memory-token"
        const val ID_BACKUP_URL = "turso-backup-url"
        const val ID_BACKUP_TOKEN = "turso-backup-token"
        const val ID_PASSPHRASE = "cloud-passphrase"
        private const val DEBOUNCE_MS = 2500L
    }
}
