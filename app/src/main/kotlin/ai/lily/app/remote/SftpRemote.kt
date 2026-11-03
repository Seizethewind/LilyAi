package ai.lily.app.remote

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64

/**
 * SFTP over SSH, using the maintained JSch fork (com.github.mwiede:jsch), which turns off the old weak algorithms by default.
 * Password login only; the server key is checked through [HostKeyPolicy] before the password is sent, and key checking is
 * never switched off.
 */
object SftpConnector {
    private const val CONNECT_MS = 15_000
    private const val IO_MS = 30_000

    suspend fun connect(profile: RemoteProfile, password: String, hostKeys: HostKeyPolicy): RemoteFs = withContext(Dispatchers.IO) {
        val port = profile.sftpPort
        val repo = PolicyRepository(profile.host, port, hostKeys)
        val session = JSch().getSession(profile.user, profile.host, port)
        session.setPassword(password)
        session.hostKeyRepository = repo
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.timeout = IO_MS
        try {
            session.connect(CONNECT_MS)
            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(CONNECT_MS)
            SftpFs(session, channel, profile.rootPath)
        } catch (e: JSchException) {
            session.disconnect()
            // A refused or changed host key is the real reason; JSch only reports that it rejected the key.
            throw repo.failure ?: mapConnect(e)
        } catch (e: RuntimeException) {
            session.disconnect()
            throw repo.failure ?: RemoteException(RemoteFailure.FAILED, e.message ?: "SFTP connection failed", e)
        }
    }

    private fun mapConnect(e: JSchException): RemoteException {
        val cause = e.cause
        val msg = e.message.orEmpty()
        return when {
            cause is ConnectException || msg.contains("Connection refused", ignoreCase = true) ->
                RemoteException(RemoteFailure.UNSUPPORTED, "no SSH service on that port", e)
            cause is SocketTimeoutException || cause is UnknownHostException || cause is NoRouteToHostException ||
                msg.contains("timeout", ignoreCase = true) ->
                RemoteException(RemoteFailure.UNREACHABLE, "could not reach the server", e)
            msg.startsWith("Auth ") ->
                RemoteException(RemoteFailure.AUTH_FAILED, "the server refused the user name or password", e)
            // Only a clear "this is not an SSH server" lets the planner try the next protocol.
            msg.contains("invalid server's version string", ignoreCase = true) ->
                RemoteException(RemoteFailure.UNSUPPORTED, "no SSH greeting from the server", e)
            else -> RemoteException(RemoteFailure.FAILED, msg.ifBlank { "SFTP connection failed" }, e)
        }
    }

    /** Hands JSch's key check to [HostKeyPolicy], which asks the person the first time and refuses a changed key. */
    private class PolicyRepository(
        private val host: String,
        private val port: Int,
        private val policy: HostKeyPolicy,
    ) : HostKeyRepository {
        var failure: RemoteException? = null
            private set

        override fun check(host: String, key: ByteArray): Int = try {
            val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
            runBlocking { policy.verify(this@PolicyRepository.host, port, keyType(key), fingerprint) }
            HostKeyRepository.OK
        } catch (e: RemoteException) {
            failure = e
            if (e.failure == RemoteFailure.HOST_KEY_CHANGED) HostKeyRepository.CHANGED else HostKeyRepository.NOT_INCLUDED
        }

        /** The key blob starts with its own type name: a 4 byte length, then text such as ssh-ed25519. */
        private fun keyType(key: ByteArray): String {
            if (key.size < 4) return "unknown"
            val len = ((key[0].toInt() and 0xff) shl 24) or ((key[1].toInt() and 0xff) shl 16) or
                ((key[2].toInt() and 0xff) shl 8) or (key[3].toInt() and 0xff)
            if (len < 1 || len > 64 || key.size < 4 + len) return "unknown"
            return String(key, 4, len, Charsets.US_ASCII)
        }

        // Nothing is kept here: accepted keys live in the app's own HostKeyStore.
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "lily"
        override fun getHostKey(): Array<HostKey>? = null
        override fun getHostKey(host: String?, type: String?): Array<HostKey>? = null
    }
}

class SftpFs(
    private val session: Session,
    private val channel: ChannelSftp,
    private val root: String,
) : RemoteFs {
    override val protocol: RemoteProtocol = RemoteProtocol.SFTP

    // One SFTP channel handles one request at a time.
    private val one = Mutex()

    private suspend fun <T> io(block: () -> T): T = one.withLock {
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: RemoteException) {
                throw e
            } catch (e: SftpException) {
                val kind = if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) RemoteFailure.NOT_FOUND else RemoteFailure.FAILED
                throw RemoteException(kind, e.message ?: "SFTP error", e)
            } catch (e: JSchException) {
                throw RemoteException(RemoteFailure.UNREACHABLE, e.message ?: "the connection was lost", e)
            } catch (e: IOException) {
                throw RemoteException(RemoteFailure.FAILED, e.message ?: "read or write failed", e)
            }
        }
    }

    override suspend fun list(dir: String): List<RemoteEntry> = io {
        val base = absolute(dir)
        val out = ArrayList<RemoteEntry>()
        for (item in channel.ls(base) as Iterable<*>) {
            val entry = item as ChannelSftp.LsEntry
            if (entry.filename == "." || entry.filename == "..") continue
            out += entry(join(dir, entry.filename), entry.attrs)
        }
        out
    }

    override suspend fun stat(path: String): RemoteEntry? = io { lstatOrNull(path) }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = io {
        val found = lstatOrNull(path) ?: throw RemoteException(RemoteFailure.NOT_FOUND, "$path is not on the server")
        if (found.isDir || found.isLink) throw RemoteException(RemoteFailure.FAILED, "$path is not a plain file")
        if (found.size > maxBytes) throw RemoteException(RemoteFailure.TOO_LARGE, "$path is larger than $maxBytes bytes")
        val bytes = channel.get(absolute(path)).use { it.readBytes() }
        if (bytes.size > maxBytes) throw RemoteException(RemoteFailure.TOO_LARGE, "$path grew past $maxBytes bytes while reading")
        bytes
    }

    // shortcut: writes in place, so a dropped connection mid-write can leave a short file; upgrade to write-then-rename if that bites.
    override suspend fun write(path: String, bytes: ByteArray) = io {
        if (lstatOrNull(path)?.isLink == true) throw RemoteException(RemoteFailure.FAILED, "$path is a link; not writing through it")
        channel.put(ByteArrayInputStream(bytes), absolute(path), ChannelSftp.OVERWRITE)
    }

    override suspend fun mkdir(path: String) = io { channel.mkdir(absolute(path)) }

    /** A folder must be empty: nothing here deletes a tree in one go. */
    override suspend fun delete(path: String) = io {
        val found = lstatOrNull(path) ?: throw RemoteException(RemoteFailure.NOT_FOUND, "$path is not on the server")
        if (found.isDir) channel.rmdir(absolute(path)) else channel.rm(absolute(path))
    }

    override suspend fun rename(from: String, to: String) = io { channel.rename(absolute(from), absolute(to)) }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }

    private fun lstatOrNull(path: String): RemoteEntry? = try {
        entry(path.trim('/'), channel.lstat(absolute(path)))
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
    }

    // The server's own clock, in seconds, made into milliseconds.
    private fun entry(path: String, a: com.jcraft.jsch.SftpATTRS) =
        RemoteEntry(path, a.isDir, a.size, a.mTime * 1000L, a.isLink)

    private fun join(dir: String, name: String): String =
        listOf(dir.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")

    /** The full server path of [path], which is relative to the workspace folder. Never leaves that folder. */
    private fun absolute(path: String): String {
        if ('\u0000' in path) throw RemoteException(RemoteFailure.FAILED, "A path cannot hold a null character")
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." }) throw RemoteException(RemoteFailure.FAILED, "The path $path is not allowed")
        return (root.trimEnd('/') + "/" + parts.joinToString("/")).trimEnd('/').ifEmpty { "/" }
    }
}
