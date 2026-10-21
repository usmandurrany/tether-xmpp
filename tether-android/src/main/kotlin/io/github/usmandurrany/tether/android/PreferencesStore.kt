package io.github.usmandurrany.tether.android

import android.content.Context
import io.github.usmandurrany.tether.core.KeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A plain [KeyValueStore] in the app's private SharedPreferences, for data
 * that is not secret but can be large, such as the contact list. Writes are
 * applied in the background. Use [TetherAndroid.secureStore] for login keys
 * and the client's state.
 */
public class PreferencesStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { prefs.getString(key, null) }

    override suspend fun put(key: String, value: String?) {
        withContext(Dispatchers.IO) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }
}
