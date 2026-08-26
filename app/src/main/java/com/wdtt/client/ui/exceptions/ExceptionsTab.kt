package com.wdtt.client.ui

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.BypassRoutes
import com.wdtt.client.BypassShare
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Stable
data class AppItem(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean = false
)

object AppCache {
    var cachedList: List<AppItem>? = null
}

private enum class ExceptionsPane { Routes, Apps }

private val BypassCardShape = RoundedCornerShape(24.dp)
private val BypassControlShape = RoundedCornerShape(14.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionsTab() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val colors = MaterialTheme.colorScheme

    var pane by rememberSaveable { mutableStateOf(ExceptionsPane.Routes) }

    val savedExcluded by settingsStore.excludedApps.collectAsStateWithLifecycle(initialValue = "")
    val selectedPackages = remember(savedExcluded) {
        savedExcluded.split(",").filter { it.isNotEmpty() }.toSet()
    }

    var appsList by remember { mutableStateOf<List<AppItem>>(AppCache.cachedList ?: emptyList()) }
    var isLoading by remember { mutableStateOf(AppCache.cachedList == null) }
    var isMigrationReady by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val isWhitelist by settingsStore.isWhitelist.collectAsStateWithLifecycle(initialValue = false)
    val savedBypass by settingsStore.bypassRoutes.collectAsStateWithLifecycle(initialValue = "")
    val connectionMode by settingsStore.connectionMode.collectAsStateWithLifecycle(
        initialValue = SettingsStore.CONNECTION_MODE_VPN
    )
    val isRawTunMode = SettingsStore.normalizeConnectionMode(connectionMode) ==
        SettingsStore.CONNECTION_MODE_RAWTUN
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle(initialValue = false)
    val bypassRules = remember(savedBypass) {
        BypassRoutes.limitRules(BypassRoutes.parseRules(savedBypass))
    }
    var newRule by remember { mutableStateOf("") }
    var bypassHint by remember { mutableStateOf<String?>(null) }
    var bypassBusy by remember { mutableStateOf(false) }
    var shareBusy by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    var showExportDialog by remember { mutableStateOf(false) }
    var exportScope by remember { mutableStateOf(BypassShare.Scope.All) }
    var pendingExportScope by remember { mutableStateOf<BypassShare.Scope?>(null) }

    var showImportDialog by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var importPeek by remember { mutableStateOf<BypassShare.PeekResult?>(null) }
    var importScope by remember { mutableStateOf(BypassShare.Scope.All) }

    fun persistBypass(rules: List<String>, limitNote: String? = null) {
        scope.launch {
            bypassBusy = true
            bypassHint = null
            try {
                val capped = BypassRoutes.limitRules(rules)
                val raw = capped.joinToString("\n")
                settingsStore.saveBypassRoutes(raw)
                BypassRoutes.clearResolveCache()
                val result = withContext(Dispatchers.IO) {
                    BypassRoutes.buildAllowedIps(raw, context)
                }
                bypassHint = when {
                    result.unresolved.isNotEmpty() ->
                        "Не резолвится: ${result.unresolved.take(2).joinToString()}"
                    result.truncated ->
                        "Список урезан — слишком много адресов"
                    isRawTunMode && tunnelRunning ->
                        "В режиме Raw список применится при следующем подключении"
                    capped.isEmpty() -> null
                    limitNote != null -> limitNote
                    else -> null
                }
                if (!isRawTunMode) {
                    TunnelManager.reloadWireGuard()
                }
            } catch (e: Exception) {
                bypassHint = "Ошибка: ${e.message}"
            } finally {
                bypassBusy = false
            }
        }
    }

    fun addBypassRule() {
        val rule = newRule.trim()
        if (rule.isEmpty() || bypassBusy) return
        if (bypassRules.any { it.equals(rule, ignoreCase = true) }) {
            bypassHint = "Уже в списке"
            newRule = ""
            return
        }
        if (bypassRules.size >= BypassRoutes.MAX_RULES) {
            bypassHint = "Лимит ${BypassRoutes.MAX_RULES} сайтов"
            return
        }
        newRule = ""
        persistBypass(bypassRules + rule)
    }

    fun removeBypassRule(rule: String) {
        if (bypassBusy) return
        persistBypass(bypassRules.filterNot { it == rule })
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val chosen = pendingExportScope
        pendingExportScope = null
        if (uri == null || chosen == null) return@rememberLauncherForActivityResult
        scope.launch {
            shareBusy = true
            try {
                val json = BypassShare.exportJson(settingsStore, chosen)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Не удалось сохранить файл")
                }
                val what = when (chosen) {
                    BypassShare.Scope.All -> "всё"
                    BypassShare.Scope.Routes -> "сайты"
                    BypassShare.Scope.Apps -> "приложения"
                }
                Toast.makeText(context, "Экспорт ($what) готов", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                shareBusy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            shareBusy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader(Charsets.UTF_8).readText()
                    } ?: error("Не удалось прочитать файл")
                }
                val peek = BypassShare.peek(text)
                if (!peek.hasRoutes && !peek.hasApps) {
                    Toast.makeText(context, "В файле нет данных обхода", Toast.LENGTH_LONG).show()
                    return@launch
                }
                pendingImportJson = text
                importPeek = peek
                importScope = when {
                    peek.hasRoutes && peek.hasApps -> BypassShare.Scope.All
                    peek.hasRoutes -> BypassShare.Scope.Routes
                    else -> BypassShare.Scope.Apps
                }
                showImportDialog = true
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка чтения: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                shareBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            settingsStore.migrateLegacyWhitelistMode()
        }
        isMigrationReady = true
        if (AppCache.cachedList != null) return@LaunchedEffect
        isLoading = true
        withContext(Dispatchers.IO) {
            val list = mutableListOf<AppItem>()
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { app ->
                if (app.packageName != context.packageName &&
                    !app.packageName.contains("vkontakte") &&
                    !app.packageName.contains("vk.calls")
                ) {
                    val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val drawable = app.loadIcon(pm)
                    val iconBitmap = if (drawable != null) {
                        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 128
                        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 128
                        runCatching { drawable.toBitmap(w, h).asImageBitmap() }.getOrNull()
                    } else null
                    list.add(
                        AppItem(
                            name = app.loadLabel(pm).toString(),
                            packageName = app.packageName,
                            icon = iconBitmap,
                            isSystem = isSystem
                        )
                    )
                }
            }
            appsList = list.sortedBy { it.name.lowercase() }
            AppCache.cachedList = appsList
        }
        isLoading = false
    }

    val filteredApps = remember(appsList, showSystemApps, searchQuery, selectedPackages) {
        val baseList = if (showSystemApps) appsList else appsList.filter { !it.isSystem }
        val matchingApps = if (searchQuery.isBlank()) {
            baseList
        } else {
            baseList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
        matchingApps.sortedWith(
            compareByDescending<AppItem> { it.packageName in selectedPackages }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
                .thenBy { it.packageName }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Обход",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp
                ),
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { showExportDialog = true },
                enabled = !shareBusy && !bypassBusy
            ) {
                Icon(
                    Icons.Outlined.FileUpload,
                    contentDescription = "Экспорт",
                    tint = colors.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { importLauncher.launch("*/*") },
                enabled = !shareBusy && !bypassBusy
            ) {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = "Импорт",
                    tint = colors.onSurfaceVariant
                )
            }
        }

        if (bypassBusy || shareBusy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .height(2.dp),
                color = colors.primary,
                trackColor = colors.surfaceVariant
            )
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
        ) {
            SegmentedButton(
                selected = pane == ExceptionsPane.Routes,
                onClick = { pane = ExceptionsPane.Routes },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.secondaryContainer,
                    activeContentColor = colors.onSecondaryContainer,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = colors.onSurfaceVariant
                ),
                border = SegmentedButtonDefaults.borderStroke(colors.outlineVariant.copy(alpha = 0.7f))
            ) {
                Text(
                    "Сайты  ${bypassRules.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
            SegmentedButton(
                selected = pane == ExceptionsPane.Apps,
                onClick = { pane = ExceptionsPane.Apps },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = colors.secondaryContainer,
                    activeContentColor = colors.onSecondaryContainer,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = colors.onSurfaceVariant
                ),
                border = SegmentedButtonDefaults.borderStroke(colors.outlineVariant.copy(alpha = 0.7f))
            ) {
                Text(
                    "Приложения  ${selectedPackages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 4.dp),
            shape = BypassCardShape,
            color = colors.surfaceContainerLow,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            when (pane) {
                ExceptionsPane.Routes -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${bypassRules.size} / ${BypassRoutes.MAX_RULES}",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.2.sp
                                ),
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (bypassRules.isNotEmpty()) {
                                TextButton(
                                    onClick = { showClearConfirm = true },
                                    enabled = !bypassBusy,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        "Очистить",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colors.error
                                    )
                                }
                            }
                        }

                        BypassInputBar(
                            value = newRule,
                            onValueChange = { newRule = it.filter { c -> c != '\n' && c != '\r' } },
                            enabled = !bypassBusy,
                            canAdd = !bypassBusy &&
                                newRule.isNotBlank() &&
                                bypassRules.size < BypassRoutes.MAX_RULES,
                            busy = bypassBusy,
                            onAdd = { addBypassRule() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        )

                        bypassHint?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(top = 10.dp),
                            color = colors.outlineVariant.copy(alpha = 0.35f)
                        )

                        if (bypassRules.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Список пуст\nДобавьте домен или IP",
                                    textAlign = TextAlign.Center,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                items(bypassRules, key = { it }) { rule ->
                                    BypassRuleRow(
                                        rule = rule,
                                        enabled = !bypassBusy,
                                        onRemove = { removeBypassRule(rule) }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = colors.outlineVariant.copy(alpha = 0.22f)
                                    )
                                }
                            }
                        }
                    }
                }

                ExceptionsPane.Apps -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        BypassSearchBar(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 14.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    "Режим",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (isWhitelist) "БС: только выбранные через VPN"
                                    else "ЧС: выбранные мимо VPN",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            SingleChoiceSegmentedButtonRow {
                                SegmentedButton(
                                    selected = !isWhitelist,
                                    onClick = {
                                        if (isWhitelist) {
                                            scope.launch {
                                                settingsStore.saveExceptionsMode(false)
                                                delay(300)
                                                TunnelManager.reloadWireGuard()
                                            }
                                        }
                                    },
                                    enabled = isMigrationReady,
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = colors.primary,
                                        activeContentColor = colors.onPrimary,
                                        inactiveContainerColor = Color.Transparent,
                                        inactiveContentColor = colors.onSurfaceVariant
                                    )
                                ) { Text("ЧС", style = MaterialTheme.typography.labelMedium) }
                                SegmentedButton(
                                    selected = isWhitelist,
                                    onClick = {
                                        if (!isWhitelist) {
                                            scope.launch {
                                                settingsStore.saveExceptionsMode(true)
                                                delay(300)
                                                TunnelManager.reloadWireGuard()
                                            }
                                        }
                                    },
                                    enabled = isMigrationReady,
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = colors.primary,
                                        activeContentColor = colors.onPrimary,
                                        inactiveContainerColor = Color.Transparent,
                                        inactiveContentColor = colors.onSurfaceVariant
                                    )
                                ) { Text("БС", style = MaterialTheme.typography.labelMedium) }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Системные",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = showSystemApps,
                                onCheckedChange = { showSystemApps = it }
                            )
                        }

                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))

                        if (!isMigrationReady || isLoading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            LazyColumn(
                                state = rememberLazyListState(),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                items(filteredApps, key = { it.packageName }) { app ->
                                    val isSelected = selectedPackages.contains(app.packageName)
                                    AppRow(
                                        app = app,
                                        isSelected = isSelected,
                                        onClick = {
                                            val newList = if (isSelected) {
                                                selectedPackages - app.packageName
                                            } else {
                                                selectedPackages + app.packageName
                                            }
                                            scope.launch {
                                                settingsStore.saveExcludedApps(newList.joinToString(","))
                                                TunnelManager.reloadWireGuard()
                                            }
                                        }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                                        color = colors.outlineVariant.copy(alpha = 0.22f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить сайты?") },
            text = { Text("Будут удалены все ${bypassRules.size} правил.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        persistBypass(emptyList())
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Отмена") }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Экспорт") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Что сохранить в файл?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    ScopeRadio(
                        label = "Всё",
                        subtitle = "Сайты и приложения",
                        selected = exportScope == BypassShare.Scope.All,
                        onClick = { exportScope = BypassShare.Scope.All }
                    )
                    ScopeRadio(
                        label = "Только сайты",
                        subtitle = "${bypassRules.size} правил",
                        selected = exportScope == BypassShare.Scope.Routes,
                        onClick = { exportScope = BypassShare.Scope.Routes }
                    )
                    ScopeRadio(
                        label = "Только приложения",
                        subtitle = "${selectedPackages.size} · ${if (isWhitelist) "БС" else "ЧС"}",
                        selected = exportScope == BypassShare.Scope.Apps,
                        onClick = { exportScope = BypassShare.Scope.Apps }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        pendingExportScope = exportScope
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                        val suffix = when (exportScope) {
                            BypassShare.Scope.All -> "all"
                            BypassShare.Scope.Routes -> "sites"
                            BypassShare.Scope.Apps -> "apps"
                        }
                        exportLauncher.launch("qwdtt_bypass_${suffix}_$stamp.json")
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showImportDialog) {
        val peek = importPeek
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                pendingImportJson = null
                importPeek = null
            },
            title = { Text("Импорт") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (peek != null) {
                        Text(
                            buildString {
                                if (peek.hasRoutes) append("${peek.routesCount} сайтов")
                                if (peek.hasRoutes && peek.hasApps) append(" · ")
                                if (peek.hasApps) {
                                    append("${peek.appsCount} прилож. (${if (peek.isWhitelist) "БС" else "ЧС"})")
                                }
                                if (peek.isPlainText) append(" · текстовый список")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("Что заменить в текущих настройках?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    ScopeRadio(
                        label = "Всё",
                        subtitle = "Сайты и приложения",
                        selected = importScope == BypassShare.Scope.All,
                        enabled = peek?.hasRoutes == true && peek.hasApps,
                        onClick = { importScope = BypassShare.Scope.All }
                    )
                    ScopeRadio(
                        label = "Только сайты",
                        subtitle = "Приложения не трогать",
                        selected = importScope == BypassShare.Scope.Routes,
                        enabled = peek?.hasRoutes == true,
                        onClick = { importScope = BypassShare.Scope.Routes }
                    )
                    ScopeRadio(
                        label = "Только приложения",
                        subtitle = "Сайты не трогать",
                        selected = importScope == BypassShare.Scope.Apps,
                        enabled = peek?.hasApps == true,
                        onClick = { importScope = BypassShare.Scope.Apps }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val json = pendingImportJson
                        val chosen = importScope
                        showImportDialog = false
                        pendingImportJson = null
                        importPeek = null
                        if (json == null) return@TextButton
                        scope.launch {
                            shareBusy = true
                            bypassBusy = true
                            bypassHint = null
                            try {
                                val result = BypassShare.importJson(settingsStore, json, chosen)
                                val bypassBuild = if (
                                    chosen == BypassShare.Scope.All ||
                                    chosen == BypassShare.Scope.Routes
                                ) {
                                    withContext(Dispatchers.IO) {
                                        val raw = settingsStore.bypassRoutes.first()
                                        BypassRoutes.buildAllowedIps(raw, context)
                                    }
                                } else null
                                // Иначе остаётся старая подсказка «Обход сайтов выключен»
                                // до пересоздания вкладки.
                                if (chosen != BypassShare.Scope.Apps) {
                                    pane = ExceptionsPane.Routes
                                    bypassHint = when {
                                        bypassBuild?.unresolved?.isNotEmpty() == true ->
                                            "Не резолвится: ${bypassBuild.unresolved.take(2).joinToString()}"
                                        bypassBuild?.truncated == true ->
                                            "Список урезан — слишком много адресов"
                                        else -> null
                                    }
                                }
                                TunnelManager.reloadWireGuard()
                                val warn = buildString {
                                    if (result.routesTruncated) {
                                        append(" ⚠ до ${BypassRoutes.MAX_RULES} сайтов")
                                    }
                                    if (bypassBuild?.truncated == true) {
                                        append(" ⚠ часть IP не влезла")
                                    }
                                }
                                val summary = when (result.scope) {
                                    BypassShare.Scope.All ->
                                        "${result.routes} сайтов, ${result.apps} прилож. (${if (result.isWhitelist) "БС" else "ЧС"})"
                                    BypassShare.Scope.Routes -> "${result.routes} сайтов"
                                    BypassShare.Scope.Apps ->
                                        "${result.apps} прилож. (${if (result.isWhitelist) "БС" else "ЧС"})"
                                }
                                Toast.makeText(context, "Импорт: $summary$warn", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                shareBusy = false
                                bypassBusy = false
                            }
                        }
                    }
                ) { Text("Импортировать") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        pendingImportJson = null
                        importPeek = null
                    }
                ) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun BypassInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    canAdd: Boolean,
    busy: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(52.dp),
        shape = BypassControlShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.55f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colors.onSurface,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                "домен, *.site.ru или IP…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                                fontSize = 14.sp
                            )
                        }
                        inner()
                    }
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(colors.outlineVariant.copy(alpha = 0.45f))
            )
            IconButton(
                onClick = onAdd,
                enabled = canAdd,
                modifier = Modifier.size(52.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary
                    )
                } else {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Добавить",
                        tint = if (canAdd) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BypassSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(48.dp),
        shape = BypassControlShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.55f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                "Поиск…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant.copy(alpha = 0.65f)
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}

@Composable
private fun ScopeRadio(
    label: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }
    }
}

@Composable
private fun BypassRuleRow(
    rule: String,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rule,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.1.sp
            ),
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Удалить",
                modifier = Modifier.size(16.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
fun AppRow(app: AppItem, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = colors.primary)
            )
        }
    }
}
