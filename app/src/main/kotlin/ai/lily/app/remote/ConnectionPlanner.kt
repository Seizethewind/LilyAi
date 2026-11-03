package ai.lily.app.remote

/** Opens one protocol. Real SFTP, FTPS and FTP clients implement this; the planner decides the order. */
interface RemoteConnector {
    /**
     * Throws [RemoteFailure.UNSUPPORTED] only when the server does not offer the protocol (connection refused on the port, no SSH
     * greeting, no TLS). A refused password, a bad key or a timeout are other failures. The server key (or TLS certificate) must be
     * checked through [hostKeys] before any password is sent, and certificate checking is never disabled.
     */
    suspend fun connect(profile: RemoteProfile, protocol: RemoteProtocol, password: String, hostKeys: HostKeyPolicy): RemoteFs
}

data class FailedAttempt(val protocol: RemoteProtocol, val reason: String)

/** What the person is told before plain FTP is used. */
data class FtpFallbackPrompt(val host: String, val attempts: List<FailedAttempt>) {
    fun message(): String = buildString {
        append("Could not connect to $host securely")
        if (attempts.isNotEmpty()) {
            append(" (")
            append(attempts.joinToString("; ") { "${it.protocol.label}: ${it.reason}" })
            append(")")
        }
        append(". Plain FTP is not encrypted: your password, your files and every change you save can be read by anyone on the network ")
        append("between you and the server. Your password will not be saved. Continue with FTP?")
    }
}

/** A connected workspace and how it was reached. */
data class RemoteSession(val fs: RemoteFs, val protocol: RemoteProtocol, val attempts: List<FailedAttempt>) {
    val encrypted: Boolean get() = protocol.encrypted

    /** Not null for plain FTP: the screen keeps this visible for as long as the connection is open. */
    val banner: String?
        get() = if (encrypted) null else "Unencrypted FTP connection: others on the network can read your files and password."

    val mayStorePassword: Boolean get() = CredentialPolicy.mayStorePassword(protocol)
}

/**
 * SFTP first, then FTPS, then plain FTP only when the profile allows it and the person says yes to the warning, every time.
 * Only "the server does not offer this protocol" moves on to the next one. A wrong password, a changed host key or a
 * timeout stops everything: falling back after those would send the password somewhere less safe.
 */
class ConnectionPlanner(
    private val connector: RemoteConnector,
    private val confirmFtp: suspend (FtpFallbackPrompt) -> Boolean,
) {
    private fun order(profile: RemoteProfile): List<RemoteProtocol> = buildList {
        add(RemoteProtocol.SFTP)
        if (profile.tryFtps) add(RemoteProtocol.FTPS)
        if (profile.allowFtpFallback) add(RemoteProtocol.FTP)
    }

    suspend fun open(profile: RemoteProfile, password: String, hostKeys: HostKeyPolicy): RemoteSession {
        val failed = mutableListOf<FailedAttempt>()
        for (protocol in order(profile)) {
            if (protocol == RemoteProtocol.FTP && !confirmFtp(FtpFallbackPrompt(profile.host, failed.toList()))) {
                throw RemoteException(RemoteFailure.FTP_DECLINED, "Plain FTP was not used")
            }
            try {
                val fs = connector.connect(profile, protocol, password, hostKeys)
                if (fs.protocol != protocol) {
                    fs.close()
                    throw RemoteException(RemoteFailure.FAILED, "The connection is not ${protocol.label}")
                }
                return RemoteSession(fs, protocol, failed.toList())
            } catch (e: RemoteException) {
                if (e.failure != RemoteFailure.UNSUPPORTED) throw e
                failed += FailedAttempt(protocol, e.message ?: "not available")
            }
        }
        val tried = failed.joinToString(", ") { it.protocol.label }
        val hint = if (profile.allowFtpFallback) "" else " Plain FTP is switched off for this connection."
        throw RemoteException(RemoteFailure.UNSUPPORTED, "${profile.host} does not offer a secure connection (tried $tried).$hint")
    }
}
