package ai.lily.app.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Server keys the person accepted, kept in a small file. A damaged file counts as "no keys known", so every server is shown to
 * the person again as new; a key is never accepted without them.
 */
class DiskHostKeyStore(private val file: File) : HostKeyStore {
    private fun prefix(host: String, port: Int) = "${host.lowercase()}:$port/"

    private fun load(): MutableMap<String, String> {
        val out = linkedMapOf<String, String>()
        try {
            if (file.isFile) {
                val json = JSONObject(file.readText())
                for (key in json.keys()) out[key] = json.getString(key)
            }
        } catch (e: Exception) {
            out.clear()
        }
        return out
    }

    private fun save(keys: Map<String, String>) {
        val json = JSONObject()
        for ((k, v) in keys) json.put(k, v)
        atomicWrite(file, json.toString().toByteArray())
    }

    @Synchronized
    override fun get(host: String, port: Int, keyType: String): String? = load()[prefix(host, port) + keyType]

    @Synchronized
    override fun put(host: String, port: Int, keyType: String, fingerprint: String) {
        val keys = load()
        keys[prefix(host, port) + keyType] = fingerprint
        save(keys)
    }

    @Synchronized
    override fun knowsHost(host: String, port: Int): Boolean = load().keys.any { it.startsWith(prefix(host, port)) }

    @Synchronized
    override fun forget(host: String, port: Int) {
        val keys = load()
        keys.keys.removeAll { it.startsWith(prefix(host, port)) }
        save(keys)
    }
}

/**
 * Saved connections, accepted server keys and passwords. Profiles and keys are small files under `remote/`; passwords live only in
 * encrypted storage, and the screens save one only for an encrypted protocol.
 */
class RemoteStore(context: Context) {
    private val app = context.applicationContext
    private val base = File(app.filesDir, "remote")
    private val profilesFile = File(base, "profiles.json")

    val hostKeys: HostKeyStore = DiskHostKeyStore(File(base, "host_keys.json"))

    private val secrets by lazy {
        EncryptedSharedPreferences.create(
            app,
            "lily_remote",
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Synchronized
    fun profiles(): List<RemoteProfile> {
        val out = ArrayList<RemoteProfile>()
        try {
            if (!profilesFile.isFile) return out
            val array = JSONArray(profilesFile.readText())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (!isSafeId(id)) continue
                out += RemoteProfile(
                    id = id,
                    name = o.optString("name"),
                    host = o.optString("host"),
                    user = o.optString("user"),
                    sftpPort = o.optInt("sftpPort", RemoteProtocol.SFTP.defaultPort),
                    ftpPort = o.optInt("ftpPort", RemoteProtocol.FTP.defaultPort),
                    rootPath = o.optString("rootPath", "/"),
                    tryFtps = o.optBoolean("tryFtps", true),
                    allowFtpFallback = o.optBoolean("allowFtpFallback", false),
                )
            }
        } catch (e: Exception) {
            // A damaged list shows as empty rather than stopping the screen.
        }
        return out
    }

    @Synchronized
    fun save(profile: RemoteProfile) {
        require(isSafeId(profile.id)) { "Bad profile id" }
        write(profiles().filter { it.id != profile.id } + profile)
    }

    /** Removes the connection, its password and its local copy of the server's files. */
    @Synchronized
    fun delete(id: String) {
        if (!isSafeId(id)) return
        write(profiles().filter { it.id != id })
        clearPassword(id)
        File(base, id).deleteRecursively()
    }

    /** The folder that holds one connection's local copy, manifest and drafts. */
    fun dirFor(id: String): File {
        require(isSafeId(id)) { "Bad profile id" }
        return File(base, id).apply { mkdirs() }
    }

    fun password(id: String): String? = secrets.getString("pw_$id", null)

    fun savePassword(id: String, password: String) {
        secrets.edit().putString("pw_$id", password).apply()
    }

    fun clearPassword(id: String) {
        secrets.edit().remove("pw_$id").apply()
    }

    private fun write(all: List<RemoteProfile>) {
        val array = JSONArray()
        for (p in all) {
            array.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("host", p.host).put("user", p.user)
                    .put("sftpPort", p.sftpPort).put("ftpPort", p.ftpPort).put("rootPath", p.rootPath)
                    .put("tryFtps", p.tryFtps).put("allowFtpFallback", p.allowFtpFallback),
            )
        }
        atomicWrite(profilesFile, array.toString().toByteArray())
    }

    private fun isSafeId(id: String) = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' }
}

/** Writes through a temporary file so a crash cannot leave half a file. */
internal fun atomicWrite(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, "." + target.name + ".tmp")
    temp.writeBytes(bytes)
    try {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
