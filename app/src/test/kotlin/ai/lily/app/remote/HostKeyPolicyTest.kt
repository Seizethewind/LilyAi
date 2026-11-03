package ai.lily.app.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyPolicyTest {
    private val prompts = mutableListOf<HostKeyPrompt>()
    private var answer = true
    private val store = InMemoryHostKeyStore()
    private val policy = HostKeyPolicy(store) {
        prompts += it
        answer
    }

    @Test
    fun aNewKeyIsShownThenRememberedSoTheNextVisitIsSilent() = runBlocking<Unit> {
        policy.verify("example.com", 22, "ssh-ed25519", "SHA256:abc")
        policy.verify("example.com", 22, "ssh-ed25519", "SHA256:abc")

        assertEquals(1, prompts.size)
        assertEquals("SHA256:abc", prompts.single().fingerprint)
        assertFalse(prompts.single().knownOtherKeys)
    }

    @Test
    fun aKeyThePersonRefusesIsNotStored() = runBlocking<Unit> {
        answer = false

        assertEquals(RemoteFailure.HOST_KEY_REJECTED, failureOf { policy.verify("example.com", 22, "ssh-ed25519", "SHA256:abc") })
        assertFalse(store.knowsHost("example.com", 22))
    }

    @Test
    fun aChangedKeyFailsWithoutBeingOfferedForAcceptance() = runBlocking<Unit> {
        policy.verify("example.com", 22, "ssh-ed25519", "SHA256:abc")
        prompts.clear()

        assertEquals(RemoteFailure.HOST_KEY_CHANGED, failureOf { policy.verify("example.com", 22, "ssh-ed25519", "SHA256:other") })
        assertTrue(prompts.isEmpty())
        assertEquals("SHA256:abc", store.get("example.com", 22, "ssh-ed25519"))
    }

    @Test
    fun aDifferentKeyTypeForAKnownHostIsAskedAboutAndFlagged() = runBlocking<Unit> {
        policy.verify("example.com", 22, "ssh-ed25519", "SHA256:abc")
        prompts.clear()

        policy.verify("example.com", 22, "ssh-rsa", "SHA256:rsa")

        assertTrue(prompts.single().knownOtherKeys)
    }

    @Test
    fun hostNamesIgnoreCaseButPortsAreSeparate() = runBlocking<Unit> {
        policy.verify("Example.com", 22, "ssh-ed25519", "SHA256:abc")
        policy.verify("example.COM", 22, "ssh-ed25519", "SHA256:abc")
        assertEquals(1, prompts.size)

        policy.verify("example.com", 2222, "ssh-ed25519", "SHA256:zzz")
        assertEquals(2, prompts.size)
    }

    @Test
    fun forgettingAHostMakesItNewAgain() = runBlocking<Unit> {
        policy.verify("example.com", 22, "ssh-ed25519", "SHA256:abc")
        store.forget("example.com", 22)
        prompts.clear()

        policy.verify("example.com", 22, "ssh-ed25519", "SHA256:new")

        assertEquals(1, prompts.size)
        assertEquals("SHA256:new", store.get("example.com", 22, "ssh-ed25519"))
    }
}
