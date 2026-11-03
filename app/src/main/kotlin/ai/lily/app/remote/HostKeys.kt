package ai.lily.app.remote

/** Remembers the fingerprint of each server key the person has accepted, per host, port and key type. */
interface HostKeyStore {
    fun get(host: String, port: Int, keyType: String): String?
    fun put(host: String, port: Int, keyType: String, fingerprint: String)

    /** True when any key of this host and port has been accepted before. */
    fun knowsHost(host: String, port: Int): Boolean

    /** Forgets every key of this host and port. Only for when the person has been told the key changed and chooses to trust the new one. */
    fun forget(host: String, port: Int)
}

class InMemoryHostKeyStore : HostKeyStore {
    private val keys = linkedMapOf<String, String>()

    private fun hostPrefix(host: String, port: Int) = "${host.lowercase()}:$port/"

    override fun get(host: String, port: Int, keyType: String): String? = keys[hostPrefix(host, port) + keyType]

    override fun put(host: String, port: Int, keyType: String, fingerprint: String) {
        keys[hostPrefix(host, port) + keyType] = fingerprint
    }

    override fun knowsHost(host: String, port: Int): Boolean = keys.keys.any { it.startsWith(hostPrefix(host, port)) }

    override fun forget(host: String, port: Int) {
        keys.keys.removeAll { it.startsWith(hostPrefix(host, port)) }
    }
}

/** What the person is shown the first time a server's key is seen. [knownOtherKeys] is true when the host is known under a different key type. */
data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val knownOtherKeys: Boolean,
)

/**
 * Trust on first use, with the person deciding. A new key is shown and must be accepted. A key that differs from the one
 * accepted before always fails: it is never offered for acceptance here, because it may be someone in the middle. Host key
 * checking is never switched off.
 */
class HostKeyPolicy(
    private val store: HostKeyStore,
    private val confirmNew: suspend (HostKeyPrompt) -> Boolean,
) {
    suspend fun verify(host: String, port: Int, keyType: String, fingerprint: String) {
        val known = store.get(host, port, keyType)
        when {
            known == null -> {
                val prompt = HostKeyPrompt(host, port, keyType, fingerprint, store.knowsHost(host, port))
                if (!confirmNew(prompt)) {
                    throw RemoteException(RemoteFailure.HOST_KEY_REJECTED, "The server key for $host was not accepted")
                }
                store.put(host, port, keyType, fingerprint)
            }
            known == fingerprint -> Unit
            else -> throw RemoteException(
                RemoteFailure.HOST_KEY_CHANGED,
                "The $keyType key of $host has changed since you accepted it. Someone may be intercepting the connection. " +
                    "Nothing was sent.",
            )
        }
    }
}
