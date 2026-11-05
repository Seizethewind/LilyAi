package ai.lily.app.plugins

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Values the person typed in for a plugin's declared secrets. Keyed by plugin id and secret name. */
interface PluginSecrets {
    fun get(pluginId: String, name: String): String?
    fun set(pluginId: String, name: String, value: String)
    fun remove(pluginId: String, name: String)

    /** Forgets every secret of one plugin (used when it is uninstalled). */
    fun clear(pluginId: String)
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

/** Encrypted app storage, like the keys in settings. A plugin id never contains '/', so the prefix cannot clash. */
class EncryptedPluginSecrets(context: Context) : PluginSecrets {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "lily_plugin_secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
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
        val edit = prefs.edit()
        for (key in prefs.all.keys) if (key.startsWith("$pluginId/")) edit.remove(key)
        edit.apply()
    }
}
