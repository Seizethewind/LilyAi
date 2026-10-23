package com.seizethewind.lilyai.memory

import android.util.Base64
import com.seizethewind.lilyai.api.providers.TursoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts everything on the phone before it is sent to Turso, so the databases only ever
 * hold ciphertext. The key comes from the user's passphrase (PBKDF2), not from the phone,
 * so a backup can be restored on a new phone with the same passphrase. Losing the
 * passphrase means the cloud data cannot be recovered.
 */
class CloudCrypto(passphrase: String, salt: ByteArray) {

    private val key: SecretKeySpec = derive(passphrase, salt)

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    /** Returns null if the data is damaged or the passphrase is wrong. */
    fun decrypt(encoded: String): String? =
        try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            if (data.size <= IV_BYTES) {
                null
            } else {
                val iv = data.copyOfRange(0, IV_BYTES)
                val encrypted = data.copyOfRange(IV_BYTES, data.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            }
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    companion object {
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val ITERATIONS = 210_000
        private const val CHECK_TEXT = "lilyai-ok"

        private fun derive(passphrase: String, salt: ByteArray): SecretKeySpec {
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        }

        private fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

        /**
         * Opens the encryption for one Turso database. The salt and a passphrase check value live
         * in the database's meta table (neither is secret), so any phone with the passphrase
         * derives the same key. Fails with a clear message if the passphrase is wrong.
         */
        suspend fun open(client: TursoClient, passphrase: String): Result<CloudCrypto> =
            withContext(Dispatchers.Default) {
                runCatching {
                    client.run(
                        listOf("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)" to emptyList<Any?>())
                    ).getOrThrow()

                    var saltText = readMeta(client, "salt")
                    if (saltText == null) {
                        client.run(
                            listOf(
                                "INSERT OR IGNORE INTO meta (k, v) VALUES ('salt', ?)" to
                                    listOf<Any?>(Base64.encodeToString(newSalt(), Base64.NO_WRAP))
                            )
                        ).getOrThrow()
                        saltText = readMeta(client, "salt")
                            ?: throw IOException("Could not store the encryption salt.")
                    }

                    val crypto = CloudCrypto(passphrase, Base64.decode(saltText, Base64.NO_WRAP))
                    val stored = readMeta(client, "check")
                    if (stored == null) {
                        client.run(
                            listOf(
                                "INSERT OR IGNORE INTO meta (k, v) VALUES ('check', ?)" to
                                    listOf<Any?>(crypto.encrypt(CHECK_TEXT))
                            )
                        ).getOrThrow()
                        val now = readMeta(client, "check")
                        if (now == null || crypto.decrypt(now) != CHECK_TEXT) {
                            throw IOException("Wrong passphrase for this database.")
                        }
                    } else if (crypto.decrypt(stored) != CHECK_TEXT) {
                        throw IOException("Wrong passphrase for this database.")
                    }
                    crypto
                }
            }

        private suspend fun readMeta(client: TursoClient, key: String): String? =
            client.run(listOf("SELECT v FROM meta WHERE k = ?" to listOf<Any?>(key)))
                .getOrThrow()
                .first()
                .firstOrNull()
                ?.firstOrNull()
    }
}
