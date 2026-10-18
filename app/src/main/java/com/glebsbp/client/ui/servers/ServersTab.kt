package com.glebsbp.client.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glebsbp.client.ManagedServer
import com.glebsbp.client.ServersStore
import kotlinx.coroutines.launch

private sealed class ServersTabScreen {
    object ServerList : ServersTabScreen()
    data class ServerOverview(val serverId: String) : ServersTabScreen()
    data class AccessList(val serverId: String) : ServersTabScreen()
    data class ServerDeploy(val serverId: String?) : ServersTabScreen()
}

@Composable
fun ServersTab() {
    val context = LocalContext.current
    val serversStore = remember { ServersStore(context) }

    var screen by rememberSaveable(stateSaver = ServersTabScreenSaver) {
        mutableStateOf<ServersTabScreen>(ServersTabScreen.ServerList)
    }

    // На экранах конкретного сервера системный жест «Назад» должен вернуться
    // к списку серверов, а не закрыть Activity.
    BackHandler(enabled = screen !is ServersTabScreen.ServerList) {
        screen = when (val current = screen) {
            is ServersTabScreen.AccessList -> ServersTabScreen.ServerOverview(current.serverId)
            is ServersTabScreen.ServerDeploy -> current.serverId
                ?.let { ServersTabScreen.ServerOverview(it) }
                ?: ServersTabScreen.ServerList
            is ServersTabScreen.ServerOverview -> ServersTabScreen.ServerList
            is ServersTabScreen.ServerList -> ServersTabScreen.ServerList
        }
    }

    Crossfade(targetState = screen, label = "servers_tab_content") { current ->
        when (val s = current) {
            is ServersTabScreen.ServerList -> ServerListScreen(
                serversStore = serversStore,
                onOpenServer = { id -> screen = ServersTabScreen.ServerOverview(id) },
                onAddServer = { screen = ServersTabScreen.ServerDeploy(null) },
            )
            is ServersTabScreen.ServerOverview -> ServerOverviewHost(
                serversStore = serversStore,
                serverId = s.serverId,
                onOpenAccess = { screen = ServersTabScreen.AccessList(s.serverId) },
                onOpenDeploy = { screen = ServersTabScreen.ServerDeploy(s.serverId) },
                onBack = { screen = ServersTabScreen.ServerList },
            )
            is ServersTabScreen.AccessList -> AccessListHost(
                serversStore = serversStore,
                serverId = s.serverId,
                onBack = { screen = ServersTabScreen.ServerOverview(s.serverId) },
            )
            is ServersTabScreen.ServerDeploy -> DeployScreen(
                initialServerId = s.serverId,
                onBack = { screen = s.serverId?.let { ServersTabScreen.ServerOverview(it) } ?: ServersTabScreen.ServerList },
            )
        }
    }
}

/**
 * Простой saver для sealed-состояния навигации: сериализуем в пару
 * (тип, id-или-null) строками — тот же подход, что и остальной rememberSaveable
 * в этом файле, без внешних зависимостей вроде kotlinx.serialization.
 */
private val ServersTabScreenSaver = androidx.compose.runtime.saveable.Saver<ServersTabScreen, List<String>>(
    save = { state ->
        when (state) {
            is ServersTabScreen.ServerList -> listOf("list")
            is ServersTabScreen.ServerOverview -> listOf("overview", state.serverId)
            is ServersTabScreen.AccessList -> listOf("access", state.serverId)
            is ServersTabScreen.ServerDeploy -> listOf("deploy", state.serverId ?: "")
        }
    },
    restore = { saved ->
        when (saved.getOrNull(0)) {
            "overview" -> ServersTabScreen.ServerOverview(saved.getOrElse(1) { "" })
            "access" -> ServersTabScreen.AccessList(saved.getOrElse(1) { "" })
            "deploy" -> ServersTabScreen.ServerDeploy(saved.getOrNull(1)?.ifEmpty { null })
            else -> ServersTabScreen.ServerList
        }
    }
)

/**
 * Ждём появления сервера с нужным id в потоке ServersStore.servers (после
 * навигации из ServerList он гарантированно там уже есть) и передаём его в
 * AccessListScreen. Если сервер вдруг исчез (удалили в другом месте) —
 * откатываемся на список.
 */
@Composable
private fun AccessListHost(
    serversStore: ServersStore,
    serverId: String,
    onBack: () -> Unit,
) {
    val servers by serversStore.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val server = servers.find { it.id == serverId }
    LaunchedEffect(servers, serverId) {
        if (servers.isNotEmpty() && server == null) {
            onBack()
        }
    }

    if (server != null) {
        AccessListScreen(server = server, onBack = onBack)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ServerOverviewHost(
    serversStore: ServersStore,
    serverId: String,
    onOpenAccess: () -> Unit,
    onOpenDeploy: () -> Unit,
    onBack: () -> Unit,
) {
    val servers by serversStore.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val server = servers.find { it.id == serverId }
    val scope = rememberCoroutineScope()
    var showActions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    LaunchedEffect(servers, serverId) {
        if (servers.isNotEmpty() && server == null) onBack()
    }

    if (server == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        ServerOverviewScreen(
            server = server,
            onOpenAccess = onOpenAccess,
            onOpenDeploy = onOpenDeploy,
            onBack = onBack,
            showActions = showActions,
            onShowActions = { showActions = it },
            onRename = { showRename = true },
            onDelete = { showDeleteConfirm = true },
        )
        if (showRename) {
            SaveServerNameDialog(
                initialName = server.name.ifBlank { server.ip },
                onDismiss = { showRename = false },
                onConfirm = { name ->
                    scope.launch { serversStore.updateServer(server.copy(name = name)) }
                    showRename = false
                }
            )
        }
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Удалить сервер?") },
                text = { Text("Из приложения будут удалены только данные подключения. Сервер и созданные на нём доступы останутся без изменений.") },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch { serversStore.deleteServer(server.id) }
                            showDeleteConfirm = false
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Удалить") }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") } },
            )
        }
    }
}

@Composable
private fun ServerOverviewScreen(
    server: ManagedServer,
    onOpenAccess: () -> Unit,
    onOpenDeploy: () -> Unit,
    onBack: () -> Unit,
    showActions: Boolean,
    onShowActions: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад к серверам",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Управление сервером",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    server.ip,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { onShowActions(true) }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Действия с сервером",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showActions,
                    onDismissRequest = { onShowActions(false) },
                    modifier = Modifier.width(216.dp).padding(vertical = 4.dp),
                    shape = RoundedCornerShape(22.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                ) {
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = 54.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        text = { Text("Переименовать", fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            onShowActions(false)
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = 54.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        text = { Text("Удалить", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            onShowActions(false)
                            onDelete()
                        },
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                AppSectionCard(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                Icons.Filled.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp).size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.ip, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "SSH ${server.sshPort}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (server.manualPortsEnabled) {
                        Text(
                            "DTLS ${server.dtlsPort}  ·  WireGuard ${server.wgPort}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text(
                    "Действия",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            item {
                ServerActionCard(
                    icon = Icons.Filled.People,
                    title = "Пользователи и подписки",
                    description = "Доступы, лимиты устройств и сроки действия",
                    onClick = onOpenAccess,
                )
            }
            item {
                ServerActionCard(
                    icon = Icons.Filled.Settings,
                    title = "Управление сервером",
                    description = "SSH, порты, обновление и переустановка",
                    onClick = onOpenDeploy,
                )
            }
        }
    }
}

@Composable
private fun ServerActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    AppSectionCard(
        modifier = Modifier.clickable(onClick = onClick),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerListScreen(
    serversStore: ServersStore,
    onOpenServer: (String) -> Unit,
    onAddServer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { serversStore.migrateLegacyServerIfNeeded() }
    val servers by serversStore.servers.collectAsStateWithLifecycle(initialValue = emptyList())

    var multiSelectMode by rememberSaveable { mutableStateOf(false) }
    var selectedForDeploy by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var showMultiDeployConfirm by remember { mutableStateOf(false) }
    var multiDeployResults by remember { mutableStateOf<Map<String, DeployOutcome>>(emptyMap()) }
    var isMultiDeploying by remember { mutableStateOf(false) }
    var activeDeployingServerId by remember { mutableStateOf<String?>(null) }
    var multiDeployJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedForDeploy = emptySet()
        multiDeployResults = emptyMap()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (multiSelectMode) "Выберите серверы" else "Серверы",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (multiSelectMode) "Выбрано: ${selectedForDeploy.size}" else "Управление вашими VPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (multiSelectMode) {
                    TextButton(onClick = ::exitMultiSelect) {
                        Text("Отмена")
                    }
                }
            }

            if (servers.size > 1 && !multiSelectMode) {
                OutlinedButton(
                    onClick = { multiSelectMode = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Обновить несколько серверов", fontWeight = FontWeight.SemiBold)
                }
            }

            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.height(26.dp))
                        Text(
                            "Добавьте первый VPS, чтобы установить сервер и управлять пользователями",
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(30.dp))
                        Button(
                            onClick = onAddServer,
                            modifier = Modifier.widthIn(max = 304.dp).fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Добавить сервер")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 4.dp,
                        bottom = if (multiSelectMode) 24.dp else 104.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(servers, key = { it.id }) { server ->
                        if (multiSelectMode) {
                            ServerCard(
                                server = server,
                                multiSelectMode = true,
                                isChecked = selectedForDeploy.contains(server.id),
                                onCheckedChange = { checked ->
                                    selectedForDeploy = if (checked) selectedForDeploy + server.id else selectedForDeploy - server.id
                                },
                                onOpenServer = { onOpenServer(server.id) },
                            )
                        } else {
                            ServerCard(
                                server = server,
                                multiSelectMode = false,
                                isChecked = false,
                                onCheckedChange = {},
                                onOpenServer = { onOpenServer(server.id) },
                            )
                        }
                    }
                }
            }

            if (multiSelectMode && selectedForDeploy.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Button(
                        onClick = { showMultiDeployConfirm = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                    ) {
                        Text("Установить на выбранные (${selectedForDeploy.size})", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (!multiSelectMode && servers.isNotEmpty()) {
            FloatingActionButton(
                onClick = onAddServer,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 22.dp).size(58.dp),
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить сервер")
            }
        }
    }

    if (showMultiDeployConfirm) {
        val targets = servers.filter { selectedForDeploy.contains(it.id) }
        MultiDeployConfirmDialog(
            servers = targets,
            onDismiss = { showMultiDeployConfirm = false },
            onConfirm = {
                showMultiDeployConfirm = false
                val appContext = context.applicationContext
                multiDeployResults = targets.associate { it.id to DeployOutcome.InProgress }
                multiDeployJob = scope.launch {
                    isMultiDeploying = true
                    // DEPLOY_START/DEPLOY_STOP держат foreground-сервис и wake
                    // lock живыми на время деплоя — рассчитаны на ОДИН SSH-сеанс.
                    // Раньше они дёргались на каждый сервер внутри цикла, из-за
                    // чего DEPLOY_STOP после первого сервера гасил foreground-
                    // защиту (stopTunnel(), если туннель не запущен) ещё до
                    // того, как второй сервер начинал разворачиваться — систему
                    // успевало подморозить процесс между серверами, и второй
                    // деплой либо не стартовал, либо тихо обрывался. Теперь
                    // держим сервис живым один раз на ВЕСЬ мультидеплой.
                    val startIntent = Intent(appContext, com.glebsbp.client.TunnelService::class.java).apply { action = "DEPLOY_START" }
                    if (Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(startIntent) else appContext.startService(startIntent)
                    try {
                        performMultiDeploy(
                            context = appContext,
                            servers = targets,
                            onServerStarted = { id ->
                                activeDeployingServerId = id
                                multiDeployResults = multiDeployResults + (id to DeployOutcome.InProgress)
                            },
                            onServerFinished = { id, outcome ->
                                multiDeployResults = multiDeployResults + (id to outcome)
                            },
                        )
                    } finally {
                        isMultiDeploying = false
                        activeDeployingServerId = null
                        multiDeployJob = null
                        try {
                            appContext.startService(Intent(appContext, com.glebsbp.client.TunnelService::class.java).apply { action = "DEPLOY_STOP" })
                        } catch (_: Exception) {}
                        exitMultiSelect()
                    }
                }
            }
        )
    }

    if (isMultiDeploying) {
        val targets = servers.filter { selectedForDeploy.contains(it.id) }
        MultiDeployBlockingDialog(
            servers = targets,
            results = multiDeployResults,
            activeServerId = activeDeployingServerId,
            onCancel = { multiDeployJob?.cancel() },
        )
    }

}

@Composable
private fun ServerCard(
    server: ManagedServer,
    multiSelectMode: Boolean,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenServer: () -> Unit,
) {
    AppSectionCard(
        modifier = Modifier.clickable {
            if (multiSelectMode) onCheckedChange(!isChecked) else onOpenServer()
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multiSelectMode) {
                Checkbox(checked = isChecked, onCheckedChange = onCheckedChange)
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        Icons.Filled.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(11.dp).size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name.ifBlank { server.ip },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server.name.isNotBlank() && server.name != server.ip) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        server.ip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (server.manualPortsEnabled) "SSH ${server.sshPort} · WG ${server.wgPort}" else "SSH ${server.sshPort}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!multiSelectMode) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Открыть сервер",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MultiDeployBlockingDialog(
    servers: List<ManagedServer>,
    results: Map<String, DeployOutcome>,
    activeServerId: String?,
    onCancel: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Установка на серверы",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Не закрывайте приложение, пока идёт установка",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    servers.forEach { server ->
                        val outcome = results[server.id] ?: DeployOutcome.InProgress
                        MultiDeployStatusRow(
                            name = server.name.ifBlank { server.ip },
                            outcome = outcome,
                            showLiveProgress = server.id == activeServerId,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Отменить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
