package com.example.meeraai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.meeraai.MainActivity
import com.example.meeraai.R
import com.example.meeraai.data.BotConfig
import com.example.meeraai.data.LogEntry
import com.example.meeraai.data.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class BotForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "meera_bot_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.example.meeraai.START_BOT"
        const val ACTION_STOP = "com.example.meeraai.STOP_BOT"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
        val logs: StateFlow<List<LogEntry>> = _logs

        private val _statusText = MutableStateFlow("Stopped")
        val statusText: StateFlow<String> = _statusText

        fun start(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var botService: TelegramBotService? = null
    private var serviceScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBot()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
                acquireWakeLock()
                startBot()
            }
        }
        return START_STICKY // Restart if killed by the system
    }

    private fun startBot() {
        if (_isRunning.value) return

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serviceScope = scope

        scope.launch {
            try {
                _statusText.value = "Starting..."
                _isRunning.value = true

                val settingsStore = SettingsStore(applicationContext)
                val config = settingsStore.getBotConfig()

                if (config.telegramBotToken.isBlank()) {
                    _statusText.value = "Error: No bot token"
                    _isRunning.value = false
                    stopSelf()
                    return@launch
                }

                val tempDir = File(cacheDir, "meera_temp")
                val firebase = FirebaseService(applicationContext)
                botService = TelegramBotService(config, tempDir, firebase)

                // Forward logs
                launch {
                    botService!!.logs.collect { logEntries ->
                        _logs.value = logEntries
                    }
                }

                botService!!.start(scope)
                _statusText.value = "Running"
                updateNotification("Meera Bot is running")

            } catch (e: Exception) {
                _statusText.value = "Error: ${e.message}"
                _isRunning.value = false
                updateNotification("Error: ${e.message}")
                // Don't stop immediately — let user see the error
                delay(10_000)
                stopSelf()
            }
        }
    }

    private fun stopBot() {
        botService?.stop()
        botService = null
        serviceScope?.cancel()
        serviceScope = null
        releaseWakeLock()
        _isRunning.value = false
        _statusText.value = "Stopped"
        _logs.value = emptyList()
    }

    override fun onDestroy() {
        stopBot()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents — keep running
        super.onTaskRemoved(rootIntent)
    }

    // ─── Wake Lock ─────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MeeraAI::BotWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ─── Notification ──────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Meera Bot Service",
            NotificationManager.IMPORTANCE_LOW // No sound, just persistent
        ).apply {
            description = "Keeps Meera bot running in the background"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        // Tap notification → open app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action button
        val stopIntent = Intent(this, BotForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Meera AI")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Stop Bot", stopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
