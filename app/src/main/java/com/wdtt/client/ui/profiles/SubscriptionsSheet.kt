package com.wdtt.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wdtt.client.ProfileSubscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTrafficMb(value: Double): String {
    return when {
        value >= 1024 -> String.format(Locale.US, "%.2f ГБ", value / 1024.0)
        value >= 1 -> String.format(Locale.US, "%.1f МБ", value)
        value > 0 -> String.format(Locale.US, "%.2f МБ", value)
        else -> "0 МБ"
    }
}

private fun formatSyncTime(ts: Long): String {
    if (ts <= 0L) return "ещё не обновлялась"
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))
}

@Composable
fun AddSubscriptionDialog(
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit
) {
    var urlInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Новая подписка", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Адрес подписки") },
                    placeholder = { Text("https://example.com/sub.json") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving
                )
                Text(
                    "Вставьте ссылку — название и профили загрузятся автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(urlInput) },
                enabled = !saving && urlInput.isNotBlank()
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Добавить")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }, enabled = !saving) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun DeleteSubscriptionDialog(
    sub: ProfileSubscription,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить подписку?") },
        text = { Text("«${sub.name}» и папка с профилями будут удалены.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Удалить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionInfoCard(
    sub: ProfileSubscription,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onOpenGroup: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hasLimit = sub.trafficLimitMb > 0
    val hasTraffic = sub.remoteTrafficUsedMb > 0 || hasLimit
    val progress = if (hasLimit && sub.trafficLimitMb > 0) {
        (sub.remoteTrafficUsedMb / sub.trafficLimitMb).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        onClick = { onOpenGroup?.invoke() },
        enabled = onOpenGroup != null,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.RssFeed,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        sub.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    IconButton(onClick = onRefresh, enabled = !isRefreshing, modifier = Modifier.size(36.dp)) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = contentColor
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Обновить подписку",
                                tint = contentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Удалить подписку",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (sub.description.isNotBlank()) {
                Text(
                    sub.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }

            if (hasTraffic) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (hasLimit) {
                            "Трафик: ${formatTrafficMb(sub.remoteTrafficUsedMb)} из ${formatTrafficMb(sub.trafficLimitMb)}"
                        } else {
                            "Трафик: ${formatTrafficMb(sub.remoteTrafficUsedMb)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                        fontWeight = FontWeight.Medium
                    )
                    if (hasLimit) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = contentColor.copy(alpha = 0.25f),
                        )
                    }
                }
            }

            Text(
                "Обновлено: ${formatSyncTime(sub.lastSyncAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.75f)
            )
            if (sub.lastSyncError.isNotBlank()) {
                Text(
                    "Ошибка: ${sub.lastSyncError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
