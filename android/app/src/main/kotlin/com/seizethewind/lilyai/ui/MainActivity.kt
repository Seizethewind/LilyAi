package com.seizethewind.lilyai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
                }
            }
        }
    }
}
