package ai.lily.app.plugins

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Where the values a person fills in for a plugin are kept, per plugin and setting name. Secrets are never put in the
 * plugin's own file, never shown again after saving and never given to Lily: a tool only sends them, at run time.
 */
interface PluginSecrets {
    fun get(pluginId: String, name: String): String?
    fun set(pluginId: String, name: String, value: String)
    fun remove(pluginId: String, name: String)

    /** Forgets everything kept for one plugin (used on uninstall). */
    fun clear(pluginId: String)
}

/** Holds nothing: used where no storage exists. */
object NoSecrets : PluginSecrets {
    override fun get(pluginId: String, name: String): String? = null
    override fun set(pluginId: String, name: String, value: String) = Unit
    override fun remove(pluginId: String, name: String) = Unit
    override fun clear(pluginId: String) = Unit
}

class InMemoryPluginSecrets : PluginSecrets {
    private val values = HashMap<String, String>()
    override fun get(pluginId: String, name: String): String? = values["$pluginId/$name"]
    override fun set(pluginId: String, name: String, value: String) {
        values["$pluginId/$name"] = value
    }

    override fun remove(pluginId: String, name: String) {
        values.remove("$pluginId/$name")
    }

    override fun clear(pluginId: String) {
        values.keys.removeAll { it.startsWith("$pluginId/") }
    }
}

/** Android keystore backed storage, the same kind the app uses for its keys and tokens. */
class EncryptedPluginSecrets(context: Context) : PluginSecrets {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "lily_plugin_secrets",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun get(pluginId: String, name: String): String? = prefs.getString("$pluginId/$name", null)

    override fun set(pluginId: String, name: String, value: String) {
        prefs.edit().putString("$pluginId/$name", value).apply()
    }

    override fun remove(pluginId: String, name: String) {
        prefs.edit().remove("$pluginId/$name").apply()
    }

    override fun clear(pluginId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$pluginId/") }.forEach { editor.remove(it) }
        editor.apply()
    }
}
