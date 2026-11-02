package ai.lily.app.sandbox

import java.io.File

/** Test doubles for the sandbox and the workspace. */
class FakeSandbox : SandboxRunner {
    val created = ArrayList<String>()
    val killed = ArrayList<String>()
    val commands = ArrayList<String>()
    var createError: Exception? = null
    var onExec: (String) -> ExecResult = { ExecResult("", "", 0) }

    override suspend fun createSandbox(ttlSeconds: Int): String {
        createError?.let { throw it }
        created += "sb1"
        return "sb1"
    }

    override suspend fun exec(sandboxId: String, command: String): ExecResult {
        commands += command
        return onExec(command)
    }

    override suspend fun kill(sandboxId: String) {
        killed += sandboxId
    }
}

class FakeVerifyFiles(private val files: Map<String, ByteArray>) : VerifyFiles {
    constructor(vararg texts: Pair<String, String>) : this(texts.associate { it.first to it.second.toByteArray(Charsets.UTF_8) })

    override suspend fun list(): List<VerifyFile> = files.map { VerifyFile(it.key, it.value.size.toLong()) }

    override suspend fun read(path: String): ByteArray? = files[path]
}

/** Writes a file (and its folders) under [root]. */
fun putFile(root: File, rel: String, bytes: ByteArray) {
    val f = File(root, rel)
    f.parentFile.mkdirs()
    f.writeBytes(bytes)
}
