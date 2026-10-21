package io.github.usmandurrany.tether.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.usmandurrany.tether.core.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [KeyValueStore] whose values are encrypted with AES-256-GCM under a key
 * that lives in the Android Keystore (hardware-backed where the device has it)
 * and never leaves it. Values are kept in private SharedPreferences.
 *
 * Each value is bound to its key name, so values cannot be swapped between
 * entries. If the Keystore key is lost (for example after a backup is restored
 * to another device), stored values read as missing and the app logs in again.
 */
public class AndroidSecureStore(context: Context, name: String = "tether") : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("$name.secure", Context.MODE_PRIVATE)
    private val alias = "tether.store.$name"
    private val mutex = Mutex()

    override suspend fun get(key: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val stored = prefs.getString(key, null) ?: return@withContext null
            runCatching {
                val bytes = Base64.decode(stored, Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
                cipher.updateAAD(key.encodeToByteArray())
                cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
            }.getOrNull()
        }
    }

    override suspend fun put(key: String, value: String?) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (value == null) {
                    prefs.edit().remove(key).commit()
                    return@withContext
                }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey()) // the Keystore picks a fresh random IV
                cipher.updateAAD(key.encodeToByteArray())
                val sealed = cipher.iv + cipher.doFinal(value.encodeToByteArray())
                prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
            }
        }
    }

    /** Loaded once: opening the Keystore on every read and write is slow. */
    @Volatile
    private var cachedKey: SecretKey? = null

    private fun secretKey(): SecretKey = cachedKey ?: loadKey().also { cachedKey = it }

    private fun loadKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
