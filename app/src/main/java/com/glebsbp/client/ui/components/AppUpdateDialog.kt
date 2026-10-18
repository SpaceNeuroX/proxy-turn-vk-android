package com.glebsbp.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.glebsbp.client.DownloadState
import com.glebsbp.client.downloadUpdate
import com.glebsbp.client.installApk
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glebsbp.client.AppReleaseInfo
import com.glebsbp.client.RemoteVersionSource

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    onPostpone: () -> Unit,
    onUpdate: () -> Unit
) {
    val isTagOnly = release.source == RemoteVersionSource.Tag
    val title = if (isTagOnly) "Найден новый tag" else "Доступно обновление"
    
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    val description = if (isTagOnly) {
        "На GitHub обнаружен более новый tag ${release.versionTag}. Похоже, опубликованный release ещё не догнал его."
    } else {
        "Вышла новая версия приложения ${release.versionTag}. Можно скачать и установить обновление автоматически."
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Update,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = release.versionTag,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                if (release.releaseNotes.isNotBlank()) {
                    Text(
                        text = release.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                if (release.isPrerelease) {
                    Text(
                        text = "Предрелиз (beta)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                when (val state = downloadState) {
                    is DownloadState.Idle, is DownloadState.Error -> {
                        if (state is DownloadState.Error) {
                            Text("Ошибка загрузки: ${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onPostpone,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Text("Позже", fontWeight = FontWeight.SemiBold)
                            }
        
                            Button(
                                onClick = {
                                    if (release.downloadUrl != null && !isTagOnly) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                downloadUpdate(context, release.downloadUrl, release.versionTag).collect { newState ->
                                                    withContext(Dispatchers.Main) {
                                                        downloadState = newState
                                                        if (newState is DownloadState.Finished) {
                                                            installApk(context, newState.file)
                                                            onPostpone() // Close dialog
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        onUpdate() // Fallback to browser
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Text(if (release.downloadUrl != null && !isTagOnly) "Скачать" else "В браузере", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    is DownloadState.Downloading -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                            Text(
                                text = "Скачивание... ${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is DownloadState.Finished -> {
                        Text("Готово! Запуск установки...", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
