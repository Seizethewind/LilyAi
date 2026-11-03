package ai.lily.app.remote

/** How a remote workspace is reached. SFTP is the first choice, FTPS the second, plain FTP only as an informed last resort. */
enum class RemoteProtocol(val label: String, val encrypted: Boolean, val defaultPort: Int) {
    SFTP("SFTP", true, 22),
    FTPS("FTPS", true, 21),
    FTP("FTP", false, 21),
}

/**
 * One file or folder on the server. [path] is relative to the remote workspace folder, uses `/` and is empty for the folder
 * itself. [modified] is the server's own clock in milliseconds, never this phone's. A link is reported as a link and never followed.
 */
data class RemoteEntry(
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val isLink: Boolean = false,
)

enum class RemoteFailure {
    /** The server does not speak this protocol (no SSH service, no TLS). The only failure that allows trying the next protocol. */
    UNSUPPORTED,
    /** Timeout, no route, name not found. */
    UNREACHABLE,
    AUTH_FAILED,
    HOST_KEY_REJECTED,
    HOST_KEY_CHANGED,
    FTP_DECLINED,
    NOT_FOUND,
    TOO_LARGE,
    FAILED,
}

class RemoteException(val failure: RemoteFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A connected remote folder. Every path is relative to the remote workspace folder. Implementations must not follow links, must
 * report server times as they are, and must throw [RemoteException] (never return partial data) when something goes wrong.
 */
interface RemoteFs {
    val protocol: RemoteProtocol

    /** The direct children of [dir] (empty string for the workspace folder). */
    suspend fun list(dir: String): List<RemoteEntry>

    /** The entry at [path], or null when nothing is there. */
    suspend fun stat(path: String): RemoteEntry?

    /** The file's bytes. Throws [RemoteFailure.TOO_LARGE] without downloading when it is bigger than [maxBytes]. */
    suspend fun read(path: String, maxBytes: Long): ByteArray

    /** Writes the whole file, replacing what was there. The parent folder must exist. */
    suspend fun write(path: String, bytes: ByteArray)

    suspend fun mkdir(path: String)

    suspend fun delete(path: String)

    suspend fun rename(from: String, to: String)

    fun close()
}
