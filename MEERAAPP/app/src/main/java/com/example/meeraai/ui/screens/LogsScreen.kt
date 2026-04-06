package com.example.meeraai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meeraai.data.LogEntry
import com.example.meeraai.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Logs", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MeeraGreen.copy(alpha = 0.15f),
                        ) {
                            Text(
                                "${logs.size}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                fontSize = 12.sp,
                                color = MeeraGreen,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MeeraOnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            if (autoScroll) Icons.Filled.VerticalAlignBottom else Icons.Filled.PauseCircle,
                            contentDescription = "Toggle auto-scroll",
                            tint = if (autoScroll) MeeraGreen else MeeraGray,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MeeraBackground,
                    titleContentColor = MeeraOnSurface,
                ),
            )
        },
        containerColor = MeeraBackground,
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = MeeraGray,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No logs yet", color = MeeraGray, fontSize = 16.sp)
                    Text("Start the bot to see activity", color = MeeraGray.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(logs, key = { "${it.timestamp}_${it.message.hashCode()}" }) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val levelColor = when (entry.level) {
        "ERROR" -> MeeraRed
        "WARN" -> MeeraYellow
        "INFO" -> MeeraGreen
        else -> MeeraGrayLight
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            timeFormat.format(Date(entry.timestamp)),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MeeraGray,
            modifier = Modifier.width(65.dp),
        )
        Text(
            entry.level.padEnd(5),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = levelColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(45.dp),
        )
        Text(
            "[${entry.tag}]",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MeeraPurpleLight,
            modifier = Modifier.width(75.dp),
        )
        Text(
            entry.message,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MeeraOnSurface.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f),
        )
    }
}
