package com.glebsbp.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebsbp.client.ConnectionPipelineCard
import com.glebsbp.client.LogEntry
import com.glebsbp.client.TunnelManager
import com.glebsbp.client.GlebColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsTab() {
    val context = LocalContext.current
    val currentLogs by TunnelManager.logs.collectAsStateWithLifecycle()
    val lastFatalError by TunnelManager.lastFatalError.collectAsStateWithLifecycle()
    val statsText by TunnelManager.stats.collectAsStateWithLifecycle()
    val isRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val isConnecting by TunnelManager.isConnecting.collectAsStateWithLifecycle()
    val connectedSinceMs by TunnelManager.connectedSinceMs.collectAsStateWithLifecycle()
    val pipelineState by TunnelManager.connectionPipeline.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRunning, connectedSinceMs) {
        if (!isRunning || connectedSinceMs <= 0L) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val uptimeText = if (isRunning && connectedSinceMs > 0L) {
        TunnelManager.formatUptime(nowMs - connectedSinceMs)
    } else {
        null
    }

    // Синяя статистика закреплена сверху — не тонет среди DNS/VK/капчи.
    val scrollableLogs = remember(currentLogs) {
        currentLogs.filter { it.key != "stats" }
    }
    val pinnedStatsMessage = remember(statsText, isRunning, isConnecting, currentLogs) {
        val fromLog = currentLogs.firstOrNull { it.key == "stats" }?.message
        val message = when {
            !fromLog.isNullOrBlank() -> fromLog
            (isRunning || isConnecting) && statsText.isNotBlank() -> "[СТАТИСТИКА] $statsText"
            else -> null
        }
        message?.replace(
            Regex("""\s*\|\s*↓\s*[\d.,]+\s*МБ\s*/\s*↑\s*[\d.,]+\s*МБ"""),
            ""
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Лог событий",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Row {
                IconButton(onClick = { TunnelManager.clearLogs() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    val text = buildString {
                        if (pinnedStatsMessage != null) {
                            appendLine(pinnedStatsMessage)
                        }
                        scrollableLogs.forEach { appendLine("${it.message} (x${it.count})") }
                    }.trim()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GLEBSBP Logs", text))
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    val uiLogText = buildString {
                        if (pinnedStatsMessage != null) {
                            appendLine(pinnedStatsMessage)
                        }
                        scrollableLogs.forEach { appendLine("${it.message} (x${it.count})") }
                    }.trim()
                    exportFullLog(context, uiLogText)
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Export logs", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        val isDark = isSystemInDarkTheme()
        val terminalBg = if (isDark) GlebColors.terminalBgDark else GlebColors.terminalBg

        // Переживает clearLogs() (вызывается на каждый новый старт подключения) —
        // без этого текст последней фатальной ошибки исчезал раньше, чем
        // пользователь успевал его прочитать/скопировать при повторном нажатии
        // "Подключить".
        if (lastFatalError != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = lastFatalError.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("GLEBSBP Error", lastFatalError.orEmpty()))
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy error", tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    IconButton(onClick = { TunnelManager.lastFatalError.value = null }) {
                        Icon(Icons.Default.Delete, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = terminalBg),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (pinnedStatsMessage != null || uptimeText != null) {
                    Surface(
                        color = GlebColors.terminalBlue.copy(alpha = if (isDark) 0.18f else 0.12f),
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (pinnedStatsMessage != null) {
                                Text(
                                    text = pinnedStatsMessage
                                        .removePrefix("[СТАТИСТИКА] ")
                                        .removePrefix("[СТАТИСТИКА]"),
                                    color = GlebColors.terminalBlue,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 14.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            if (uptimeText != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = GlebColors.terminalBlue,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = uptimeText,
                                        color = GlebColors.terminalBlue,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        color = GlebColors.terminalBlue.copy(alpha = 0.35f),
                        thickness = 1.dp
                    )
                }

                AnimatedVisibility(
                    visible = pipelineState.visible,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = GlebColors.terminalBlue.copy(alpha = if (isDark) 0.10f else 0.08f),
                        ) {}
                        ConnectionPipelineCard(
                            state = pipelineState,
                            isDark = isDark,
                        )
                        HorizontalDivider(
                            color = GlebColors.terminalBlue.copy(alpha = 0.20f),
                            thickness = 1.dp
                        )
                    }
                }

                if (scrollableLogs.isEmpty() && pinnedStatsMessage == null && uptimeText == null && !pipelineState.visible) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Notes,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = "Логи пусты",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Здесь будут отображаться события туннеля",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(scrollableLogs, key = { it.key }) { entry ->
                            LogLine(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogLine(entry: LogEntry) {
    val color = when {
        entry.isError -> GlebColors.terminalRed
        entry.priority <= 2 -> GlebColors.terminalGreen
        entry.priority == 3 -> GlebColors.terminalBlue
        else -> GlebColors.terminalText
    }

    var trigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(entry.count) { trigger++ }

    val animatedScale by animateFloatAsState(
        targetValue = if (trigger > 0) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale",
        finishedListener = { trigger = 0 }
    )

    val showDnsSettingsAction = entry.key == "go_dns_tip" ||
        entry.key == "err_vk_dns" ||
        entry.key == "go_dns_precheck_fail"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = GlebColors.terminalCounter.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                    .graphicsLayer(scaleX = animatedScale, scaleY = animatedScale)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = "${entry.count}",
                        color = GlebColors.terminalBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = entry.message,
                color = color,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (entry.isError) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }

        if (showDnsSettingsAction) {
            TextButton(
                onClick = { TunnelManager.requestOpenAppSettings() },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text(
                    "Открыть ⚙️ → Сеть",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Собирает в один файл и открытый шаринг:
 *  1. UI-лог (то же, что и кнопка "Копировать") — уже распарсенные, читаемые
 *     события: этапы подключения, [RAW-DIAG], статистика.
 *  2. Реальный logcat процесса — всё остальное, включая необработанные
 *     исключения/стектрейсы и системные события (ANR, kill фонового сервиса),
 *     которые ни один парсер в UI-лог не превращает. Без READ_LOGS-разрешения
 *     (недоступно обычным приложениям на современном Android без root)
 *     `logcat -d` возвращает только строки ЭТОГО процесса — этого достаточно,
 *     так как весь наш Log.i/w/e и вывод go_client-подпроцесса попадают
 *     именно туда.
 */
private fun exportFullLog(context: android.content.Context, uiLogText: String) {
    MainScope().launch(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
            val logcatOutput = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val output = buildString {
                appendLine("=== Информация о среде ===")
                appendLine("Приложение: GLEBSBP ${com.glebsbp.client.BuildConfig.VERSION_NAME} (build ${com.glebsbp.client.BuildConfig.VERSION_CODE})")
                appendLine("Устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine()
                appendLine("=== Текущее подключение ===")
                appendLine(TunnelManager.connectionDiagnosticsSummary())
                appendLine()
                appendLine("=== UI-лог GLEBSBP ===")
                appendLine(uiLogText)
                appendLine()
                appendLine("=== logcat ===")
                append(logcatOutput)
            }

            val dir = java.io.File(context.cacheDir, "logs").apply { mkdirs() }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(dir, "glebsbp_log_$timestamp.txt")
            file.writeText(output)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(
                    android.content.Intent.createChooser(shareIntent, "Отправить лог").apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Не удалось выгрузить лог: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
