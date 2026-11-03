package ai.lily.app.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProfileTest {
    private val good = RemoteProfile(id = "1", name = "Site", host = "example.com", user = "me", rootPath = "/var/www")

    @Test
    fun aGoodProfileHasNoProblems() {
        assertTrue(RemoteProfileCheck.problems(good).isEmpty())
    }

    @Test
    fun problemsAreSaidInWords() {
        val bad = good.copy(name = " ", host = "me@example.com/x", user = "", sftpPort = 0, ftpPort = 70000, rootPath = "www/../etc")

        assertEquals(7, RemoteProfileCheck.problems(bad).size)
    }

    @Test
    fun ftpFallbackIsOffUnlessAskedFor() {
        assertFalse(good.allowFtpFallback)
        assertTrue(good.tryFtps)
    }

    @Test
    fun portsDependOnTheProtocol() {
        val p = good.copy(sftpPort = 2222, ftpPort = 2121)
        assertEquals(2222, p.portFor(RemoteProtocol.SFTP))
        assertEquals(2121, p.portFor(RemoteProtocol.FTPS))
        assertEquals(2121, p.portFor(RemoteProtocol.FTP))
    }

    @Test
    fun onlyEncryptedProtocolsMayHaveAStoredPassword() {
        assertTrue(CredentialPolicy.mayStorePassword(RemoteProtocol.SFTP))
        assertTrue(CredentialPolicy.mayStorePassword(RemoteProtocol.FTPS))
        assertFalse(CredentialPolicy.mayStorePassword(RemoteProtocol.FTP))
    }
}
