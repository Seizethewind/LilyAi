package ai.lily.app.plugins

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Starts a service in another app and, if the plugin asked for an answer, waits for it. This class knows nothing about
 * any particular app: package, class, action, extras and answer keys all come from the plugin's manifest.
 *
 * Android facts this relies on (not verifiable in the build sandbox):
 * - Starting another app's service is refused while Lily is in the background; the caller gets NOT_ALLOWED.
 * - The other app must be visible to Lily (a <queries> entry in AndroidManifest.xml), or Android hides it.
 * - The PendingIntent for the answer must be mutable: the other app adds the result to it. An immutable one is
 *   delivered with no result at all.
 */
class AndroidServiceBridge(context: Context) : ServiceBridge {
    private val app = context.applicationContext

    override suspend fun call(call: ServiceCall): ServiceReply {
        if (!isInstalled(call.packageName)) {
            return ServiceReply.Failed(FailReason.APP_NOT_INSTALLED, "${call.packageName} is not installed or Lily cannot see it")
        }
        val intent = Intent().setClassName(call.packageName, call.className).setAction(call.intentAction)
        for ((key, value) in call.extras) {
            when (value) {
                is ExtraValue.Text -> intent.putExtra(key, value.value)
                is ExtraValue.TextList -> intent.putExtra(key, value.values.toTypedArray())
                is ExtraValue.Flag -> intent.putExtra(key, value.value)
                is ExtraValue.IntValue -> intent.putExtra(key, value.value)
            }
        }

        val spec = call.result
        val requestId = PluginResults.newId()
        val waiting = if (spec == null) null else {
            val back = Intent(app, PluginResultReceiver::class.java).putExtra(EXTRA_REQUEST_ID, requestId)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            intent.putExtra(
                spec.pendingIntentExtra,
                PendingIntent.getBroadcast(app, requestId, back, PendingIntent.FLAG_ONE_SHOT or mutable),
            )
            PluginResults.expect(requestId)
        }

        try {
            if (app.startService(intent) == null) {
                PluginResults.drop(requestId)
                return ServiceReply.Failed(FailReason.ERROR, "${call.packageName} has no service ${call.className}; check that the app is up to date and allows other apps")
            }
        } catch (e: SecurityException) {
            PluginResults.drop(requestId)
            return ServiceReply.Failed(FailReason.NOT_ALLOWED, "Android refused the call: ${e.message}. The permission the app asks for may not be granted to Lily in Android settings.")
        } catch (e: IllegalStateException) {
            PluginResults.drop(requestId)
            return ServiceReply.Failed(FailReason.NOT_ALLOWED, "Android does not let Lily start another app while Lily is in the background. Keep Lily open on screen and try again.")
        } catch (e: Exception) {
            PluginResults.drop(requestId)
            return ServiceReply.Failed(FailReason.ERROR, e.message ?: e.javaClass.simpleName)
        }

        if (spec == null || waiting == null) return ServiceReply.Sent

        val answer = try {
            withTimeoutOrNull(call.timeoutMs) { waiting.await() }
        } finally {
            PluginResults.drop(requestId)
        } ?: return ServiceReply.Failed(FailReason.TIMEOUT, "no answer within ${call.timeoutMs / 1000} seconds; the command may still be running")

        val bundle = answer.getBundleExtra(spec.bundleExtra)
            ?: return ServiceReply.Failed(FailReason.ERROR, "the app answered without a result (${spec.bundleExtra})")

        fun intAt(key: String?): Int? = if (key != null && bundle.containsKey(key)) bundle.getInt(key) else null

        return ServiceReply.Done(
            output = bundle.getString(spec.outputKey).orEmpty(),
            errorOutput = spec.errorOutputKey?.let { bundle.getString(it) },
            exitCode = intAt(spec.exitCodeKey),
            errorCode = intAt(spec.errorCodeKey),
            errorMessage = spec.errorMessageKey?.let { bundle.getString(it) },
        )
    }

    private fun isInstalled(packageName: String): Boolean = try {
        app.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val EXTRA_REQUEST_ID = "ai.lily.app.plugins.REQUEST_ID"
    }
}

/** Answers that are being waited for, by request number. Lives only as long as the app process. */
internal object PluginResults {
    private val next = AtomicInteger(1)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Intent>>()

    fun newId(): Int = next.getAndIncrement()

    fun expect(id: Int): CompletableDeferred<Intent> = CompletableDeferred<Intent>().also { waiting[id] = it }

    fun deliver(id: Int, intent: Intent) {
        waiting.remove(id)?.complete(intent)
    }

    fun drop(id: Int) {
        waiting.remove(id)
    }
}

/**
 * Receives the answer the other app sends through the PendingIntent. It is not exported, so only a PendingIntent that
 * Lily itself created and handed out can reach it.
 */
class PluginResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AndroidServiceBridge.EXTRA_REQUEST_ID, 0)
        if (id != 0) PluginResults.deliver(id, intent)
    }
}
