package com.seizethewind.lilyai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seizethewind.lilyai.LilyAiApplication

private enum class Screen { CHAT, SETTINGS, MEMORY, CLOUD }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LilyAiApplication
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.CHAT) }
                    when (screen) {
                        Screen.CHAT -> {
                            val chatViewModel: ChatViewModel = viewModel(
                                factory = viewModelFactory {
                                    initializer { ChatViewModel(app.agent) }
                                }
                            )
                            ChatScreen(
                                viewModel = chatViewModel,
                                onOpenMemories = { screen = Screen.MEMORY },
                                onOpenSettings = { screen = Screen.SETTINGS }
                            )
                        }
                        Screen.SETTINGS -> {
                            BackHandler { screen = Screen.CHAT }
                            SettingsScreen(
                                settings = app.settings,
                                keyStore = app.keyStore,
                                modelManager = app.modelManager,
                                localEngine = app.localEngine,
                                permissions = app.permissions,
                                onBack = { screen = Screen.CHAT }
                            )
                        }
                        Screen.MEMORY -> {
                            BackHandler { screen = Screen.CHAT }
                            MemoryScreen(
                                memoryManager = app.memoryManager,
                                settings = app.settings,
                                onOpenCloud = { screen = Screen.CLOUD },
                                onBack = { screen = Screen.CHAT }
                            )
                        }
                        Screen.CLOUD -> {
                            BackHandler { screen = Screen.MEMORY }
                            CloudScreen(
                                keyStore = app.keyStore,
                                cloudSync = app.cloudSync,
                                settings = app.settings,
                                onBack = { screen = Screen.MEMORY }
                            )
                        }
                    }

                    // Approval dialog for actions that need the user's OK. Shown on top of any screen.
                    val pending by app.confirmations.pending.collectAsState()
                    pending?.let { request ->
                        AlertDialog(
                            onDismissRequest = { app.confirmations.answer(false) },
                            title = { Text("LilyAi needs your OK") },
                            text = { Text(request.message) },
                            confirmButton = {
                                TextButton(onClick = { app.confirmations.answer(true) }) { Text("Allow") }
                            },
                            dismissButton = {
                                TextButton(onClick = { app.confirmations.answer(false) }) { Text("Deny") }
                            }
                        )
                    }
                }
            }
        }
    }
}
