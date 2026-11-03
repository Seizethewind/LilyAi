package ai.lily.app.remote

import java.io.File

/** One open remote workspace: how it was reached, the local copy the editor works on, and the folder that holds its state. */
class ActiveRemote(
    val profile: RemoteProfile,
    val session: RemoteSession,
    val mirror: RemoteMirror,
    val localRoot: File,
    val dir: File,
)

/**
 * The connection that is open right now. The editor screen is rebuilt when the person connects or disconnects, so the open
 * connection is kept here for as long as the app process lives. After the process dies there is no connection: the editor opens
 * the local workspace and the person connects again.
 */
object RemoteHub {
    @Volatile
    var active: ActiveRemote? = null
        private set

    private var notice: String? = null

    @Synchronized
    fun open(remote: ActiveRemote, message: String?) {
        active?.session?.fs?.close()
        active = remote
        notice = message
    }

    @Synchronized
    fun close() {
        active?.session?.fs?.close()
        active = null
        notice = null
    }

    /** A sentence for the person that must outlive the screen being rebuilt. Returned once. */
    @Synchronized
    fun takeNotice(): String? = notice.also { notice = null }
}

/** Makes [path] and every folder above it that is missing. */
suspend fun RemoteFs.mkdirs(path: String) {
    var prefix = ""
    for (part in path.split('/').filter { it.isNotEmpty() }) {
        prefix = if (prefix.isEmpty()) part else "$prefix/$part"
        val found = stat(prefix)
        if (found == null) {
            mkdir(prefix)
        } else if (!found.isDir) {
            throw RemoteException(RemoteFailure.FAILED, "$prefix is a file on the server")
        }
    }
}

/** Deletes a file, or a folder with everything in it. A link is deleted as a link and never followed. */
suspend fun RemoteFs.deleteTree(path: String) {
    val found = stat(path) ?: return
    if (found.isDir && !found.isLink) {
        for (child in list(path)) deleteTree(child.path)
    }
    delete(path)
}
