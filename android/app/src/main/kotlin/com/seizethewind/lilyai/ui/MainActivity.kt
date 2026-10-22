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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LilyAiApplication
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            settings = app.settings,
                            keyStore = app.keyStore,
                            onBack = { showSettings = false }
                        )
                    } else {
                        val chatViewModel: ChatViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer { ChatViewModel(app.agent) }
                            }
                        )
                        ChatScreen(
                            viewModel = chatViewModel,
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }
}
