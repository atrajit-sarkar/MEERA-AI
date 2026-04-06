package com.example.meeraai

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.meeraai.ui.screens.HomeScreen
import com.example.meeraai.ui.screens.LoginScreen
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
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val isVerifying by viewModel.isVerifying.collectAsState()
    val loginError by viewModel.loginError.collectAsState()
    val botName by viewModel.botName.collectAsState()
    val customSystemPrompt by viewModel.customSystemPrompt.collectAsState()
    val ollamaHost by viewModel.ollamaHost.collectAsState()
    val ollamaModel by viewModel.ollamaModel.collectAsState()
    val elevenlabsVoiceId by viewModel.elevenlabsVoiceId.collectAsState()
    val botStatus by viewModel.botStatus.collectAsState()
    val isConfigValid by viewModel.isConfigValid.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val needsNotificationPermission by viewModel.needsNotificationPermission.collectAsState()

    // Notification permission launcher (Android 13+)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    LaunchedEffect(needsNotificationPermission) {
        if (needsNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Navigate on login/logout
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn && navController.currentDestination?.route == "login") {
            navController.navigate("home") {
                popUpTo("login") { inclusive = true }
            }
        } else if (!isLoggedIn && navController.currentDestination?.route != "login") {
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val startDestination = if (isLoggedIn) "home" else "login"

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") {
            LoginScreen(
                isVerifying = isVerifying,
                errorMessage = loginError,
                onConnect = { viewModel.verifyAndLogin(it) },
            )
        }
        composable("home") {
            HomeScreen(
                botStatus = botStatus,
                isConfigValid = isConfigValid,
                onStartBot = { viewModel.startBot() },
                onStopBot = { viewModel.stopBot() },
                onLogout = { viewModel.logout() },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToLogs = { navController.navigate("logs") },
            )
        }
        composable("settings") {
            SettingsScreen(
                botName = botName,
                customSystemPrompt = customSystemPrompt,
                ollamaHost = ollamaHost,
                ollamaModel = ollamaModel,
                elevenlabsVoiceId = elevenlabsVoiceId,
                onBotNameChange = { viewModel.saveBotName(it) },
                onCustomSystemPromptChange = { viewModel.saveCustomSystemPrompt(it) },
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