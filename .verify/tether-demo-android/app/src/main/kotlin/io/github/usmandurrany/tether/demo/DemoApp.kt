package io.github.usmandurrany.tether.demo

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.usmandurrany.tether.android.TetherAndroid
import io.github.usmandurrany.tether.core.ConnectResult
import io.github.usmandurrany.tether.core.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SignInResult {
    data object Connected : SignInResult
    data class Error(val message: String) : SignInResult

    /** The server's certificate is not trusted (self-signed?). Offer to pin [sha256]. */
    data class UntrustedCertificate(val sha256: String, val message: String) : SignInResult
}

/**
 * Holds the one [TetherSession] for the life of the process, as the SDK expects
 * (one client per account per process, in an application-wide scope).
 */
class DemoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settingsStore: SettingsStore
        private set

    /** Android Keystore-encrypted storage for the SDK's state and login keys. */
    val secureStore: KeyValueStore by lazy { TetherAndroid.secureStore(this) }

    private val _session = MutableStateFlow<TetherSession?>(null)
    val session: StateFlow<TetherSession?> = _session.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)

        // Remote logs live in memory until uploaded: push them out when the app is backgrounded.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                scope.launch { _session.value?.remoteLogs?.flush() }
            }
        })

        val saved = settingsStore.load()
        if (saved.autoConnect) {
            scope.launch {
                if (!TetherSession.hasSavedLogin(secureStore, saved.jid)) return@launch
                val session = runCatching {
                    TetherSession.create(this@DemoApp, saved, null, secureStore, settingsStore.installId, scope)
                }.getOrNull() ?: return@launch
                _session.value = session
                // Not torn down on failure: the SDK keeps retrying, and the app shows the state.
                session.connect()
            }
        }
    }

    /**
     * Signs in from the settings page. Unlike auto-connect, a failed first attempt
     * stops the session, so the user can fix the settings and try again.
     */
    suspend fun signIn(settings: ConnectionSettings, password: String?): SignInResult {
        settingsStore.save(settings)
        _session.value?.let { _session.value = null; it.signOut(forget = false) }
        val session = try {
            TetherSession.create(this, settings, password, secureStore, settingsStore.installId, scope)
        } catch (e: IllegalArgumentException) {
            return SignInResult.Error(e.message ?: "Invalid settings")
        }
        connecting.value = session
        val result = try {
            session.connect()
        } finally {
            connecting.value = null
        }
        return when (result) {
            is ConnectResult.Connected -> {
                _session.value = session
                SignInResult.Connected
            }
            is ConnectResult.Failed -> {
                session.signOut(forget = false)
                val pin = TetherSession.refusedCertificate(result.error)
                if (pin != null) {
                    SignInResult.UntrustedCertificate(pin, TetherSession.describe(result.error))
                } else {
                    SignInResult.Error(TetherSession.describe(result.error))
                }
            }
            ConnectResult.Cancelled -> {
                session.signOut(forget = false)
                SignInResult.Error("Cancelled")
            }
        }
    }

    /** The session of a sign-in still in progress, so the settings page can show its state. */
    val connecting = MutableStateFlow<TetherSession?>(null)

    fun signOut(forget: Boolean) {
        val current = _session.value ?: return
        _session.value = null
        scope.launch {
            current.signOut(forget)
            if (forget) settingsStore.save(settingsStore.load().copy(autoConnect = false))
        }
    }
}
