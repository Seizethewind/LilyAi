package ai.lily.app.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPlannerTest {
    private class ScriptedConnector(private val script: Map<RemoteProtocol, () -> RemoteFs>) : RemoteConnector {
        val tried = mutableListOf<RemoteProtocol>()

        override suspend fun connect(
            profile: RemoteProfile,
            protocol: RemoteProtocol,
            password: String,
            hostKeys: HostKeyPolicy,
        ): RemoteFs {
            tried += protocol
            return script.getValue(protocol)()
        }
    }

    private fun fail(failure: RemoteFailure, message: String = failure.name): () -> RemoteFs = { throw RemoteException(failure, message) }

    private fun ok(fs: RemoteFs): () -> RemoteFs = { fs }

    private val hostKeys = HostKeyPolicy(InMemoryHostKeyStore()) { true }
    private val profile = RemoteProfile(id = "p1", name = "Site", host = "example.com", user = "me")
    private val prompts = mutableListOf<FtpFallbackPrompt>()
    private var ftpAnswer = true

    private fun planner(connector: RemoteConnector) = ConnectionPlanner(connector) {
        prompts += it
        ftpAnswer
    }

    @Test
    fun sftpIsTriedFirstAndNothingElseWhenItWorks() = runBlocking<Unit> {
        val fs = FakeRemoteFs(RemoteProtocol.SFTP)
        val connector = ScriptedConnector(mapOf(RemoteProtocol.SFTP to ok(fs)))

        val session = planner(connector).open(profile, "pw", hostKeys)

        assertSame(fs, session.fs)
        assertEquals(listOf(RemoteProtocol.SFTP), connector.tried)
        assertTrue(session.encrypted)
        assertNull(session.banner)
        assertTrue(session.mayStorePassword)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun ftpsIsUsedWhenTheServerHasNoSftp() = runBlocking<Unit> {
        val connector = ScriptedConnector(
            mapOf(
                RemoteProtocol.SFTP to fail(RemoteFailure.UNSUPPORTED, "no SSH service"),
                RemoteProtocol.FTPS to ok(FakeRemoteFs(RemoteProtocol.FTPS)),
            ),
        )

        val session = planner(connector).open(profile, "pw", hostKeys)

        assertEquals(RemoteProtocol.FTPS, session.protocol)
        assertEquals(listOf(FailedAttempt(RemoteProtocol.SFTP, "no SSH service")), session.attempts)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun plainFtpIsNeverTriedWhenTheProfileDoesNotAllowIt() = runBlocking<Unit> {
        val connector = ScriptedConnector(
            mapOf(
                RemoteProtocol.SFTP to fail(RemoteFailure.UNSUPPORTED),
                RemoteProtocol.FTPS to fail(RemoteFailure.UNSUPPORTED),
            ),
        )

        val failure = failureOf { planner(connector).open(profile, "pw", hostKeys) }

        assertEquals(RemoteFailure.UNSUPPORTED, failure)
        assertEquals(listOf(RemoteProtocol.SFTP, RemoteProtocol.FTPS), connector.tried)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun plainFtpNeedsTheWarningToBeShownAndAccepted() = runBlocking<Unit> {
        val connector = ScriptedConnector(
            mapOf(
                RemoteProtocol.SFTP to fail(RemoteFailure.UNSUPPORTED, "connection refused"),
                RemoteProtocol.FTPS to fail(RemoteFailure.UNSUPPORTED, "no TLS"),
                RemoteProtocol.FTP to ok(FakeRemoteFs(RemoteProtocol.FTP)),
            ),
        )

        val session = planner(connector).open(profile.copy(allowFtpFallback = true), "pw", hostKeys)

        assertEquals(RemoteProtocol.FTP, session.protocol)
        assertEquals(1, prompts.size)
        val text = prompts.single().message()
        assertTrue(text.contains("example.com"))
        assertTrue(text.contains("SFTP: connection refused"))
        assertTrue(text.contains("FTPS: no TLS"))
        assertTrue(text.contains("not encrypted"))
        assertNotNull(session.banner)
        assertFalse(session.encrypted)
        assertFalse(session.mayStorePassword)
    }

    @Test
    fun declininkingTheWarningMeansPlainFtpIsNotTried() = runBlocking<Unit> {
        ftpAnswer = false
        val connector = ScriptedConnector(
            mapOf(
                RemoteProtocol.SFTP to fail(RemoteFailure.UNSUPPORTED),
                RemoteProtocol.FTPS to fail(RemoteFailure.UNSUPPORTED),
                RemoteProtocol.FTP to ok(FakeRemoteFs(RemoteProtocol.FTP)),
            ),
        )

        val failure = failureOf { planner(connector).open(profile.copy(allowFtpFallback = true), "pw", hostKeys) }

        assertEquals(RemoteFailure.FTP_DECLINED, failure)
        assertFalse(RemoteProtocol.FTP in connector.tried)
    }

    @Test
    fun theWarningIsShownEveryTime() = runBlocking<Unit> {
        val connector = ScriptedConnector(
            mapOf(
                RemoteProtocol.SFTP to fail(RemoteFailure.UNSUPPORTED),
                RemoteProtocol.FTPS to fail(RemoteFailure.UNSUPPORTED),
                RemoteProtocol.FTP to ok(FakeRemoteFs(RemoteProtocol.FTP)),
            ),
        )
        val p = planner(connector)

        p.open(profile.copy(allowFtpFallback = true), "pw", hostKeys)
        p.open(profile.copy(allowFtpFallback = true), "pw", hostKeys)

        assertEquals(2, prompts.size)
    }

    @Test
    fun ftpsCanBeSkippedByTheProfile() = runBlocking<Unit> {
        val connector = ScriptedConnector(
            mapOf(
                RemoteProtocol.SFTP to fail(RemoteFailure.UNSUPPORTED),
                RemoteProtocol.FTP to ok(FakeRemoteFs(RemoteProtocol.FTP)),
            ),
        )

        planner(connector).open(profile.copy(tryFtps = false, allowFtpFallback = true), "pw", hostKeys)

        assertEquals(listOf(RemoteProtocol.SFTP, RemoteProtocol.FTP), connector.tried)
    }

    @Test
    fun aRefusedPasswordNeverFallsBackToAWeakerProtocol() = runBlocking<Unit> {
        val connector = ScriptedConnector(
            mapOf(
                RemoteProtocol.SFTP to fail(RemoteFailure.AUTH_FAILED),
                RemoteProtocol.FTPS to ok(FakeRemoteFs(RemoteProtocol.FTPS)),
                RemoteProtocol.FTP to ok(FakeRemoteFs(RemoteProtocol.FTP)),
            ),
        )

        val failure = failureOf { planner(connector).open(profile.copy(allowFtpFallback = true), "pw", hostKeys) }

        assertEquals(RemoteFailure.AUTH_FAILED, failure)
        assertEquals(listOf(RemoteProtocol.SFTP), connector.tried)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun aChangedHostKeyAndAnUnreachableServerAlsoStopEverything() = runBlocking<Unit> {
        for (failure in listOf(RemoteFailure.HOST_KEY_CHANGED, RemoteFailure.HOST_KEY_REJECTED, RemoteFailure.UNREACHABLE)) {
            val connector = ScriptedConnector(
                mapOf(
                    RemoteProtocol.SFTP to fail(failure),
                    RemoteProtocol.FTPS to ok(FakeRemoteFs(RemoteProtocol.FTPS)),
                    RemoteProtocol.FTP to ok(FakeRemoteFs(RemoteProtocol.FTP)),
                ),
            )

            assertEquals(failure, failureOf { planner(connector).open(profile.copy(allowFtpFallback = true), "pw", hostKeys) })
            assertEquals(listOf(RemoteProtocol.SFTP), connector.tried)
        }
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun aConnectionThatIsNotTheProtocolAskedForIsRefusedAndClosed() = runBlocking<Unit> {
        val liar = FakeRemoteFs(RemoteProtocol.FTP)
        val connector = ScriptedConnector(mapOf(RemoteProtocol.SFTP to ok(liar)))

        val failure = failureOf { planner(connector).open(profile, "pw", hostKeys) }

        assertEquals(RemoteFailure.FAILED, failure)
        assertTrue(liar.closed)
    }
}
