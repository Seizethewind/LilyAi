package ai.lily.app.remote

/** The real [RemoteConnector]: JSch for SFTP, Commons Net for FTPS and (only after the planner's warning) plain FTP. */
class DefaultRemoteConnector : RemoteConnector {
    override suspend fun connect(profile: RemoteProfile, protocol: RemoteProtocol, password: String, hostKeys: HostKeyPolicy): RemoteFs =
        when (protocol) {
            RemoteProtocol.SFTP -> SftpConnector.connect(profile, password, hostKeys)
            RemoteProtocol.FTPS, RemoteProtocol.FTP -> FtpConnector.connect(profile, protocol, password)
        }
}
