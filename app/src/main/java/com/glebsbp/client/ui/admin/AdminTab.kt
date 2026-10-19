package com.glebsbp.client.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebsbp.client.AdminApiClient
import com.glebsbp.client.ManagedServer
import com.glebsbp.client.GlebColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessListScreen(server: ManagedServer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val deployIp = server.ip
    val deployMainPassword = server.adminApiToken
    val serverPort = 56002
    val adminCertPin = server.adminCertPin

    AdminApiClient.configureServer(deployIp, adminCertPin)

    var passwords by remember { mutableStateOf<List<AdminApiClient.AdminPassword>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AdminApiClient.AdminPassword?>(null) }
    var busyPassword by remember { mutableStateOf<String?>(null) }
    var detailsEntry by remember { mutableStateOf<AdminApiClient.AdminPassword?>(null) }
    var editEntry by remember { mutableStateOf<AdminApiClient.AdminPassword?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val filteredPasswords = remember(passwords, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) passwords else passwords.filter { entry ->
            entry.label.contains(q, ignoreCase = true) ||
                entry.password.contains(q, ignoreCase = true) ||
                entry.vkHash.contains(q, ignoreCase = true)
        }
    }

    fun refresh() {
        if (deployIp.isBlank() || deployMainPassword.isBlank() || adminCertPin.isBlank()) return
        isLoading = true
        errorMessage = null
        scope.launch {
            when (val result = AdminApiClient.listPasswords(deployIp, serverPort, deployMainPassword)) {
                is AdminApiClient.Result.Success -> {
                    passwords = result.value
                    errorMessage = null
                }
                is AdminApiClient.Result.Failure -> {
                    errorMessage = result.message
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(deployIp, deployMainPassword, adminCertPin, serverPort) {
        refresh()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (deployIp.isBlank() || deployMainPassword.isBlank() || adminCertPin.isBlank()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Для этого сервера ещё не настроена защищённая админ-панель — обновите сервер через деплой",
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(30.dp))
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.widthIn(max = 304.dp).fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("Назад к списку серверов")
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        server.name.ifBlank { server.ip },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { refresh() }, enabled = !isLoading) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Обновить",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showCreateSheet = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Создать пароль",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    placeholder = { Text("Поиск") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Очистить")
                            }
                        }
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                )

                errorMessage?.let { msg ->
                    AppSectionCard(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            "Не удалось загрузить: $msg",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                if (isLoading && passwords.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (passwords.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Паролей пока нет",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (filteredPasswords.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Ничего не найдено по запросу «$searchQuery»",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(filteredPasswords, key = { it.password }) { entry ->
                            AdminPasswordCard(
                                entry = entry,
                                isBusy = busyPassword == entry.password,
                                onOpenDetails = { detailsEntry = entry },
                                onCopy = { clipboard.setText(AnnotatedString(entry.password)) },
                                onToggleActive = {
                                    busyPassword = entry.password
                                    scope.launch {
                                        val result = if (entry.isDeactivated) {
                                            AdminApiClient.activatePassword(deployIp, serverPort, deployMainPassword, entry.password)
                                        } else {
                                            AdminApiClient.deactivatePassword(deployIp, serverPort, deployMainPassword, entry.password)
                                        }
                                        if (result is AdminApiClient.Result.Failure) {
                                            errorMessage = result.message
                                        }
                                        busyPassword = null
                                        refresh()
                                    }
                                },
                                onDelete = { pendingDelete = entry },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(96.dp)) }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        PasswordFormSheet(
            existing = null,
            onDismiss = { showCreateSheet = false },
            onSubmit = { label, vkHash, days, maxDevices ->
                scope.launch {
                    val result = AdminApiClient.createPassword(deployIp, serverPort, deployMainPassword, vkHash, days ?: 30, maxDevices, label)
                    if (result is AdminApiClient.Result.Failure) {
                        errorMessage = result.message
                    } else {
                        showCreateSheet = false
                    }
                    refresh()
                }
            }
        )
    }

    editEntry?.let { entry ->
        PasswordFormSheet(
            existing = entry,
            onDismiss = { editEntry = null },
            onSubmit = { label, vkHash, days, maxDevices ->
                scope.launch {
                    val result = AdminApiClient.updatePassword(
                        host = deployIp,
                        port = serverPort,
                        adminPassword = deployMainPassword,
                        password = entry.password,
                        label = label,
                        vkHash = vkHash,
                        maxDevices = maxDevices,
                        days = days,
                    )
                    if (result is AdminApiClient.Result.Failure) {
                        errorMessage = result.message
                    } else {
                        editEntry = null
                        // detailsEntry могла быть открыта под этой формой — обновим её тоже.
                        (result as? AdminApiClient.Result.Success)?.value?.let { detailsEntry = it }
                    }
                    refresh()
                }
            }
        )
    }

    detailsEntry?.let { entry ->
        PasswordDetailsSheet(
            entry = entry,
            host = deployIp,
            port = serverPort,
            adminPassword = deployMainPassword,
            onDismiss = { detailsEntry = null },
            onEdit = {
                detailsEntry = null
                editEntry = entry
            },
            onEntryUpdated = { updated ->
                detailsEntry = updated
                passwords = passwords.map { if (it.password == updated.password) updated else it }
            },
            onError = { msg -> errorMessage = msg },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить пароль?") },
            text = { Text("Пароль «${entry.label}» и все привязанные устройства будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = entry.password
                    pendingDelete = null
                    busyPassword = target
                    scope.launch {
                        val result = AdminApiClient.deletePassword(deployIp, serverPort, deployMainPassword, target)
                        if (result is AdminApiClient.Result.Failure) {
                            errorMessage = result.message
                        }
                        busyPassword = null
                        refresh()
                    }
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun AdminPasswordCard(
    entry: AdminApiClient.AdminPassword,
    isBusy: Boolean,
    onOpenDetails: () -> Unit,
    onCopy: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val expiresText = if (entry.expiresAt > 0) {
        dateFormat.format(Date(entry.expiresAt * 1000))
    } else {
        "бессрочно"
    }

    // Заголовок — label, если он есть, иначе сам пароль. Строку с паролем
    // ниже показываем только когда заголовок — это НЕ пароль (иначе одна и
    // та же строка дублировалась бы дважды подряд).
    val hasDistinctLabel = entry.label.isNotBlank()
    val titleText = if (hasDistinctLabel) entry.label else entry.password

    AppSectionCard(
        modifier = Modifier
            .animateContentSize()
            .clickable(onClick = onOpenDetails),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        titleText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.isDeactivated) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusChip("выключен", GlebColors.warning)
                    } else if (entry.activeDevices > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusChip("активен", GlebColors.connected)
                    }
                }
                if (hasDistinctLabel) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        entry.password,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Скопировать пароль",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            InfoStat(label = "Истекает", value = expiresText, modifier = Modifier.weight(1f))
            InfoStat(label = "Устройства", value = "${entry.deviceIds.size}/${entry.maxDevices}", modifier = Modifier.weight(1f))
            InfoStat(label = "Трафик", value = formatBytes(entry.upBytes + entry.downBytes), modifier = Modifier.weight(1f))
        }

        if (isBusy) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onToggleActive,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            if (entry.isDeactivated) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            if (entry.isDeactivated) "Включить" else "Выключить",
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                    ),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            "Удалить",
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun InfoStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 МБ"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) "%.2f ГБ".format(mb / 1024.0) else "%.1f МБ".format(mb)
}

/**
 * Форма создания/редактирования пароля доступа — один и тот же UI для
 * обоих случаев, различается только заголовком/кнопкой и тем, что при
 * редактировании поля предзаполнены текущими значениями. days трактуется
 * как "продлить ещё на N дней от сейчас" в обоих случаях — так же, как
 * работает /new в Telegram-боте.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordFormSheet(
    existing: AdminApiClient.AdminPassword?,
    onDismiss: () -> Unit,
    onSubmit: (label: String, vkHash: String, days: Int?, maxDevices: Int) -> Unit,
) {
    val isEdit = existing != null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var label by rememberSaveable(existing?.password) { mutableStateOf(existing?.label.orEmpty()) }
    // Сервер хранит и принимает несколько VK-хешей одной строкой через
    // запятую (см. go_client/group.go ParseHashes) — в форме это список для
    // удобного добавления/удаления по одному, при отправке склеивается
    // обратно в CSV-строку, формат на проводе не меняется.
    var hashList by rememberSaveable(existing?.password) {
        mutableStateOf(
            existing?.vkHash.orEmpty()
                .split(',', ';', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("") }
        )
    }
    var daysInput by rememberSaveable(existing?.password) { mutableStateOf(if (isEdit) "" else "30") }
    var maxDevicesInput by rememberSaveable(existing?.password) {
        mutableStateOf((existing?.maxDevices ?: 1).toString())
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (isEdit) "Редактировать доступ" else "Новый пароль доступа",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Имя (кому выдан)") },
                placeholder = { Text("например, Иван Петров") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "VK хеши",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                hashList.forEachIndexed { index, value ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { new ->
                                hashList = hashList.toMutableList().also { it[index] = new }
                            },
                            label = { Text(if (hashList.size > 1) "Хеш ${index + 1}" else "VK хеш") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (hashList.size > 1) {
                            IconButton(onClick = {
                                hashList = hashList.toMutableList().also { it.removeAt(index) }
                            }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Удалить хеш",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = { hashList = hashList + "" }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Добавить хеш")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = daysInput,
                    onValueChange = { value -> if (value.all { it.isDigit() } && value.length <= 3) daysInput = value },
                    label = { Text(if (isEdit) "Продлить (дней)" else "Дней") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = maxDevicesInput,
                    onValueChange = { value -> if (value.all { it.isDigit() } && value.length <= 2) maxDevicesInput = value },
                    label = { Text("Устройств") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            if (isEdit) {
                Text(
                    "Оставьте «Продлить» пустым, чтобы не менять срок действия.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val combinedHashes = hashList.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

            Button(
                onClick = {
                    val days = if (isEdit) {
                        daysInput.toIntOrNull()?.coerceIn(1, 365)
                    } else {
                        daysInput.toIntOrNull()?.coerceIn(1, 365) ?: 30
                    }
                    val maxDevices = maxDevicesInput.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    onSubmit(label.trim(), combinedHashes, days, maxDevices)
                },
                enabled = combinedHashes.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isEdit) "Сохранить" else "Создать")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Клик по карточке — сразу готовые ссылки GLEBSBP (glebsbp://config, наш
 * основной формат с QR) и легаси GLEBSBP (glebsbp://host:port:port:port:pass:hash,
 * см. parseQrConfig в ProfilesTab.kt) плюс QR-код на первую из них, чтобы
 * не объяснять человеку вручную, что вводить в приложение.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordDetailsSheet(
    entry: AdminApiClient.AdminPassword,
    host: String,
    port: Int,
    adminPassword: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onEntryUpdated: (AdminApiClient.AdminPassword) -> Unit,
    onError: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var qrBitmap by remember(entry.password) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var unbindingDeviceId by remember(entry.password) { mutableStateOf<String?>(null) }

    val glebsbpLink = remember(entry, host, port) {
        val peer = "$host:$port"
        val nameEsc = URLEncoder.encode(entry.label.ifBlank { entry.password }, "UTF-8")
        val peerEsc = URLEncoder.encode(peer, "UTF-8")
        val hashesEsc = URLEncoder.encode(entry.vkHash, "UTF-8")
        val passEsc = URLEncoder.encode(entry.password, "UTF-8")
        "glebsbp://config?name=$nameEsc&peer=$peerEsc&hashes=$hashesEsc&workers=18&port=9000&pass=$passEsc"
    }
    val legacyLink = remember(entry, host, port) {
        // glebsbp://<server_ip>:<dtls_port>:<wg_port>:<local_port>:<password>:<vk_hash>
        // wg_port/local_port тут неизвестны админ-API — используем те же
        // дефолты, что и остальной клиент (56001/9000).
        "glebsbp://$host:$port:56001:9000:${entry.password}:${entry.vkHash}"
    }

    LaunchedEffect(glebsbpLink) {
        scope.launch(Dispatchers.Default) {
            val bmp = generateQrCode(glebsbpLink, 600)
            withContext(Dispatchers.Main) { qrBitmap = bmp }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.label.ifBlank { entry.password },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Редактировать",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = qrBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "QR-код для подключения",
                        modifier = Modifier
                            .size(220.dp)
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                    )
                } else {
                    Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            LinkRow(
                label = "Ссылка GLEBSBP",
                value = glebsbpLink,
                onCopy = {
                    clipboard.setText(AnnotatedString(glebsbpLink))
                },
            )
            LinkRow(
                label = "Ссылка GLEBSBP (легаси)",
                value = legacyLink,
                onCopy = {
                    clipboard.setText(AnnotatedString(legacyLink))
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            InfoStat(label = "Пароль", value = entry.password)
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                InfoStat(
                    label = "Устройства",
                    value = "${entry.deviceIds.size}/${entry.maxDevices}",
                    modifier = Modifier.weight(1f),
                )
                InfoStat(
                    label = "Трафик",
                    value = formatBytes(entry.upBytes + entry.downBytes),
                    modifier = Modifier.weight(1f),
                )
            }

            if (entry.deviceIds.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "Привязанные устройства",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    entry.deviceIds.forEach { deviceId ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                deviceId,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (unbindingDeviceId == deviceId) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(
                                    onClick = {
                                        unbindingDeviceId = deviceId
                                        scope.launch {
                                            val result = AdminApiClient.unbindDevice(host, port, adminPassword, entry.password, deviceId)
                                            when (result) {
                                                is AdminApiClient.Result.Success -> onEntryUpdated(result.value)
                                                is AdminApiClient.Result.Failure -> onError(result.message)
                                            }
                                            unbindingDeviceId = null
                                        }
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Открепить устройство",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LinkRow(label: String, value: String, onCopy: () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Скопировать",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
