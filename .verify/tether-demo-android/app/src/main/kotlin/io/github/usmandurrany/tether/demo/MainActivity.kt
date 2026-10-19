package io.github.usmandurrany.tether.demo

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.demo.ui.ChatScreen
import io.github.usmandurrany.tether.demo.ui.HomeScreen
import io.github.usmandurrany.tether.demo.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as DemoApp
        setContent {
            DemoTheme {
                val session by app.session.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var openChat by rememberSaveable { mutableStateOf<String?>(null) }
                val current = session

                when {
                    current == null || showSettings -> SettingsScreen(
                        app = app,
                        canGoBack = current != null,
                        onBack = { showSettings = false },
                        onConnected = { showSettings = false; openChat = null },
                    )
                    openChat != null -> {
                        BackHandler { openChat = null }
                        ChatScreen(current, BareJid.parse(openChat!!), onBack = { openChat = null })
                    }
                    else -> HomeScreen(
                        session = current,
                        onOpenChat = { openChat = it.toString() },
                        onSettings = { showSettings = true },
                        onSignOut = { forget -> app.signOut(forget) },
                    )
                }
                if (showSettings && current != null) BackHandler { showSettings = false }
            }
        }
    }
}

@Composable
private fun DemoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
