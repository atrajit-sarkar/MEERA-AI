package com.example.meeraai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.meeraai.ui.screens.HomeScreen
import com.example.meeraai.ui.screens.LogsScreen
import com.example.meeraai.ui.screens.SettingsScreen
import com.example.meeraai.ui.theme.MeeraAITheme
import com.example.meeraai.viewmodel.BotViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeeraAITheme {
                MeeraApp()
            }
        }
    }
}

@Composable
fun MeeraApp(viewModel: BotViewModel = viewModel()) {
    val navController = rememberNavController()

    val botToken by viewModel.botToken.collectAsState()
    val encryptionKey by viewModel.encryptionKey.collectAsState()
    val ollamaHost by viewModel.ollamaHost.collectAsState()
    val ollamaModel by viewModel.ollamaModel.collectAsState()
    val elevenlabsVoiceId by viewModel.elevenlabsVoiceId.collectAsState()
    val botStatus by viewModel.botStatus.collectAsState()
    val isConfigValid by viewModel.isConfigValid.collectAsState()
    val logs by viewModel.logs.collectAsState()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                botToken = botToken,
                encryptionKey = encryptionKey,
                botStatus = botStatus,
                isConfigValid = isConfigValid,
                onBotTokenChange = { viewModel.saveBotToken(it) },
                onEncryptionKeyChange = { viewModel.saveEncryptionKey(it) },
                onGenerateEncryptionKey = { viewModel.generateEncryptionKey() },
                onStartBot = { viewModel.startBot() },
                onStopBot = { viewModel.stopBot() },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToLogs = { navController.navigate("logs") },
            )
        }
        composable("settings") {
            SettingsScreen(
                ollamaHost = ollamaHost,
                ollamaModel = ollamaModel,
                elevenlabsVoiceId = elevenlabsVoiceId,
                onOllamaHostChange = { viewModel.saveOllamaHost(it) },
                onOllamaModelChange = { viewModel.saveOllamaModel(it) },
                onElevenlabsVoiceIdChange = { viewModel.saveElevenlabsVoiceId(it) },
                onBack = { navController.popBackStack() },
            )
        }
        composable("logs") {
            LogsScreen(
                logs = logs,
                onBack = { navController.popBackStack() },
            )
        }
    }
}