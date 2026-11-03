package ai.lily.app.remote

/** An in-memory server. Times come from a counter so every change is visible as a different time. */
class FakeRemoteFs(override val protocol: RemoteProtocol = RemoteProtocol.SFTP) : RemoteFs {
    class Node(var bytes: ByteArray?, var modified: Long)

    val nodes = linkedMapOf<String, Node>()
    val extraEntries = mutableMapOf<String, MutableList<RemoteEntry>>()
    val brokenFolders = mutableSetOf<String>()
    var clock = 1_000L
    var reads = 0
    var closed = false

    private fun tick(): Long {
        clock += 10
        return clock
    }

    fun putFile(path: String, text: String) {
        var dir = RemotePaths.parent(path)
        while (dir.isNotEmpty()) {
            if (dir !in nodes) nodes[dir] = Node(null, tick())
            dir = RemotePaths.parent(dir)
        }
        nodes[path] = Node(text.toByteArray(), tick())
    }

    fun textOf(path: String): String? = nodes[path]?.bytes?.toString(Charsets.UTF_8)

    private fun entry(path: String, node: Node) =
        RemoteEntry(path, node.bytes == null, node.bytes?.size?.toLong() ?: 0L, node.modified)

    override suspend fun list(dir: String): List<RemoteEntry> {
        if (dir in brokenFolders) throw RemoteException(RemoteFailure.FAILED, "listing failed")
        val real = nodes.filter { RemotePaths.parent(it.key) == dir }.map { entry(it.key, it.value) }
        return real + extraEntries[dir].orEmpty()
    }

    override suspend fun stat(path: String): RemoteEntry? = nodes[path]?.let { entry(path, it) }

    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        reads++
        val bytes = nodes[path]?.bytes ?: throw RemoteException(RemoteFailure.NOT_FOUND, "$path not found")
        if (bytes.size > maxBytes) throw RemoteException(RemoteFailure.TOO_LARGE, "$path is too large")
        return bytes
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        val parent = RemotePaths.parent(path)
        if (parent.isNotEmpty() && nodes[parent]?.bytes != null) throw RemoteException(RemoteFailure.FAILED, "$parent is a file")
        if (parent.isNotEmpty() && parent !in nodes) throw RemoteException(RemoteFailure.NOT_FOUND, "$parent not found")
        nodes[path] = Node(bytes, tick())
    }

    override suspend fun mkdir(path: String) {
        nodes[path] = Node(null, tick())
    }

    override suspend fun delete(path: String) {
        nodes.keys.removeAll { it == path || it.startsWith("$path/") }
    }

    override suspend fun rename(from: String, to: String) {
        val node = nodes.remove(from) ?: throw RemoteException(RemoteFailure.NOT_FOUND, "$from not found")
        nodes[to] = node
    }

    override fun close() {
        closed = true
    }
}

suspend fun failureOf(block: suspend () -> Unit): RemoteFailure? =
    try {
        block()
        null
    } catch (e: RemoteException) {
        e.failure
    }
