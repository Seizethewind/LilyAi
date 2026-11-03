package ai.lily.app.editor

import ai.lily.app.remote.ActiveRemote
import ai.lily.app.remote.ConnectionPlanner
import ai.lily.app.remote.DefaultRemoteConnector
import ai.lily.app.remote.FtpFallbackPrompt
import ai.lily.app.remote.HostKeyPolicy
import ai.lily.app.remote.HostKeyPrompt
import ai.lily.app.remote.PullReport
import ai.lily.app.remote.PushResult
import ai.lily.app.remote.RemoteConnector
import ai.lily.app.remote.RemoteException
import ai.lily.app.remote.RemoteFailure
import ai.lily.app.remote.RemoteFs
import ai.lily.app.remote.RemoteHub
import ai.lily.app.remote.RemoteMirror
import ai.lily.app.remote.RemoteProfile
import ai.lily.app.remote.RemoteProfileCheck
import ai.lily.app.remote.RemoteProtocol
import ai.lily.app.remote.RemoteSession
import ai.lily.app.remote.RemoteStore
import ai.lily.app.remote.deleteTree
import ai.lily.app.remote.mkdirs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** A yes or no the person is being asked, with the thing they are asked about. */
class Ask<T>(val value: T, val answer: CompletableDeferred<Boolean>)

class PasswordAnswer(val password: String, val remember: Boolean)

/** [ftp] is true when the password is wanted for plain FTP, which is asked every time and never saved. */
class PasswordAsk(val profile: RemoteProfile, val ftp: Boolean, val answer: CompletableDeferred<PasswordAnswer?>)

/**
 * Everything the remote workspace screens show and do: saved connections, connecting, the questions the person must answer on the
 * way (server key, plain FTP, password), pulling, pushing saved files, and passing folder changes on to the server.
 * [onSessionChanged] is called after connecting or disconnecting so the editor can rebuild itself on the right folder.
 */
class RemoteUi(
    private val store: RemoteStore,
    private val scope: CoroutineScope,
    private val say: (String) -> Unit,
    private val onSessionChanged: () -> Unit,
) {
    var manager by mutableStateOf(false)
    var editing by mutableStateOf<RemoteProfile?>(null)
    var busy by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(RemoteHub.takeNotice())
    var profiles by mutableStateOf(store.profiles())
        private set
    var hostKeyAsk by mutableStateOf<Ask<HostKeyPrompt>?>(null)
        private set
    var ftpAsk by mutableStateOf<Ask<FtpFallbackPrompt>?>(null)
        private set
    var passwordAsk by mutableStateOf<PasswordAsk?>(null)
        private set
    var conflict by mutableStateOf<PushResult.Conflict?>(null)

    val active: ActiveRemote? get() = RemoteHub.active

    // ---- Saved connections ----

    fun newProfile() {
        editing = RemoteProfile(id = UUID.randomUUID().toString(), name = "", host = "", user = "")
    }

    fun saveProfile(profile: RemoteProfile) {
        store.save(profile)
        profiles = store.profiles()
        editing = null
    }

    fun deleteProfile(profile: RemoteProfile) {
        if (active?.profile?.id == profile.id) {
            say("Disconnect from ${profile.name} before deleting it")
            return
        }
        store.delete(profile.id)
        profiles = store.profiles()
    }

    // ---- Connecting ----

    fun connect(profile: RemoteProfile) {
        scope.launch {
            val saved = store.password(profile.id)
            val answer = if (saved != null) PasswordAnswer(saved, true) else askPassword(profile, ftp = false) ?: return@launch
            run(profile, answer, fromStore = saved != null)
        }
    }

    private suspend fun run(profile: RemoteProfile, typed: PasswordAnswer, fromStore: Boolean) {
        val problems = RemoteProfileCheck.problems(profile)
        if (problems.isNotEmpty()) {
            notice = problems.joinToString("\n")
            return
        }
        busy = "Connecting to ${profile.name}\u2026"
        val real = DefaultRemoteConnector()
        // A saved password was kept for encrypted protocols only, so it is never sent over plain FTP: it is asked for again.
        val guarded = object : RemoteConnector {
            override suspend fun connect(profile: RemoteProfile, protocol: RemoteProtocol, password: String, hostKeys: HostKeyPolicy): RemoteFs {
                val use = if (protocol == RemoteProtocol.FTP && fromStore) {
                    askPassword(profile, ftp = true)?.password
                        ?: throw RemoteException(RemoteFailure.FTP_DECLINED, "No password was given for plain FTP")
                } else {
                    password
                }
                return real.connect(profile, protocol, use, hostKeys)
            }
        }
        try {
            val planner = ConnectionPlanner(guarded) { prompt -> askFtp(prompt) }
            val session = planner.open(profile, typed.password, HostKeyPolicy(store.hostKeys) { prompt -> askHostKey(prompt) })
            if (session.mayStorePassword && typed.remember) store.savePassword(profile.id, typed.password)

            busy = "Downloading files\u2026"
            val dir = store.dirFor(profile.id)
            val local = File(dir, "files").apply { mkdirs() }
            val mirror = RemoteMirror(session.fs, local, File(dir, "manifest.json"))
            val report = mirror.pull()
            RemoteHub.open(ActiveRemote(profile, session, mirror, local, dir), summary(report, session))
            manager = false
            onSessionChanged()
        } catch (e: RemoteException) {
            if (e.failure == RemoteFailure.AUTH_FAILED) store.clearPassword(profile.id)
            notice = e.message ?: "The connection failed"
        } finally {
            busy = null
        }
    }

    fun disconnect() {
        RemoteHub.close()
        onSessionChanged()
    }

    fun pull() {
        val a = active ?: return
        scope.launch {
            busy = "Checking the server\u2026"
            try {
                notice = summary(a.mirror.pull(), a.session) + " Reopen a file to see its new text."
            } catch (e: RemoteException) {
                notice = e.message ?: "Could not read from the server"
            } finally {
                busy = null
            }
        }
    }

    private fun summary(r: PullReport, s: RemoteSession): String = buildString {
        append("Connected over ${s.protocol.label}. ")
        append("${r.downloaded.size} downloaded, ${r.unchanged} up to date")
        if (r.removed.isNotEmpty()) append(", ${r.removed.size} removed")
        append('.')
        if (r.localKept.isNotEmpty()) {
            append(" Kept your version of ${r.localKept.size} file(s) that the server also changed: ${r.localKept.take(5).joinToString()}.")
        }
        if (r.skipped.isNotEmpty()) append(" ${r.skipped.size} item(s) skipped (links, big files or limits).")
        if (!r.complete) append(" The download was cut short, so nothing was deleted on this phone.")
    }

    // ---- Questions on the way ----

    private suspend fun askHostKey(prompt: HostKeyPrompt): Boolean {
        val ask = Ask(prompt, CompletableDeferred<Boolean>())
        hostKeyAsk = ask
        return try {
            ask.answer.await()
        } finally {
            if (hostKeyAsk === ask) hostKeyAsk = null
        }
    }

    private suspend fun askFtp(prompt: FtpFallbackPrompt): Boolean {
        val ask = Ask(prompt, CompletableDeferred<Boolean>())
        ftpAsk = ask
        return try {
            ask.answer.await()
        } finally {
            if (ftpAsk === ask) ftpAsk = null
        }
    }

    private suspend fun askPassword(profile: RemoteProfile, ftp: Boolean): PasswordAnswer? {
        val ask = PasswordAsk(profile, ftp, CompletableDeferred())
        passwordAsk = ask
        return try {
            ask.answer.await()
        } finally {
            if (passwordAsk === ask) passwordAsk = null
        }
    }

    // ---- Sending changes to the server ----

    /** Sends one saved file. A server copy that changed behind the phone's back opens the conflict question instead. */
    suspend fun pushNow(path: String, force: Boolean = false) {
        val a = active ?: return
        when (val result = a.mirror.push(path, force)) {
            is PushResult.Uploaded -> say("Sent ${result.path} to the server")
            is PushResult.Conflict -> conflict = result
            is PushResult.Failed -> say("Could not send ${result.path}: ${result.message}")
        }
    }

    fun keepMine(path: String) {
        conflict = null
        scope.launch { pushNow(path, force = true) }
    }

    fun takeServers(path: String) {
        conflict = null
        val a = active ?: return
        scope.launch {
            val problem = a.mirror.takeRemote(path)
            say(problem ?: "Took the server's version of $path. Reopen the file to see it.")
        }
    }

    /** Sends every file the person saves while connected. Checks the open tabs every moment or so. */
    fun watchSaves(state: EditorState) {
        scope.launch {
            val seen = HashMap<String, String>()
            while (isActive) {
                for (tab in state.tabs.toList()) {
                    val before = seen.put(tab.path, tab.savedText)
                    if (before != null && before != tab.savedText) pushNow(tab.path)
                }
                delay(800)
            }
        }
    }

    /** Passes new folders, renames, deletes and replaced files on to the server. The phone's copy has already changed. */
    suspend fun onChange(change: WorkspaceChange) {
        val a = active ?: return
        val fs = a.session.fs
        try {
            when (change) {
                is WorkspaceChange.NewFolder -> fs.mkdirs(change.path)
                is WorkspaceChange.Renamed -> {
                    fs.mkdirs(change.to.substringBeforeLast('/', ""))
                    fs.rename(change.from, change.to)
                    a.mirror.pull()
                }
                is WorkspaceChange.Deleted -> {
                    fs.deleteTree(change.path)
                    a.mirror.pull()
                }
                is WorkspaceChange.Written -> pushNow(change.path)
            }
        } catch (e: RemoteException) {
            say("The server did not accept that change (${e.message}). It is changed on this phone only.")
        }
    }
}
