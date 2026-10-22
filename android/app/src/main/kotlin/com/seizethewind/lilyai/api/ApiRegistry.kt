package com.seizethewind.lilyai.api

import android.content.Context
import com.seizethewind.lilyai.api.providers.GroqProvider
import com.seizethewind.lilyai.settings.SettingsManager
import org.json.JSONObject

/** One entry of assets/services.json. Contains no secrets. */
data class ServiceInfo(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyRequired: Boolean,
    val permissionLevel: String
)

/** Knows every external service the app can use and builds the provider for each. */
class ApiRegistry(
    private val services: List<ServiceInfo>,
    private val keyStore: ApiKeyStore,
    private val settings: SettingsManager
) {

    fun info(id: String): ServiceInfo? = services.firstOrNull { it.id == id }

    fun chatProvider(id: String): ChatProvider? {
        val info = services.firstOrNull { it.id == id && it.type == "chat" } ?: return null
        return when (id) {
            "groq" -> GroqProvider(
                info = info,
                keyProvider = { keyStore.get(id) },
                modelProvider = { settings.groqModel }
            )
            else -> null
        }
    }

    companion object {
        fun load(context: Context, keyStore: ApiKeyStore, settings: SettingsManager): ApiRegistry {
            val text = context.assets.open("services.json").bufferedReader().use { it.readText() }
            val array = JSONObject(text).getJSONArray("services")
            val list = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ServiceInfo(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = o.getString("type"),
                    baseUrl = o.getString("baseUrl"),
                    defaultModel = o.optString("defaultModel", ""),
                    keyRequired = o.optBoolean("keyRequired", true),
                    permissionLevel = o.optString("permissionLevel", "safe")
                )
            }
            return ApiRegistry(list, keyStore, settings)
        }
    }
}
