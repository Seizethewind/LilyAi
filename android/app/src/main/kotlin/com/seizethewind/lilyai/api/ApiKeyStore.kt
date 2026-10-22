package com.seizethewind.lilyai.api

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores API keys encrypted on the device (Android Keystore master key).
 * Keys are entered by the user at runtime and are never part of the APK or the repo.
 */
class ApiKeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun get(serviceId: String): String? =
        try {
            prefs.getString(name(serviceId), null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

    fun has(serviceId: String): Boolean = get(serviceId) != null

    /** Returns false if the key could not be saved on this device. */
    fun set(serviceId: String, value: String): Boolean =
        try {
            prefs.edit().putString(name(serviceId), value).apply()
            true
        } catch (e: Exception) {
            false
        }

    fun clear(serviceId: String) {
        try {
            prefs.edit().remove(name(serviceId)).apply()
        } catch (e: Exception) {
            // Nothing stored or store unavailable: nothing to clear.
        }
    }

    private fun name(serviceId: String) = "key_$serviceId"

    companion object {
        private const val PREFS_NAME = "lilyai_secure_prefs"
    }
}
