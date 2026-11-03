package ai.lily.app.remote

/**
 * A saved remote workspace. It holds no password: passwords live in the encrypted store and only for encrypted protocols.
 * [allowFtpFallback] is off by default; even when on, plain FTP is used only after the person is told and says yes.
 */
data class RemoteProfile(
    val id: String,
    val name: String,
    val host: String,
    val user: String,
    val sftpPort: Int = RemoteProtocol.SFTP.defaultPort,
    val ftpPort: Int = RemoteProtocol.FTP.defaultPort,
    val rootPath: String = "/",
    val tryFtps: Boolean = true,
    val allowFtpFallback: Boolean = false,
) {
    fun portFor(protocol: RemoteProtocol): Int = if (protocol == RemoteProtocol.SFTP) sftpPort else ftpPort
}

object RemoteProfileCheck {
    /** What is wrong with a profile, in words for the person; empty when it can be used. */
    fun problems(p: RemoteProfile): List<String> {
        val out = mutableListOf<String>()
        if (p.name.isBlank()) out += "Give the connection a name"
        val host = p.host.trim()
        if (host.isEmpty()) {
            out += "Enter the server address"
        } else if (host.any { it.isWhitespace() || it == '/' || it == '@' || it == '\\' }) {
            out += "The server address should be a host name only, such as example.com"
        }
        if (p.user.isBlank()) out += "Enter the user name"
        if (p.sftpPort !in 1..65535) out += "The SFTP port must be between 1 and 65535"
        if (p.ftpPort !in 1..65535) out += "The FTP port must be between 1 and 65535"
        if (!p.rootPath.startsWith("/")) out += "The remote folder must start with /"
        if (p.rootPath.split('/').any { it == ".." }) out += "The remote folder cannot contain .."
        return out
    }
}

object CredentialPolicy {
    /** A password is kept only for an encrypted protocol. A plain FTP password is asked for every time and never saved. */
    fun mayStorePassword(protocol: RemoteProtocol): Boolean = protocol.encrypted
}
