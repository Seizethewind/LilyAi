package ai.lily.app.plugins

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Asks the person, on screen, whether one plugin command may run. [pending] is what the dialog shows; [confirm] waits
 * for the answer. Only one question is open at a time: a second one waits until the first is answered.
 * Closing the dialog any other way counts as "no".
 */
class PromptGate : ConfirmationGate {
    class Pending(val request: ConfirmRequest, val answer: CompletableDeferred<Boolean>)

    var pending by mutableStateOf<Pending?>(null)
        private set

    private val one = Mutex()

    override suspend fun confirm(request: ConfirmRequest): Boolean = one.withLock {
        val ask = Pending(request, CompletableDeferred())
        pending = ask
        try {
            ask.answer.await()
        } finally {
            if (pending === ask) pending = null
        }
    }

    fun answer(approved: Boolean) {
        pending?.answer?.complete(approved)
    }
}

/**
 * The plugin pieces the editor screen needs, built once from the app context: the folder of installed plugins, the
 * registry, the bridge to other apps, the on-screen approval, and the host that hands plugin tools to the workers.
 * [version] goes up whenever the person changes anything, so screens redraw and Lily is rebuilt to see new tools.
 */
class PluginServices(context: Context) {
    private val app = context.applicationContext

    val gate = PromptGate()
    val registry = PluginRegistry(DiskPluginStore(File(app.filesDir, "plugins")))
    val host = PluginHost(registry, AndroidServiceBridge(app), gate)

    var version by mutableIntStateOf(0)
        private set

    /**
     * Installs the manifests that ship in assets/plugins, once each. They arrive turned off with nothing allowed. A
     * plugin the person later removes is not put back, because the ids already offered are remembered.
     */
    fun installBundled() {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val offered = prefs.getStringSet(OFFERED, emptySet()).orEmpty().toMutableSet()
        val names = runCatching { app.assets.list("plugins") }.getOrNull().orEmpty()
        var changed = false
        for (name in names.filter { it.endsWith(".json") }) {
            val text = runCatching { app.assets.open("plugins/$name").bufferedReader().use { it.readText() } }.getOrNull() ?: continue
            val manifest = (ManifestParser.parse(text) as? ParseResult.Ok)?.manifest ?: continue
            if (manifest.id in offered) continue
            offered += manifest.id
            if (registry.get(manifest.id) == null) {
                registry.install(text)
                changed = true
            }
        }
        prefs.edit().putStringSet(OFFERED, offered).apply()
        if (changed) version++
    }

    fun install(manifestText: String): InstallResult = registry.install(manifestText).also { version++ }

    fun uninstall(id: String) {
        registry.uninstall(id)
        version++
    }

    fun setEnabled(id: String, on: Boolean) {
        registry.setEnabled(id, on)
        version++
    }

    fun setGranted(id: String, permissionKey: String, on: Boolean) {
        if (on) registry.grant(id, permissionKey) else registry.revoke(id, permissionKey)
        version++
    }

    fun setAutoApprove(id: String, toolName: String, on: Boolean) {
        registry.setAutoApprove(id, toolName, on)
        version++
    }

    private companion object {
        const val PREFS = "lily_plugins"
        const val OFFERED = "bundled_offered"
    }
}
