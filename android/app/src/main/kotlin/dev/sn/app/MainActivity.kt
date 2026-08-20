package dev.sn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.sn.app.ui.ChatScreen
import dev.sn.app.ui.ChatViewModel
import dev.sn.app.ui.SettingsScreen
import dev.sn.app.ui.SnTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SnTheme {
                // Two screens is not worth a navigation graph, and a plain flag
                // keeps the ViewModel alive across the switch for free.
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
                } else {
                    ChatScreen(viewModel = viewModel, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
