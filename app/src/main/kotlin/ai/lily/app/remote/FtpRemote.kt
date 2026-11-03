package ai.lily.app.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * FTPS (explicit TLS) and, only when the planner has the person's yes, plain FTP, using Apache Commons Net.
 *
 * For FTPS the certificate chain is checked against the phone's trusted authorities and the server name is checked against the
 * certificate; neither check is ever turned off, and the data channel is encrypted too. A bad certificate is a hard failure,
 * never "unsupported", so it can never lead to plain FTP.
 */
object FtpConnector {
    private const val CONNECT_MS = 15_000
    private const val IO_MS = 30_000

    suspend fun connect(profile: RemoteProfile, protocol: RemoteProtocol, password: String): RemoteFs = withContext(Dispatchers.IO) {
        require(protocol != RemoteProtocol.SFTP) { "Use SftpConnector for SFTP" }
        val client: FTPClient = if (protocol == RemoteProtocol.FTPS) tlsClient() else FTPClient()
        client.connectTimeout = CONNECT_MS
        client.defaultTimeout = IO_MS
        client.setDataTimeout(java.time.Duration.ofMillis(IO_MS.toLong()))
        client.controlEncoding = "UTF-8"
        try {
            client.connect(profile.host, profile.portFor(protocol))
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw RemoteException(RemoteFailure.FAILED, "the server did not welcome the connection (${client.replyCode})")
            }
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            if (!client.login(profile.user, password)) {
                throw RemoteException(RemoteFailure.AUTH_FAILED, "the server refused the user name or password")
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.setAutodetectUTF8(true)
            FtpFs(client, protocol, profile.rootPath)
        } catch (e: RemoteException) {
            runCatching { client.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw mapConnect(e, protocol)
        }
    }

    private fun tlsClient(): FTPSClient {
        val client = FTPSClient(false)
        // Null means the phone's own trusted authorities, with the whole chain checked.
        client.setTrustManager(null)
        client.setEndpointCheckingEnabled(true)
        val protocols = SSLContext.getDefault().supportedSSLParameters.protocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (protocols.isNotEmpty()) client.setEnabledProtocols(protocols.toTypedArray())
        return client
    }

    private fun mapConnect(e: Exception, protocol: RemoteProtocol): RemoteException = when {
        e is ConnectException -> RemoteException(RemoteFailure.UNSUPPORTED, "nothing is listening for ${protocol.label} on that port", e)
        e is SocketTimeoutException || e is UnknownHostException || e is NoRouteToHostException ->
            RemoteException(RemoteFailure.UNREACHABLE, "could not reach the server", e)
        // A certificate the phone does not trust, or one for another name, must never lead to a weaker protocol.
        e is SSLHandshakeException || e is SSLPeerUnverifiedException ->
            RemoteException(RemoteFailure.FAILED, "the server's certificate was not accepted: ${e.message}", e)
        // The server refused to start TLS at all.
        e is SSLException && protocol == RemoteProtocol.FTPS ->
            RemoteException(RemoteFailure.UNSUPPORTED, "the server does not offer TLS", e)
        else -> RemoteException(RemoteFailure.FAILED, e.message ?: "the connection failed", e)
    }
}

class FtpFs(
    private val client: FTPClient,
    override val protocol: RemoteProtocol,
    private val root: String,
) : RemoteFs {
    private val one = Mutex()

    private suspend fun <T> io(block: () -> T): T = one.withLock {
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: RemoteException) {
                throw e
            } catch (e: IOException) {
                throw RemoteException(RemoteFailure.UNREACHABLE, e.message ?: "the connection was lost", e)
            }
        }
    }

    // shortcut: needs a server that answers MLSD (exact UTC times); older servers are refused rather than guessed at, upgrade if one shows up.
    override suspend fun list(dir: String): List<RemoteEntry> = io {
        val files = client.mlistDir(absolute(dir)) ?: emptyArray()
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            throw RemoteException(RemoteFailure.FAILED, "the server cannot list folders with MLSD (${client.replyCode})")
        }
        files.filter { it.name != "." && it.name != ".." }.map { entry(join(dir, it.name.substringAfterLast('/')), it) }
    }

    override suspend fun stat(path: String): RemoteEntry? = io { statOrNull(path) }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = io {
        val found = statOrNull(path) ?: throw RemoteException(RemoteFailure.NOT_FOUND, "$path is not on the server")
        if (found.isDir || found.isLink) throw RemoteException(RemoteFailure.FAILED, "$path is not a plain file")
        if (found.size > maxBytes) throw RemoteException(RemoteFailure.TOO_LARGE, "$path is larger than $maxBytes bytes")
        val out = ByteArrayOutputStream()
        if (!client.retrieveFile(absolute(path), out)) throw RemoteException(RemoteFailure.FAILED, "could not read $path (${client.replyCode})")
        if (out.size() > maxBytes) throw RemoteException(RemoteFailure.TOO_LARGE, "$path grew past $maxBytes bytes while reading")
        out.toByteArray()
    }

    override suspend fun write(path: String, bytes: ByteArray) = io {
        if (statOrNull(path)?.isLink == true) throw RemoteException(RemoteFailure.FAILED, "$path is a link; not writing through it")
        if (!client.storeFile(absolute(path), ByteArrayInputStream(bytes))) {
            throw RemoteException(RemoteFailure.FAILED, "could not write $path (${client.replyCode})")
        }
    }

    override suspend fun mkdir(path: String) = io {
        if (!client.makeDirectory(absolute(path))) throw RemoteException(RemoteFailure.FAILED, "could not create $path (${client.replyCode})")
    }

    /** A folder must be empty: nothing here deletes a tree in one go. */
    override suspend fun delete(path: String) = io {
        val found = statOrNull(path) ?: throw RemoteException(RemoteFailure.NOT_FOUND, "$path is not on the server")
        val done = if (found.isDir) client.removeDirectory(absolute(path)) else client.deleteFile(absolute(path))
        if (!done) throw RemoteException(RemoteFailure.FAILED, "could not delete $path (${client.replyCode})")
    }

    override suspend fun rename(from: String, to: String) = io {
        if (!client.rename(absolute(from), absolute(to))) throw RemoteException(RemoteFailure.FAILED, "could not rename $from (${client.replyCode})")
    }

    override fun close() {
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }

    private fun statOrNull(path: String): RemoteEntry? {
        val f = client.mlistFile(absolute(path))
        if (f == null) {
            if (client.replyCode == 550) return null
            throw RemoteException(RemoteFailure.FAILED, "the server cannot describe $path (${client.replyCode})")
        }
        return entry(path.trim('/'), f)
    }

    // The server's own clock, as MLSD reports it in UTC.
    private fun entry(path: String, f: FTPFile) =
        RemoteEntry(path, f.isDirectory, f.size, f.timestamp?.timeInMillis ?: 0L, f.isSymbolicLink)

    private fun join(dir: String, name: String): String =
        listOf(dir.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")

    /** The full server path of [path], which is relative to the workspace folder. Never leaves that folder. */
    private fun absolute(path: String): String {
        if ('\u0000' in path || '\r' in path || '\n' in path) throw RemoteException(RemoteFailure.FAILED, "A path cannot hold a control character")
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." }) throw RemoteException(RemoteFailure.FAILED, "The path $path is not allowed")
        return (root.trimEnd('/') + "/" + parts.joinToString("/")).trimEnd('/').ifEmpty { "/" }
    }
}
