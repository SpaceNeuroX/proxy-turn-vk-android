package com.wdtt.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.PeerAddress
import com.wdtt.client.BypassRoutes
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.TunnelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wdtt.client.VkAuthWebViewManager
import com.wdtt.client.ManlCaptchaWebViewManager
import kotlin.math.roundToInt
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.scale
import com.wdtt.client.NotificationHelper
import com.wdtt.client.isNewerVersion
import com.wdtt.client.stripVkUrlStatic

private const val WORKERS_PER_GROUP = 9

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    isDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    currentPalette: String,
    onPaletteChange: (String) -> Unit,
    onConnectRequested: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(currentDensity.density, fontScale = 1f)
    ) {
        SettingsTabContent(
            context = context,
            scope = scope,
            settingsStore = settingsStore,
            themeMode = themeMode,
            onThemeChange = onThemeChange,
            isDynamicColor = isDynamicColor,
            onDynamicColorChange = onDynamicColorChange,
            currentPalette = currentPalette,
            onPaletteChange = onPaletteChange,
            onConnectRequested = onConnectRequested,
            onOpenProfiles = onOpenProfiles,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabContent(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    settingsStore: SettingsStore,
    themeMode: String,
    onThemeChange: (String) -> Unit,
    isDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    currentPalette: String,
    onPaletteChange: (String) -> Unit,
    onConnectRequested: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
) {
    val savedConnectionPassword by settingsStore.connectionPassword.collectAsStateWithLifecycle(initialValue = "")
    val savedManualPortsEnabled by settingsStore.manualPortsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedServerDtlsPort by settingsStore.serverDtlsPort.collectAsStateWithLifecycle(initialValue = 56000)
    val savedServerWgPort by settingsStore.serverWgPort.collectAsStateWithLifecycle(initialValue = 56001)
    val savedListenPort by settingsStore.listenPort.collectAsStateWithLifecycle(initialValue = 9000)

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelConnecting by TunnelManager.isConnecting.collectAsStateWithLifecycle()
    val tunnelReconnecting by TunnelManager.isReconnecting.collectAsStateWithLifecycle()
    tunnelRunning || tunnelConnecting
    var connectCancelArmed by remember { mutableStateOf(false) }
    LaunchedEffect(tunnelConnecting, tunnelRunning) {
        connectCancelArmed = false
        if (tunnelConnecting && !tunnelRunning) {
            delay(2_500)
            if (TunnelManager.isConnecting.value && !TunnelManager.running.value) {
                connectCancelArmed = true
            }
        }
    }
    val autoSwitchToLogs by settingsStore.autoSwitchToLogs.collectAsStateWithLifecycle(initialValue = true)
    val stopOnWifi by settingsStore.stopOnWifi.collectAsStateWithLifecycle(initialValue = false)
    val bypassAutoRefresh by settingsStore.bypassAutoRefresh.collectAsStateWithLifecycle(initialValue = true)
    val connectionPipelineEnabled by settingsStore.connectionPipelineEnabled.collectAsStateWithLifecycle(initialValue = true)
    val connectionMode by settingsStore.connectionMode.collectAsStateWithLifecycle(
        initialValue = SettingsStore.CONNECTION_MODE_VPN
    )
    val socksPort by settingsStore.socksPort.collectAsStateWithLifecycle(
        initialValue = SettingsStore.DEFAULT_SOCKS_PORT
    )
    var socksPortInput by rememberSaveable { mutableStateOf(SettingsStore.DEFAULT_SOCKS_PORT.toString()) }
    val socksAuthEnabled by settingsStore.socksAuthEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedSocksUsername by settingsStore.socksUsername.collectAsStateWithLifecycle(initialValue = "")
    val savedSocksPassword by settingsStore.socksPassword.collectAsStateWithLifecycle(initialValue = "")
    var socksUsernameInput by rememberSaveable { mutableStateOf("") }
    var socksPasswordInput by rememberSaveable { mutableStateOf("") }
    val turnTransport by settingsStore.turnTransport.collectAsStateWithLifecycle(
        initialValue = SettingsStore.TURN_TRANSPORT_TCP
    )
    val savedTurnHosts by settingsStore.turnHosts.collectAsStateWithLifecycle(initialValue = "")
    var turnHostsInput by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(savedTurnHosts) {
        turnHostsInput = savedTurnHosts
    }
    val serverRawPort by settingsStore.serverRawPort.collectAsStateWithLifecycle(initialValue = 56003)
    var serverRawPortInput by rememberSaveable { mutableStateOf("56003") }
    LaunchedEffect(serverRawPort) {
        serverRawPortInput = serverRawPort.toString()
    }
    val detailedLogs by settingsStore.detailedLogs.collectAsStateWithLifecycle(initialValue = false)
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = com.wdtt.client.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
    )
    val includeBetaUpdates by settingsStore.includeBetaUpdates.collectAsStateWithLifecycle(initialValue = false)
    val subscriptionAutoRefreshHours by settingsStore.subscriptionAutoRefreshHours.collectAsStateWithLifecycle(
        initialValue = SettingsStore.DEFAULT_SUB_AUTO_REFRESH_HOURS
    )
    var subAutoRefreshMenuExpanded by remember { mutableStateOf(false) }

    val currentProfileId by settingsStore.currentProfileId.collectAsStateWithLifecycle(initialValue = "")
    val currentProfileName by settingsStore.currentProfileName.collectAsStateWithLifecycle(initialValue = "")
    val savedPeer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")
    val savedWorkers by settingsStore.workersPerHash.collectAsStateWithLifecycle(initialValue = 18)

    val profilesStore = remember { com.wdtt.client.ProfilesStore(context) }
    val profiles by profilesStore.profiles.collectAsStateWithLifecycle(initialValue = emptyList())

    val cooldownSeconds by TunnelManager.cooldownSeconds.collectAsStateWithLifecycle()
    var wasRunning by remember { mutableStateOf(false) }

    LaunchedEffect(tunnelRunning) {
        if (wasRunning && !tunnelRunning) {
            TunnelManager.startCooldown(5)
        }
        wasRunning = tunnelRunning
    }

    var peerInput by rememberSaveable { mutableStateOf("") }
    var workersInput by rememberSaveable { mutableFloatStateOf(18f) }
    var showHashesDialog by rememberSaveable { mutableStateOf(false) }
    var autoCaptchaEnabled by rememberSaveable { mutableStateOf(true) }
    var useWVCaptcha by rememberSaveable { mutableStateOf(false) }
    var isManualMode by rememberSaveable { mutableStateOf(true) }
    var wbvManualMode by rememberSaveable { mutableStateOf(true) }
    var vkAccountAuth by rememberSaveable { mutableStateOf(false) }
    var vkAuthBusy by remember { mutableStateOf(false) }
    var vkLoggedIn by remember { mutableStateOf(false) }
    var manualPortsEnabled by rememberSaveable { mutableStateOf(false) }
    var serverDtlsPortInput by rememberSaveable { mutableStateOf("56000") }
    var serverWgPortInput by rememberSaveable { mutableStateOf("56001") }
    var showAppSettingsDialog by rememberSaveable { mutableStateOf(false) }
    val openAppSettingsRequest by TunnelManager.openAppSettingsRequest.collectAsStateWithLifecycle()
    var lastHandledOpenSettings by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(openAppSettingsRequest) {
        if (openAppSettingsRequest > 0L && openAppSettingsRequest != lastHandledOpenSettings) {
            lastHandledOpenSettings = openAppSettingsRequest
            showAppSettingsDialog = true
        }
    }

    val currentHashesRaw by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val uniqueHashes = remember(currentHashesRaw) { 
        currentHashesRaw.split(Regex("[,\\s\\n]+"))
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
    }
    val filledHashCount = uniqueHashes.size
    val combinedHashes = uniqueHashes.joinToString(",")
    val dynamicMaxWorkers = remember(filledHashCount, vkAccountAuth) {
        if (vkAccountAuth) SettingsStore.VK_ACCOUNT_MAX_WORKERS.toFloat()
        else SettingsStore.maxAnonymousWorkers(filledHashCount.coerceAtLeast(1)).toFloat()
    }

    val vkAnonPath by settingsStore.vkAnonPath.collectAsStateWithLifecycle(initialValue = "vkcalls")
    val goDnsPreset by settingsStore.goDnsPreset.collectAsStateWithLifecycle(initialValue = "yandex")
    val goDnsCustomStored by settingsStore.goDnsCustom.collectAsStateWithLifecycle(initialValue = "")
    val goDnsDohCustomStored by settingsStore.goDnsDohCustom.collectAsStateWithLifecycle(initialValue = "")
    val obfsMode by settingsStore.obfsMode.collectAsStateWithLifecycle(initialValue = "audio")
    val interfaceRole by settingsStore.interfaceRole.collectAsStateWithLifecycle(initialValue = "admin")
    LaunchedEffect(socksPort) {
        socksPortInput = socksPort.toString()
    }
    LaunchedEffect(savedSocksUsername) {
        socksUsernameInput = savedSocksUsername
    }
    LaunchedEffect(savedSocksPassword) {
        socksPasswordInput = savedSocksPassword
    }
    var goDnsCustomInput by rememberSaveable { mutableStateOf("") }
    var goDnsDohCustomInput by rememberSaveable { mutableStateOf("") }
    val useVKCallsAuth = !vkAnonPath.equals("legacy", ignoreCase = true)
    var portInput by rememberSaveable { mutableStateOf("9000") }
    var sniInput by rememberSaveable { mutableStateOf("") }



    val currentWorkers = if (vkAccountAuth) {
        workersInput.coerceIn(1f, dynamicMaxWorkers)
    } else {
        workersInput.coerceIn(WORKERS_PER_GROUP.toFloat(), dynamicMaxWorkers)
    }

    val hashErrors = remember(currentHashesRaw) {
        buildList {
            val parts = currentHashesRaw.split(Regex("[,\\s\\n]+")).filter { it.isNotEmpty() }
            parts.forEachIndexed { i, h ->
                if (h.isNotBlank() && h.length < 16) add("Хеш ${i + 1} — короткий")
            }
            val filled = parts.filter { it.isNotBlank() && it.length >= 16 }
            if (filled.size != filled.distinct().size) add("Есть дубликаты хешей")
        }
    }
    val hasInputHashErrors = hashErrors.isNotEmpty()

    var showSecretsDialog by rememberSaveable { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    fun normalizeHashes(vararg hashes: String): String {
        return hashes
            .map { stripVkUrlStatic(it) }
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
            .joinToString(",")
    }

    LaunchedEffect(Unit) {
        val peer = settingsStore.peer.first()
        val hashes = settingsStore.vkHashes.first()
        val workers = settingsStore.workersPerHash.first()
        val port = settingsStore.listenPort.first()
        val manualPorts = settingsStore.manualPortsEnabled.first()
        val serverDtlsPort = settingsStore.serverDtlsPort.first()
        val serverWgPort = settingsStore.serverWgPort.first()
        val sni = settingsStore.sni.first()
        val captchaMode = settingsStore.captchaMode.first()
        val captchaMethod = settingsStore.captchaSolveMethod.first()
        val wbvCaptchaMethod = settingsStore.captchaWbvSolveMethod.first()
        val vkAuthMode = settingsStore.vkAuthMode.first()
        
        val embeddedPort = PeerAddress.port(peer)
        peerInput = PeerAddress.host(peer)
        val initialHashesList = hashes.split(Regex("[,\\s\\n]+"))
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
        val initialHashesCount = initialHashesList.size.coerceAtLeast(1)
        workersInput = roundToGroup(
            workers.toFloat(),
            SettingsStore.maxAnonymousWorkers(initialHashesCount).toFloat()
        )
        portInput = port.toString()
        manualPortsEnabled = manualPorts
        serverDtlsPortInput = (embeddedPort ?: serverDtlsPort).toString()
        serverWgPortInput = serverWgPort.toString()
        if (embeddedPort != null && PeerAddress.hasExplicitPort(peer)) {
            if (embeddedPort != 56000) {
                settingsStore.saveManualPortsEnabled(true)
                manualPortsEnabled = true
            }
            settingsStore.savePorts(embeddedPort, serverWgPort, port)
            settingsStore.save(
                PeerAddress.host(peer), hashes, "",
                workers, "udp", port, sni
            )
        }
        sniInput = sni
        autoCaptchaEnabled = captchaMode == "auto"
        useWVCaptcha = captchaMode != "rjs"
        wbvManualMode = wbvCaptchaMethod != "auto"
        isManualMode = if (captchaMode == "wv") wbvManualMode else captchaMethod != "auto"
        vkAccountAuth = !vkAuthMode.equals("anonymous", ignoreCase = true)
        goDnsCustomInput = settingsStore.goDnsCustom.first()
        goDnsDohCustomInput = settingsStore.goDnsDohCustom.first().ifBlank {
            val legacy = settingsStore.goDnsCustom.first()
            if (legacy.startsWith("https://", ignoreCase = true)) legacy else ""
        }
        
        initialized = true
        vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
    }

    LaunchedEffect(goDnsCustomStored) {
        if (goDnsCustomInput != goDnsCustomStored) {
            goDnsCustomInput = goDnsCustomStored
        }
    }

    LaunchedEffect(goDnsDohCustomStored) {
        if (goDnsDohCustomInput != goDnsDohCustomStored) {
            goDnsDohCustomInput = goDnsDohCustomStored
        }
    }

    LaunchedEffect(currentProfileId, savedPeer, savedWorkers, savedListenPort, vkAccountAuth, combinedHashes) {
        if (currentProfileId.isBlank()) return@LaunchedEffect
        if (savedPeer.isNotBlank()) {
            peerInput = PeerAddress.host(savedPeer)
            PeerAddress.port(savedPeer)?.let { embedded ->
                serverDtlsPortInput = embedded.toString()
            }
        }
        portInput = savedListenPort.toString()
        val hashesCount = combinedHashes.split(",").filter { it.isNotBlank() }.size.coerceAtLeast(1)
        val maxW = if (vkAccountAuth) {
            SettingsStore.VK_ACCOUNT_MAX_WORKERS.toFloat()
        } else {
            SettingsStore.maxAnonymousWorkers(hashesCount).toFloat()
        }
        workersInput = roundToGroup(savedWorkers.toFloat(), maxW, vkAccountAuth)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vkAuthBusy) {
        if (!vkAuthBusy) {
            vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
        }
    }

    LaunchedEffect(vkAccountAuth) {
        if (vkAccountAuth) {
            vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
        }
    }

    LaunchedEffect(savedManualPortsEnabled) {
        manualPortsEnabled = savedManualPortsEnabled
    }

    LaunchedEffect(savedServerDtlsPort) {
        serverDtlsPortInput = savedServerDtlsPort.toString()
    }

    LaunchedEffect(savedServerWgPort) {
        serverWgPortInput = savedServerWgPort.toString()
    }

    LaunchedEffect(savedListenPort) {
        portInput = savedListenPort.toString()
    }

    if (!initialized) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun saveTunnelSettingsNow(hashes: String = combinedHashes, onSaved: (() -> Unit)? = null) {
        saveJob?.cancel()
        scope.launch {
            val savedLocalPort = if (manualPortsEnabled) portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000 else 9000
            val hashesList = hashes.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() && it.length >= 16 }.distinct()
            val hashesCount = hashesList.size.coerceAtLeast(1)
            val maxW = SettingsStore.maxAnonymousWorkers(hashesCount)
            val finalWorkers = workersInput.toInt().coerceIn(9, maxW)
            val host = PeerAddress.host(peerInput.trim())
            settingsStore.save(
                host, hashes, "",
                finalWorkers, "udp", savedLocalPort, sniInput
            )
            onSaved?.invoke()
        }
    }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            val savedLocalPort = if (manualPortsEnabled) portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000 else 9000
            val hashesList = combinedHashes.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() && it.length >= 16 }.distinct()
            val hashesCount = hashesList.size.coerceAtLeast(1)
            val maxW = SettingsStore.maxAnonymousWorkers(hashesCount)
            val finalWorkers = workersInput.toInt().coerceIn(9, maxW)
            val host = PeerAddress.host(peerInput.trim())
            settingsStore.save(
                host, combinedHashes, "",
                finalWorkers, "udp", savedLocalPort, sniInput
            )
        }
    }

    val scrollState = rememberScrollState()

    val isPeerValid = peerInput.isNotBlank()
    val isHashesValid = combinedHashes.isNotBlank()
    val socksAuthValid = connectionMode != SettingsStore.CONNECTION_MODE_SOCKS || !socksAuthEnabled ||
        (socksUsernameInput.isNotBlank() && socksPasswordInput.isNotBlank())
    val isValid = isPeerValid && isHashesValid && savedConnectionPassword.isNotBlank() &&
        !hasInputHashErrors && socksAuthValid
    val effectiveServerDtlsPort = if (manualPortsEnabled) serverDtlsPortInput.toIntOrNull()?.coerceIn(1, 65535) ?: 56000 else 56000
    val effectiveLocalPort = if (manualPortsEnabled) portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000 else 9000
    fun startTunnelService() {
        val effectiveCaptchaMode = if (autoCaptchaEnabled) "auto" else if (useWVCaptcha) "wv" else "rjs"
        val effectiveCaptchaSolveMethod = if (!autoCaptchaEnabled && effectiveCaptchaMode == "wv" && isManualMode) "manual" else "auto"
        val hashesList = combinedHashes.split(Regex("[,\\s\\n]+")).filter { it.isNotBlank() && it.length >= 16 }.distinct()
        val hashesCount = hashesList.size.coerceAtLeast(1)
        val maxW = SettingsStore.maxAnonymousWorkers(hashesCount)
        val finalWorkers = workersInput.toInt().coerceIn(9, maxW)
        val host = PeerAddress.host(peerInput.trim())
        val peerForTunnel = PeerAddress.ensurePort(host, effectiveServerDtlsPort)
        saveJob?.cancel()
        // Не rememberCoroutineScope: уход на вкладку «Логи» отменяет Compose-scope и ломает старт.
        TunnelManager.scope.launch {
            val effectiveVkAnonPath = SettingsStore.resolveVkAnonPath(context)
            settingsStore.save(
                host, combinedHashes, "",
                finalWorkers, "udp", effectiveLocalPort, sniInput
            )
            settingsStore.saveCaptchaMode(effectiveCaptchaMode)
            settingsStore.saveCaptchaSolveMethod(effectiveCaptchaSolveMethod)
            settingsStore.saveVkAnonPath(effectiveVkAnonPath)
            val effectiveGoDns = settingsStore.resolveGoDnsArg()
            val intent = Intent(context, TunnelService::class.java).apply {
                action = "START"
                putExtra("peer",    peerForTunnel)
                putExtra("vk_hashes", combinedHashes)
                putExtra("secondary_vk_hash", "")
                putExtra("workers_per_hash", finalWorkers)
                putExtra("port", effectiveLocalPort)
                putExtra("sni", sniInput)
                putExtra("connection_password", savedConnectionPassword)
                putExtra("captcha_mode", effectiveCaptchaMode)
                putExtra("captcha_solve_method", effectiveCaptchaSolveMethod)
                putExtra("vk_auth_mode", if (vkAccountAuth) "account" else "anonymous")
                putExtra("vk_anon_path", effectiveVkAnonPath)
                putExtra("go_dns_arg", effectiveGoDns)
                putExtra("obfs_mode", obfsMode)
                putExtra("connection_mode", connectionMode)
                putExtra("socks_port", SettingsStore.normalizeSocksPort(socksPortInput.toIntOrNull() ?: socksPort))
                putExtra("socks_auth_enabled", socksAuthEnabled)
                putExtra("socks_username", socksUsernameInput)
                putExtra("socks_password", socksPasswordInput)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    fun requestVpnAndStart() {
        (context as? com.wdtt.client.MainActivity)?.requestNotificationPermissionIfNeeded()
        TunnelManager.beginConnecting()
        val proceed = {
            // Переключение на «Логи» только когда VPN уже выдан — иначе launcher вкладки уничтожается.
            if (autoSwitchToLogs) {
                onConnectRequested()
            }
            startTunnelService()
        }
        if (connectionMode == SettingsStore.CONNECTION_MODE_SOCKS) {
            proceed()
            return
        }
        val activity = context as? com.wdtt.client.MainActivity
        if (activity != null) {
            activity.prepareVpnThen(proceed)
        } else if (VpnService.prepare(context) == null) {
            proceed()
        } else {
            TunnelManager.cancelConnectingIfNeeded()
            Toast.makeText(context, "VPN-разрешение недоступно", Toast.LENGTH_SHORT).show()
        }
    }

    // ═══ Dialogs ═══
    if (showSecretsDialog) {
        SecretsDialog(
            settingsStore = settingsStore,
            initialPassword = savedConnectionPassword,
            initialServerDtlsPort = serverDtlsPortInput,
            initialServerWgPort = serverWgPortInput,
            initialLocalPort = portInput,
            onSaved = { dtls, wg, local ->
                serverDtlsPortInput = dtls
                serverWgPortInput = wg
                portInput = local
            },
            onDismiss = { showSecretsDialog = false }
        )
    }

    if (showHashesDialog) {
        val activeParts = currentHashesRaw.split(Regex("[,\\s\\n]+")).filter { it.isNotEmpty() }
        val captchaModeForCheck by settingsStore.captchaMode.collectAsStateWithLifecycle(initialValue = "auto")
        val goDnsArgForCheck = remember(goDnsPreset, goDnsCustomInput, goDnsDohCustomInput) {
            when (SettingsStore.normalizeGoDnsPreset(goDnsPreset)) {
                "custom" -> {
                    val servers = SettingsStore.normalizeGoDnsServers(goDnsCustomInput)
                    if (servers.isNotEmpty()) "custom:$servers" else "yandex"
                }
                "doh-custom" -> {
                    val urls = SettingsStore.normalizeGoDnsDohUrls(goDnsDohCustomInput)
                    if (urls.isNotEmpty()) "doh:$urls" else "doh-yandex"
                }
                else -> goDnsPreset
            }
        }
        HashesDialog(
            hash1 = activeParts.getOrElse(0) { "" },
            hash2 = activeParts.getOrElse(1) { "" },
            hash3 = activeParts.getOrElse(2) { "" },
            hash4 = activeParts.getOrElse(3) { "" },
            hash5 = activeParts.getOrElse(4) { "" },
            hash6 = activeParts.getOrElse(5) { "" },
            captchaMode = captchaModeForCheck,
            vkAnonPath = vkAnonPath,
            goDnsArg = goDnsArgForCheck,
            onSave = { h1, h2, h3, h4, h5, h6 ->
                val cleaned1 = stripVkUrlStatic(h1)
                val cleaned2 = stripVkUrlStatic(h2)
                val cleaned3 = stripVkUrlStatic(h3)
                val cleaned4 = stripVkUrlStatic(h4)
                val cleaned5 = stripVkUrlStatic(h5)
                val cleaned6 = stripVkUrlStatic(h6)
                val combined = normalizeHashes(cleaned1, cleaned2, cleaned3, cleaned4, cleaned5, cleaned6)
                
                scope.launch {
                    val currentProfileIdStr = settingsStore.currentProfileId.first()
                    val currentProfile = profiles.firstOrNull { it.id == currentProfileIdStr }
                    
                    if (currentProfileIdStr.isEmpty() || (currentProfile != null && currentProfile.useGlobalHashes)) {
                        settingsStore.saveGlobalVkHashes(combined)
                    }
                    
                    // Coerce workers count to new max immediately!
                    val newHashCount = combined.split(",").filter { it.isNotBlank() && it.length >= 16 }.size.coerceAtLeast(1)
                    val newMax = SettingsStore.maxAnonymousWorkers(newHashCount)
                    if (workersInput > newMax) {
                        workersInput = newMax.toFloat()
                    }
                    
                    saveTunnelSettingsNow(combined) { showHashesDialog = false }
                }
            },
            onDismiss = { showHashesDialog = false }
        )
    }

    if (showAppSettingsDialog) {
        Dialog(
            onDismissRequest = { showAppSettingsDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Настройки", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        IconButton(onClick = { showAppSettingsDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                    // ═══ Раздел: Оформление ═══
                    Text(
                        "Оформление",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Тема оформления
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Тема оформления",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ProtocolChip(
                                    label = "Сист.",
                                    selected = themeMode == "system",
                                    enabled = true,
                                    isError = false,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    onThemeChange("system")
                                }
                                ProtocolChip(
                                    label = "Свет.",
                                    selected = themeMode == "light",
                                    enabled = true,
                                    isError = false,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    onThemeChange("light")
                                }
                                ProtocolChip(
                                    label = "Темн.",
                                    selected = themeMode == "dark",
                                    enabled = true,
                                    isError = false,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    onThemeChange("dark")
                                }
                            }
                        }

                        val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        if (supportsDynamicColor) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(
                                        "Динамические цвета",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Material You",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = isDynamicColor,
                                    onCheckedChange = { onDynamicColorChange(it) },
                                    modifier = Modifier.scale(0.8f)
                                )
                            }
                        }

                        // Выбор палитры, если динамические цвета выключены
                        if (!isDynamicColor || !supportsDynamicColor) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Цветовая палитра",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PaletteCircleOption("indigo", 0xFF5B588D, currentPalette, onPaletteChange)
                                    PaletteCircleOption("forest", 0xFF5F5D68, currentPalette, onPaletteChange)
                                    PaletteCircleOption("espresso", 0xFF6D4C41, currentPalette, onPaletteChange)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // ═══ Раздел: Поведение ═══
                    Text(
                        "Поведение",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Логи при подключении",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Переключаться на вкладку «Логи» при запуске туннеля",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoSwitchToLogs,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsStore.saveAutoSwitchToLogs(enabled) }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Схема подключения",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Показывать этапы DNS → VK → DTLS → VPN на вкладке «Логи»",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = connectionPipelineEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsStore.saveConnectionPipelineEnabled(enabled)
                                    if (!enabled) {
                                        TunnelManager.hideConnectionPipelineForSettings()
                                    }
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Отключать на Wi-Fi",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Автоматически отключать туннель при подключении к Wi-Fi (удобно для обхода БС только в мобильной сети)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = stopOnWifi,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsStore.saveStopOnWifi(enabled) }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Автообновление обхода",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Каждые ${BypassRoutes.AUTO_REFRESH_INTERVAL_MS / 60_000} мин перерезолвить домены. Если IP сменились — кратко пересоберёт VPN (может рвать игру/звонки)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bypassAutoRefresh,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsStore.saveBypassAutoRefresh(enabled)
                                    if (!enabled) {
                                        BypassRoutes.stopAutoRefresh()
                                    } else if (TunnelManager.running.first()) {
                                        BypassRoutes.startAutoRefresh(TunnelManager.scope, context) {
                                            TunnelManager.addNetworkLog(
                                                "[ОБХОД] IP доменов изменились — обновляю маршруты VPN"
                                            )
                                            TunnelManager.reloadWireGuard()
                                        }
                                    }
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val subRefreshLabel = when (subscriptionAutoRefreshHours) {
                        SettingsStore.SUB_AUTO_REFRESH_NEVER -> "Выкл"
                        SettingsStore.SUB_AUTO_REFRESH_EVERY_OPEN -> "При каждом открытии"
                        6 -> "Каждые 6 ч"
                        24 -> "Каждые 24 ч"
                        else -> "Каждые 12 ч"
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Автообновление подписок",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Подтягивать профили с сервера подписки при открытии приложения (когда туннель выключен)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ExposedDropdownMenuBox(
                            expanded = subAutoRefreshMenuExpanded,
                            onExpandedChange = { subAutoRefreshMenuExpanded = !subAutoRefreshMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = subRefreshLabel,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                label = { Text("Интервал") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = subAutoRefreshMenuExpanded)
                                },
                                shape = RoundedCornerShape(12.dp),
                            )
                            ExposedDropdownMenu(
                                expanded = subAutoRefreshMenuExpanded,
                                onDismissRequest = { subAutoRefreshMenuExpanded = false }
                            ) {
                                listOf(
                                    SettingsStore.SUB_AUTO_REFRESH_NEVER to "Выкл",
                                    6 to "Каждые 6 ч",
                                    SettingsStore.DEFAULT_SUB_AUTO_REFRESH_HOURS to "Каждые 12 ч",
                                    24 to "Каждые 24 ч",
                                    SettingsStore.SUB_AUTO_REFRESH_EVERY_OPEN to "При каждом открытии",
                                ).forEach { (hours, title) ->
                                    DropdownMenuItem(
                                        text = { Text(title) },
                                        onClick = {
                                            subAutoRefreshMenuExpanded = false
                                            scope.launch {
                                                settingsStore.saveSubscriptionAutoRefreshHours(hours)
                                            }
                                        },
                                        trailingIcon = {
                                            if (subscriptionAutoRefreshHours == hours) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Подробные логи",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Записывать больше диагностической информации (замедляет работу)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = detailedLogs,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsStore.saveDetailedLogs(enabled) }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Проверять обновления",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Автоматически проверять наличие обновлений при открытии приложения",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = updateCheckIntervalHours != com.wdtt.client.UPDATE_CHECK_NEVER,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    val newInterval = if (enabled) {
                                        com.wdtt.client.DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
                                    } else {
                                        com.wdtt.client.UPDATE_CHECK_NEVER
                                    }
                                    settingsStore.saveUpdateCheckIntervalHours(newInterval)
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Бета-обновления",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Показывать pre-release сборки с GitHub (v*-beta)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = includeBetaUpdates,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsStore.saveIncludeBetaUpdates(enabled) }
                            }
                        )
                    }

                    val notificationsEnabled = NotificationHelper.areNotificationsEnabled(context)
                    if (!notificationsEnabled) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Уведомления отключены",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    "Без них не видно статус туннеля, капчу и вход VK. На Xiaomi/Samsung включите уведомления для qWDTT вручную.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                OutlinedButton(
                                    onClick = {
                                        (context as? com.wdtt.client.MainActivity)?.let { activity ->
                                            if (Build.VERSION.SDK_INT >= 33 &&
                                                !NotificationHelper.hasPostNotificationsPermission(context)
                                            ) {
                                                activity.requestNotificationPermissionIfNeeded()
                                            } else {
                                                activity.openNotificationSettings()
                                            }
                                        } ?: NotificationHelper.openAppNotificationSettings(context)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Включить уведомления")
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // ═══ Раздел: Интерфейс ═══
                    Text(
                        "Интерфейс",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Режим приложения",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "В режиме пользователя вкладка «Серверы» скрыта.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProtocolChip(
                                label = "Пользователь",
                                selected = interfaceRole == "user",
                                enabled = true,
                                modifier = Modifier.weight(1f)
                            ) {
                                scope.launch { settingsStore.saveInterfaceRole("user") }
                            }
                            ProtocolChip(
                                label = "Админ",
                                selected = interfaceRole == "admin",
                                enabled = true,
                                modifier = Modifier.weight(1f)
                            ) {
                                scope.launch { settingsStore.saveInterfaceRole("admin") }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // ═══ Раздел: Сеть ═══
                    Text(
                        "Сеть",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    GoDnsSettingsSection(
                        goDnsPreset = goDnsPreset,
                        goDnsCustomInput = goDnsCustomInput,
                        goDnsDohCustomInput = goDnsDohCustomInput,
                        tunnelRunning = tunnelRunning,
                        onPresetChange = { preset ->
                            scope.launch {
                                settingsStore.saveGoDns(
                                    preset = preset,
                                    custom = goDnsCustomInput,
                                    dohCustom = goDnsDohCustomInput,
                                )
                            }
                        },
                        onCustomChange = { value ->
                            goDnsCustomInput = value
                            scope.launch {
                                settingsStore.saveGoDns(
                                    preset = goDnsPreset,
                                    custom = goDnsCustomInput,
                                    dohCustom = goDnsDohCustomInput,
                                )
                            }
                        },
                        onDohCustomChange = { value ->
                            goDnsDohCustomInput = value
                            scope.launch {
                                settingsStore.saveGoDns(
                                    preset = goDnsPreset,
                                    custom = goDnsCustomInput,
                                    dohCustom = goDnsDohCustomInput,
                                )
                            }
                        },
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Режим подключения",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Оба варианта — полноценный VPN (весь трафик через туннель), отличается только " +
                                "транспортный протокол. WireGuard — основной, проверенный. Raw — без " +
                                "WireGuard вообще, эксперимент, нужен сервер с -listen-raw. SOCKS5 — без " +
                                "VPN-разрешения, прокси вручную.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = connectionMode == SettingsStore.CONNECTION_MODE_VPN,
                                onClick = {
                                    if (!tunnelRunning) {
                                        scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_VPN) }
                                    }
                                },
                                label = { Text("WG") },
                                enabled = !tunnelRunning,
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = connectionMode == SettingsStore.CONNECTION_MODE_RAWTUN,
                                onClick = {
                                    if (!tunnelRunning) {
                                        scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_RAWTUN) }
                                    }
                                },
                                label = { Text("Raw") },
                                enabled = !tunnelRunning,
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = connectionMode == SettingsStore.CONNECTION_MODE_SOCKS,
                                onClick = {
                                    if (!tunnelRunning) {
                                        scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_SOCKS) }
                                    }
                                },
                                label = { Text("SOCKS5") },
                                enabled = !tunnelRunning,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (connectionMode == SettingsStore.CONNECTION_MODE_RAWTUN) {
                            Text(
                                "Требует сервер, собранный с -listen-raw. Несовместим со старыми " +
                                    "серверами — если Raw не подключается, используйте режим WG.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            OutlinedTextField(
                                value = serverRawPortInput,
                                onValueChange = { value ->
                                    if (value.all { it.isDigit() } && value.length <= 5) {
                                        serverRawPortInput = value
                                        value.toIntOrNull()?.let { port ->
                                            scope.launch { settingsStore.saveServerRawPort(port) }
                                        }
                                    }
                                },
                                label = { Text("Порт сервера (-listen-raw)") },
                                singleLine = true,
                                enabled = !tunnelRunning,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            )
                        }
                        if (connectionMode == SettingsStore.CONNECTION_MODE_SOCKS) {
                            val socksAddr = SettingsStore.socksListenAddress(
                                socksPortInput.toIntOrNull() ?: socksPort
                            )
                            OutlinedTextField(
                                value = socksPortInput,
                                onValueChange = { value ->
                                    if (value.all { it.isDigit() } && value.length <= 5) {
                                        socksPortInput = value
                                        value.toIntOrNull()?.let { port ->
                                            scope.launch { settingsStore.saveSocksPort(port) }
                                        }
                                    }
                                },
                                label = { Text("Порт SOCKS5") },
                                singleLine = true,
                                enabled = !tunnelRunning,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Авторизация SOCKS5",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        "Требовать логин и пароль от прокси-клиента",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = socksAuthEnabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            settingsStore.saveSocksAuthEnabled(enabled)
                                        }
                                    },
                                    enabled = !tunnelRunning,
                                )
                            }
                            AnimatedVisibility(visible = socksAuthEnabled) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = socksUsernameInput,
                                        onValueChange = { value ->
                                            if (value.toByteArray().size <= 255) {
                                                socksUsernameInput = value
                                                scope.launch {
                                                    settingsStore.saveSocksUsername(value)
                                                }
                                            }
                                        },
                                        label = { Text("Логин SOCKS5") },
                                        singleLine = true,
                                        enabled = !tunnelRunning,
                                        isError = socksUsernameInput.isBlank(),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                    )
                                    OutlinedTextField(
                                        value = socksPasswordInput,
                                        onValueChange = { value ->
                                            if (value.toByteArray().size <= 255) {
                                                socksPasswordInput = value
                                                scope.launch {
                                                    settingsStore.saveSocksPassword(value)
                                                }
                                            }
                                        },
                                        label = { Text("Пароль SOCKS5") },
                                        singleLine = true,
                                        enabled = !tunnelRunning,
                                        isError = socksPasswordInput.isBlank(),
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                    )
                                    if (!socksAuthValid) {
                                        Text(
                                            "Для запуска SOCKS5 заполните логин и пароль",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            Surface(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("socks", socksAddr))
                                    Toast.makeText(context, "Скопировано: $socksAddr", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Адрес прокси",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            socksAddr,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Копировать",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        if (tunnelRunning) {
                            Text(
                                "Смена режима — после отключения туннеля",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Применимо ко всем режимам — меняет только транспорт до
                    // TURN-relay VK (UDP, TCP или TLS), сама TURN/RTP-obfs
                    // логика не меняется. TCP по умолчанию.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "TURN-транспорт",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                SettingsStore.TURN_TRANSPORT_UDP to "UDP",
                                SettingsStore.TURN_TRANSPORT_TCP to "TCP",
                                SettingsStore.TURN_TRANSPORT_TLS to "TLS",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = turnTransport == value,
                                    onClick = {
                                        if (!tunnelRunning) {
                                            scope.launch { settingsStore.saveTurnTransport(value) }
                                        }
                                    },
                                    label = { Text(label) },
                                    enabled = !tunnelRunning,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Text(
                            when (turnTransport) {
                                SettingsStore.TURN_TRANSPORT_UDP ->
                                    "Самый быстрый. Если не подключается или скорость низкая — попробуйте TCP."
                                SettingsStore.TURN_TRANSPORT_TLS ->
                                    "Выглядит как обычный HTTPS к серверу VK. Для сетей, где режут UDP и TCP. " +
                                        "Медленнее остальных; если VK не выдал TLS-адресов, используется TCP."
                                else ->
                                    "Помогает, если провайдер душит UDP (например, Ростелеком)."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = turnHostsInput,
                            onValueChange = { value ->
                                val clean = SettingsStore.sanitizeTurnHosts(value)
                                turnHostsInput = clean
                                scope.launch { settingsStore.saveTurnHosts(clean) }
                            },
                            label = { Text("Свои TURN-IP (необязательно)") },
                            placeholder = { Text("1.2.3.4, 5.6.7.8:3478") },
                            supportingText = {
                                Text("Вместо адресов, которые выдаёт VK. Через запятую, порт можно не указывать.")
                            },
                            singleLine = true,
                            enabled = !tunnelRunning,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Маскировка трафика",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "RTP-пакеты под аудио (OPUS) или видео (H.264) звонок VK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("audio" to "Аудио", "video" to "Видео").forEach { (mode, label) ->
                                FilterChip(
                                    selected = obfsMode == mode,
                                    onClick = {
                                        if (!tunnelRunning) {
                                            scope.launch { settingsStore.saveObfsMode(mode) }
                                        }
                                    },
                                    label = { Text(label) },
                                    enabled = !tunnelRunning,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (tunnelRunning) {
                            Text(
                                "Смена режима — после отключения туннеля",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // ═══ Раздел: О приложении ═══
                    Text(
                        "О приложении",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    val currentVersion = remember { "v${com.wdtt.client.BuildConfig.VERSION_NAME.removePrefix("v")}" }
                    var isCheckingUpdates by remember { mutableStateOf(false) }
                    val updateLatestVersion by settingsStore.updateLatestVersion.collectAsStateWithLifecycle(initialValue = "")
                    val updateLastError by settingsStore.updateLastError.collectAsStateWithLifecycle(initialValue = "")

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "qWDTT",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Версия $currentVersion",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/darkbitVPN"))
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Telegram", style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SpaceNeuroX/proxy-turn-vk-android"))
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("GitHub", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        Text(
                            text = "Форк оригинального проекта amurcanov/proxy-turn-vk-android от разработчика SpaceNeuroX.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://pay.cloudtips.ru/p/64a6c43c")
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Поблагодарить разработчика",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Text(
                            text = "Если приложение помогает — можно оставить чаевые через CloudTips.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Готовые профили",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                
                                Text(
                                    "Вы можете получить готовые конфиги напрямую в этих Telegram-ботах:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/darkbit_vpnbot"))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            contentColor = MaterialTheme.colorScheme.primary
                                        ),
                                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(vertical = 10.dp)
                                    ) {
                                        Text(
                                            "🤖 @darkbit_vpnbot",
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/sidylinkbot"))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            contentColor = MaterialTheme.colorScheme.primary
                                        ),
                                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(vertical = 10.dp)
                                    ) {
                                        Text(
                                            "🤖 @sidylinkbot",
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Проверка обновлений
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val updateStatusText = remember(isCheckingUpdates, updateLatestVersion, updateLastError) {
                                when {
                                    isCheckingUpdates -> "Проверяем..."
                                    updateLatestVersion.isNotBlank() && isNewerVersion(currentVersion, updateLatestVersion, includeBetaUpdates) -> "Доступна $updateLatestVersion!"
                                    updateLatestVersion.isNotBlank() -> "Обновлений нет"
                                    updateLastError.isNotBlank() -> "Ошибка"
                                    else -> "Не проверено"
                                }
                            }
                            
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text(
                                    "Обновления",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = updateStatusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (updateLatestVersion.isNotBlank() && isNewerVersion(currentVersion, updateLatestVersion, includeBetaUpdates)) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        isCheckingUpdates = true
                                        try {
                                            val release = com.wdtt.client.fetchLatestReleaseInfo(
                                                currentVersion,
                                                includeBetaUpdates,
                                            )
                                            if (release != null) {
                                                settingsStore.saveUpdateState(
                                                    lastCheckAt = System.currentTimeMillis(),
                                                    latestVersion = release.versionTag,
                                                    error = ""
                                                )
                                                if (isNewerVersion(currentVersion, release.versionTag, includeBetaUpdates)) {
                                                    Toast.makeText(context, "Доступна новая версия: ${release.versionTag}", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "У вас последняя версия!", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                settingsStore.saveUpdateState(
                                                    lastCheckAt = System.currentTimeMillis(),
                                                    latestVersion = "",
                                                    error = "Ошибка"
                                                )
                                                Toast.makeText(context, "Не удалось проверить обновления", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: java.lang.Exception) {
                                            Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            isCheckingUpdates = false
                                        }
                                    }
                                },
                                enabled = !isCheckingUpdates,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Проверить", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Копия отчета
                        OutlinedButton(
                            onClick = {
                                val reportText = """
                                    Приложение: qWDTT
                                    Версия: $currentVersion
                                    Android API: ${Build.VERSION.SDK_INT}
                                    Архитектура (ABI): ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}
                                    Устройство: ${Build.MANUFACTURER} ${Build.MODEL}
                                """.trimIndent()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("qWDTT Report", reportText))
                                Toast.makeText(context, "Отчёт о системе скопирован!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Скопировать системный отчёт")
                        }

                        Spacer(Modifier.height(12.dp))
                    }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { showAppSettingsDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Готово")
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══ Топ-тулбар с заголовком и иконкой настроек ═══
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "qWDTT",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.primary
            )
            
            // Иконка настроек (шестеренка)
            IconButton(
                onClick = { showAppSettingsDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Настройки оформления и инфо",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        // ═══ Подключение — главное действие, сразу на экране ═══
        val tunnelSecretsMissing = savedConnectionPassword.isBlank()
        val buttonColor by animateColorAsState(
            targetValue = when {
                tunnelRunning -> MaterialTheme.colorScheme.error
                tunnelConnecting && connectCancelArmed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
            animationSpec = tween(400),
            label = "btn_color"
        )

        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (profiles.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (currentProfileName.isNotEmpty()) "Профиль: $currentProfileName" else "Быстрый выбор профиля",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        profiles.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        p.name,
                                        fontWeight = if (p.id == currentProfileId) FontWeight.Bold else FontWeight.Normal,
                                        color = if (p.id == currentProfileId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    scope.launch {
                                        profilesStore.applyProfile(context, p.id)

                                        peerInput = PeerAddress.host(settingsStore.peer.first())
                                        portInput = settingsStore.listenPort.first().toString()
                                        workersInput = roundToGroup(
                                            settingsStore.workersPerHash.first().toFloat(),
                                            dynamicMaxWorkers,
                                            vkAccountAuth
                                        )

                                        if (tunnelRunning) {
                                            context.startService(
                                                Intent(context, TunnelService::class.java).apply { action = "STOP" }
                                            )
                                            delay(800)
                                            requestVpnAndStart()
                                        }

                                        Toast.makeText(context, "Профиль «${p.name}» применен!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (p.id == currentProfileId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                Surface(
                    onClick = onOpenProfiles,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Нет серверов",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Добавьте или импортируйте профиль во вкладке «Профили»",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Компактный переключатель режима подключения прямо рядом с кнопкой
            // "Подключить" — тот же connectionMode, что и подробный блок "Режим
            // подключения" выше в списке настроек, просто на виду без прокрутки.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = connectionMode == SettingsStore.CONNECTION_MODE_VPN,
                    onClick = {
                        if (!tunnelRunning) {
                            scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_VPN) }
                        }
                    },
                    label = { Text("WG") },
                    enabled = !tunnelRunning,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = connectionMode == SettingsStore.CONNECTION_MODE_RAWTUN,
                    onClick = {
                        if (!tunnelRunning) {
                            scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_RAWTUN) }
                        }
                    },
                    label = { Text("Raw") },
                    enabled = !tunnelRunning,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = connectionMode == SettingsStore.CONNECTION_MODE_SOCKS,
                    onClick = {
                        if (!tunnelRunning) {
                            scope.launch { settingsStore.saveConnectionMode(SettingsStore.CONNECTION_MODE_SOCKS) }
                        }
                    },
                    label = { Text("SOCKS5") },
                    enabled = !tunnelRunning,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { showSecretsDialog = true },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (tunnelSecretsMissing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                        contentColor = if (tunnelSecretsMissing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (tunnelSecretsMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(imageVector = Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Секреты", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        when {
                            tunnelRunning || tunnelReconnecting || (tunnelConnecting && connectCancelArmed) -> {
                                context.startService(
                                    Intent(context, TunnelService::class.java).apply { action = "STOP" }
                                )
                            }
                            tunnelConnecting -> {
                                // Grace: игнор, чтобы жест «Подключить» не превратился в «Отмена»
                            }
                            else -> {
                                requestVpnAndStart()
                            }
                        }
                    },
                    enabled = (isValid && cooldownSeconds == 0 && !tunnelConnecting && !tunnelReconnecting) ||
                        tunnelRunning ||
                        tunnelReconnecting ||
                        (tunnelConnecting && connectCancelArmed),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = when {
                            tunnelRunning || tunnelReconnecting || (tunnelConnecting && connectCancelArmed) -> Icons.Default.Stop
                            else -> Icons.Default.PowerSettingsNew
                        },
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            tunnelReconnecting -> "Переподключение…"
                            tunnelConnecting && !tunnelRunning && !connectCancelArmed -> "Подключение…"
                            tunnelConnecting && !tunnelRunning -> "Отмена"
                            tunnelRunning -> "Остановить"
                            cooldownSeconds > 0 -> "Подождите ($cooldownSeconds)"
                            else -> "Подключить"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = peerInput,
                onValueChange = {
                    var cleaned = it.filter { c -> c != ' ' }
                    if (PeerAddress.hasExplicitPort(cleaned)) {
                        cleaned = PeerAddress.host(cleaned)
                    }
                    peerInput = cleaned
                    scheduleSave()
                },
                label = { Text("IP сервера или домен") },
                placeholder = { Text("31.76.102.29") },
                singleLine = true,
                isError = !isPeerValid && peerInput.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
            )

            OutlinedButton(
                onClick = { showHashesDialog = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(
                    1.dp,
                    if (hasInputHashErrors) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Default.Tag, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("VK Хеши ($filledHashCount)", fontWeight = FontWeight.SemiBold)
            }

            val errorTexts = hashErrors.filter { !it.contains("короткий") }
            if (errorTexts.isNotEmpty()) {
                Text(
                    text = errorTexts.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ═══ Мощность + Капча ═══
        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
                // — Мощность —
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Мощность",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${currentWorkers.toInt()}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }

                Spacer(Modifier.height(4.dp))

                val maxWorkers = dynamicMaxWorkers
                val minWorkers = if (vkAccountAuth) 1f else WORKERS_PER_GROUP.toFloat()
                val workerStep = if (vkAccountAuth) 1f else WORKERS_PER_GROUP.toFloat()
                val currentWorkersVal = if (vkAccountAuth) {
                    currentWorkers.coerceIn(1f, maxWorkers).roundToInt().toFloat()
                } else {
                    roundToGroup(currentWorkers.coerceIn(minWorkers, maxWorkers), maxWorkers)
                }

                CompactSteppedSlider(
                    value = currentWorkersVal,
                    onValueChange = { raw ->
                        workersInput = if (vkAccountAuth) {
                            raw.coerceIn(1f, maxWorkers).roundToInt().toFloat()
                        } else {
                            roundToGroup(raw, maxWorkers)
                        }
                        scheduleSave()
                    },
                    valueRange = minWorkers..maxWorkers,
                    stepSize = workerStep,
                    enabled = !tunnelRunning,
                    modifier = Modifier.fillMaxWidth()
                )

                // — Разделитель —
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Вход через аккаунт VK",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Если анонимный режим не работает — включите и войдите в свой аккаунт VK. Подключение стабильнее.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = vkAccountAuth,
                        enabled = !tunnelRunning && !vkAuthBusy,
                        onCheckedChange = { enabled ->
                            vkAccountAuth = enabled
                            scope.launch {
                                settingsStore.saveVkAuthMode(if (enabled) "account" else "anonymous")
                            }
                        }
                    )
                }

                if (vkAccountAuth) {
                    Button(
                        onClick = {
                            scope.launch {
                                vkAuthBusy = true
                                try {
                                    val result = VkAuthWebViewManager.loginOnly(context)
                                    result.onSuccess {
                                        vkLoggedIn = true
                                        Toast.makeText(
                                            context,
                                            "Вход в VK выполнен",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }.onFailure {
                                        vkLoggedIn = VkAuthWebViewManager.hasVkSessionCookie()
                                        Toast.makeText(
                                            context,
                                            "VK: ${it.message ?: "ошибка"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } finally {
                                    vkAuthBusy = false
                                }
                            }
                        },
                        enabled = !tunnelRunning && !vkAuthBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (vkAuthBusy) "Ожидание входа VK..." else "Войти в VK")
                    }
                    if (vkLoggedIn) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF43A047),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Вход в VK выполнен",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF43A047),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Вход в VK не выполнен",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // — Режим VK (анонимный) —
                AnimatedVisibility(visible = !vkAccountAuth) {
                Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Режим VK",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProtocolChip("Звонок", useVKCallsAuth, enabled = !tunnelRunning) {
                            scope.launch { settingsStore.saveVkAnonPath("vkcalls") }
                        }
                        ProtocolChip("Капча", !useVKCallsAuth, enabled = !tunnelRunning) {
                            scope.launch { settingsStore.saveVkAnonPath("legacy") }
                        }
                    }
                }
                if (useVKCallsAuth) {
                    Text(
                        "TURN через «Звонок», обычно без капчи. При ошибке — запасной режим «Капча».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                AnimatedVisibility(
                    visible = !useVKCallsAuth,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                Column {
                // — Авто капча —
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (autoCaptchaEnabled) "Авто капча" else "Ручная капча",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoCaptchaEnabled,
                        onCheckedChange = { enabled ->
                            autoCaptchaEnabled = enabled
                            scope.launch {
                                if (enabled) {
                                    settingsStore.saveCaptchaMode("auto")
                                    settingsStore.saveCaptchaSolveMethod("auto")
                                } else {
                                    val mode = if (useWVCaptcha) "wv" else "rjs"
                                    settingsStore.saveCaptchaMode(mode)
                                    settingsStore.saveCaptchaSolveMethod(if (mode == "wv" && isManualMode) "manual" else "auto")
                                }
                            }
                        }
                    )
                }

                AnimatedVisibility(
                    visible = !autoCaptchaEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        // — Разделитель —
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // — Метод обхода капчи —
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Метод обхода капчи",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProtocolChip("WBV", useWVCaptcha, enabled = true) {
                                    useWVCaptcha = true
                                    isManualMode = wbvManualMode
                                    scope.launch {
                                        settingsStore.saveCaptchaMode("wv")
                                        settingsStore.saveCaptchaSolveMethod(if (wbvManualMode) "manual" else "auto")
                                    }
                                }
                                ProtocolChip("RJS", !useWVCaptcha, enabled = true, isError = false) {
                                    useWVCaptcha = false
                                    isManualMode = false
                                    scope.launch {
                                        settingsStore.saveCaptchaMode("rjs")
                                        settingsStore.saveCaptchaSolveMethod("auto")
                                    }
                                }
                            }
                        }

                        // — Разделитель —
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // — Режим обхода —
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Режим обхода",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (useWVCaptcha) {
                                    ProtocolChip(
                                        "РУЧ",
                                        isManualMode,
                                        enabled = true,
                                        isError = false
                                    ) {
                                        isManualMode = true
                                        wbvManualMode = true
                                        scope.launch { settingsStore.saveWbvCaptchaSolveMethod("manual") }
                                    }
                                    ProtocolChip(
                                        "АВТ",
                                        !isManualMode,
                                        enabled = true,
                                        isError = false
                                    ) {
                                        isManualMode = false
                                        wbvManualMode = false
                                        scope.launch { settingsStore.saveWbvCaptchaSolveMethod("auto") }
                                    }
                                } else {
                                    ProtocolChip(
                                        "АВТ",
                                        selected = true,
                                        enabled = true,
                                        isError = false
                                    ) {}
                                }
                            }
                        }
                    }
                }
                }
                }
                }
                }
        }

    }
}

// ═══ Reusable mode chip ═══
@Composable
private fun ProtocolChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        },
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
            disabledLabelColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            selectedBorderColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun CompactSteppedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    stepSize: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    fun snap(raw: Float): Float {
        val min = valueRange.start
        val max = valueRange.endInclusive
        val snapped = (((raw - min) / stepSize).roundToInt() * stepSize) + min
        return snapped.coerceIn(min, max)
    }

    val steps = (((valueRange.endInclusive - valueRange.start) / stepSize).roundToInt() - 1).coerceAtLeast(0)
    val clampedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val valueLabel = clampedValue.toInt().toString()

    Slider(
        value = clampedValue,
        onValueChange = { onValueChange(snap(it)) },
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier.semantics {
            contentDescription = "Количество потоков"
            stateDescription = "$valueLabel, от ${valueRange.start.toInt()} до ${valueRange.endInclusive.toInt()}"
        }
    )
}

// Округление до ближайшего кратного WORKERS_PER_GROUP (анонимный режим) или 1..max (аккаунт VK)
private fun roundToGroup(value: Float, maxW: Float = 96f, accountMode: Boolean = false): Float {
    if (accountMode || maxW < WORKERS_PER_GROUP) {
        return value.coerceIn(1f, maxW.coerceAtLeast(1f))
    }
    val rounded = (Math.round(value / WORKERS_PER_GROUP) * WORKERS_PER_GROUP).toFloat()
    return rounded.coerceIn(WORKERS_PER_GROUP.toFloat(), maxW)
}

// ═══ Модальное окно хешей ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashesDialog(
    hash1: String,
    hash2: String,
    hash3: String,
    hash4: String,
    hash5: String = "",
    hash6: String = "",
    captchaMode: String = "auto",
    vkAnonPath: String = "vkcalls",
    goDnsArg: String = "yandex",
    onSave: (String, String, String, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var h1 by remember { mutableStateOf(hash1) }
    var h2 by remember { mutableStateOf(hash2) }
    var h3 by remember { mutableStateOf(hash3) }
    var h4 by remember { mutableStateOf(hash4) }
    var h5 by remember { mutableStateOf(hash5) }
    var h6 by remember { mutableStateOf(hash6) }
    var isChecking by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var checkResults by remember { mutableStateOf<Map<Int, com.wdtt.client.HashCheckResult>>(emptyMap()) }
    var menuExpanded by remember { mutableStateOf(false) }

    val currentHashes = remember(h1, h2, h3, h4, h5, h6) {
        listOf(h1, h2, h3, h4, h5, h6).map { stripVkUrlStatic(it) }
    }
    val filledHashes = remember(currentHashes) {
        currentHashes.filter { it.isNotBlank() }
    }
    val checkableHashes = remember(currentHashes) {
        currentHashes.mapIndexedNotNull { index, hash ->
            if (hash.length >= 16) index + 1 to hash else null
        }
    }
    val completedChecks = checkResults.values.count {
        it.status !in setOf("pending", "checking", "solving_captcha")
    }
    val okCount = checkResults.values.count { it.status == "ok" }
    val badCount = checkResults.values.count {
        it.status in setOf("dead", "error", "network", "limited", "captcha")
    }
    val tunnelBusy by TunnelManager.running.collectAsStateWithLifecycle()
    val vkLoggedIn = remember { mutableStateOf(VkAuthWebViewManager.hasVkSessionCookie()) }
    LaunchedEffect(Unit) {
        vkLoggedIn.value = VkAuthWebViewManager.hasVkSessionCookie()
    }
    val progress = if (checkableHashes.isEmpty()) {
        0f
    } else {
        completedChecks.toFloat() / checkableHashes.size.toFloat()
    }

    fun startHashGeneration() {
        if (isGenerating || isChecking || tunnelBusy || filledHashes.size >= SettingsStore.MAX_VK_HASHES) return
        if (!VkAuthWebViewManager.hasVkSessionCookie()) {
            Toast.makeText(context, "Сначала войдите в аккаунт VK", Toast.LENGTH_SHORT).show()
            return
        }
        checkJob = scope.launch {
            isGenerating = true
            val emptyCount = (SettingsStore.MAX_VK_HASHES - filledHashes.size).coerceAtLeast(1)
            try {
                val result = withContext(Dispatchers.IO) {
                    com.wdtt.client.VkCallHashGenerator.generateHashes(context, emptyCount)
                }
                result.fold(
                    onSuccess = { newHashes ->
                        val slots = mutableListOf(h1, h2, h3, h4, h5, h6)
                        newHashes.forEach { hash ->
                            val idx = slots.indexOfFirst { it.isBlank() }
                            if (idx >= 0) slots[idx] = hash
                        }
                        h1 = slots[0]
                        h2 = slots[1]
                        h3 = slots[2]
                        h4 = slots[3]
                        h5 = slots[4]
                        h6 = slots[5]
                        Toast.makeText(
                            context,
                            "Создано хешей: ${newHashes.size}",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            context,
                            e.message ?: "Не удалось создать звонок VK",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } finally {
                isGenerating = false
                checkJob = null
            }
        }
    }

    fun cancelHashCheck(updateUi: Boolean = true) {
        checkJob?.cancel()
        checkJob = null
        ManlCaptchaWebViewManager.cancelCaptcha()
        if (updateUi) {
            isChecking = false
            val active = checkResults.filterValues {
                it.status in setOf("pending", "checking", "solving_captcha")
            }
            if (active.isNotEmpty()) {
                checkResults = checkResults + active.mapValues { (_, r) ->
                    r.copy(status = "cancelled", message = "Остановлено")
                }
            }
        }
    }

    fun closeDialog() {
        cancelHashCheck()
        onDismiss()
    }

    fun startHashCheck() {
        if (isChecking || tunnelBusy || checkableHashes.isEmpty()) return
        checkJob = scope.launch {
            isChecking = true
            checkResults = checkableHashes.associate { (slot, hash) ->
                slot to com.wdtt.client.HashCheckResult(
                    hash = hash,
                    status = "pending",
                    message = "В очереди"
                )
            }
            try {
                val results = withContext(Dispatchers.IO) {
                    com.wdtt.client.HashCheckHelper.checkHashes(
                        context = context,
                        hashes = checkableHashes,
                        captchaMode = captchaMode,
                        vkAnonPath = vkAnonPath,
                        goDnsArg = goDnsArg,
                        onUpdate = { slot, result ->
                            scope.launch(Dispatchers.Main) {
                                checkResults = checkResults + (slot to result)
                            }
                        }
                    )
                }
                checkResults = results
            } catch (e: Exception) {
                val message = e.message ?: "Сбой диагностики"
                checkResults = checkableHashes.associate { (slot, hash) ->
                    slot to (checkResults[slot] ?: com.wdtt.client.HashCheckResult(
                        hash = hash,
                        status = "error",
                        message = message
                    ))
                }
            } finally {
                isChecking = false
                checkJob = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { cancelHashCheck(updateUi = false) }
    }

    Dialog(
        onDismissRequest = { closeDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ─── Header (fixed) ───
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Tag,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "VK Хеши",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = filledHashes.isNotEmpty(),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Действия")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Копировать через запятую") },
                                onClick = {
                                    menuExpanded = false
                                    copyText(context, "VK Хеши", filledHashes.joinToString(","))
                                },
                                enabled = filledHashes.isNotEmpty()
                            )
                            DropdownMenuItem(
                                text = { Text("Копировать по строкам") },
                                onClick = {
                                    menuExpanded = false
                                    copyText(context, "VK Хеши", filledHashes.joinToString("\n"))
                                },
                                enabled = filledHashes.isNotEmpty()
                            )
                            DropdownMenuItem(
                                text = { Text("Сбросить статусы") },
                                onClick = {
                                    menuExpanded = false
                                    if (!isChecking) checkResults = emptyMap()
                                },
                                enabled = checkResults.isNotEmpty() && !isChecking
                            )
                        }
                    }
                    IconButton(onClick = { closeDialog() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                AnimatedVisibility(visible = isChecking || checkResults.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { if (isChecking) progress.coerceIn(0.05f, 1f) else 1f },
                            modifier = Modifier.weight(1f).height(4.dp),
                            color = when {
                                badCount > 0 && !isChecking -> MaterialTheme.colorScheme.error
                                okCount > 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.tertiary
                            },
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        )
                        Text(
                            if (isChecking) {
                                "$completedChecks/${checkableHashes.size}"
                            } else {
                                "✓$okCount ✕$badCount"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ─── Slots (scroll) ───
                Column(
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (vkLoggedIn.value) {
                        OutlinedButton(
                            onClick = { startHashGeneration() },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isGenerating && !isChecking && !tunnelBusy && filledHashes.size < SettingsStore.MAX_VK_HASHES,
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isGenerating) "Создаём звонок VK…" else "Сгенерировать хеш VK",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Text(
                            "Для автогенерации хешей включите «Вход через аккаунт VK» на вкладке «Туннель» и войдите в VK",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    listOf(
                        Triple("1", h1) { v: String -> h1 = v },
                        Triple("2", h2) { v: String -> h2 = v },
                        Triple("3", h3) { v: String -> h3 = v },
                        Triple("4", h4) { v: String -> h4 = v }
                    ).take(SettingsStore.MAX_VK_HASHES).forEachIndexed { idx, (label, value, onChange) ->
                        HashSlotCard(
                            slot = idx + 1,
                            label = label,
                            required = idx == 0,
                            value = value,
                            result = checkResults[idx + 1],
                            enabled = !isChecking && !isGenerating,
                            onValueChange = { raw ->
                                onChange(stripVkUrlStatic(raw.filter { c -> c != ' ' && c != '\n' }))
                            },
                            onCopy = {
                                val cleaned = stripVkUrlStatic(value)
                                if (cleaned.isNotBlank()) copyText(context, "VK Хеш ${idx + 1}", cleaned)
                            }
                        )
                    }
                    if (tunnelBusy) {
                        Text(
                            "Проверка недоступна при активном туннеле.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // ─── Footer (fixed) ───
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isChecking) {
                        OutlinedButton(
                            onClick = { cancelHashCheck() },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Стоп", color = MaterialTheme.colorScheme.error, maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { startHashCheck() },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = checkableHashes.isNotEmpty() && !tunnelBusy,
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Default.Verified, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (tunnelBusy) "Занято" else "Проверить",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                    Button(
                        onClick = {
                            cancelHashCheck(updateUi = false)
                            onSave(h1, h2, h3, h4, h5, h6)
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = h1.isNotBlank() && h1.length >= 16 && !isChecking,
                        colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("Сохранить", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun HashSlotCard(
    slot: Int,
    label: String,
    required: Boolean,
    value: String,
    result: com.wdtt.client.HashCheckResult?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onCopy: () -> Unit,
) {
    val cleaned = stripVkUrlStatic(value)
    val isShort = cleaned.isNotBlank() && cleaned.length < 16
    val statusText = when {
        isShort -> "короткий"
        result == null -> null
        else -> when (result.status) {
            "ok" -> "живой"
            "dead" -> "закрыт"
            "captcha" -> "капча"
            "limited" -> "лимит"
            "network" -> "сеть"
            "checking", "solving_captcha" -> "…"
            "pending" -> "…"
            "cancelled" -> "стоп"
            else -> "ошибка"
        }
    }
    val statusColor = when {
        isShort -> MaterialTheme.colorScheme.error
        result?.status == "ok" -> MaterialTheme.colorScheme.primary
        result?.status in setOf("checking", "pending", "solving_captcha", "captcha", "limited") ->
            MaterialTheme.colorScheme.tertiary
        result != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        isError = isShort || result?.status in setOf("dead", "error", "network"),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (required) "Слот $label *" else "Слот $label")
                if (statusText != null) {
                    Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(statusText, color = statusColor, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        placeholder = { Text("ссылка или хеш") },
        supportingText = when {
            isShort -> {{ Text("мин. 16 символов", color = MaterialTheme.colorScheme.error) }}
            result != null && result.status !in setOf("pending", "checking", "solving_captcha") -> {
                { Text(result.message, maxLines = 1) }
            }
            else -> null
        },
        trailingIcon = {
            if (cleaned.isNotBlank()) {
                IconButton(onClick = onCopy, enabled = enabled, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Копировать",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    )
}

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

// ═══ Модальное окно секретов ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsDialog(
    settingsStore: SettingsStore,
    initialPassword: String,
    initialServerDtlsPort: String,
    initialServerWgPort: String,
    initialLocalPort: String,
    onSaved: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var passwordInput by rememberSaveable { mutableStateOf(initialPassword) }
    var serverDtlsPort by rememberSaveable { mutableStateOf(initialServerDtlsPort.ifBlank { "56000" }) }
    var serverWgPort by rememberSaveable { mutableStateOf(initialServerWgPort.ifBlank { "56001" }) }
    var localPort by rememberSaveable { mutableStateOf(initialLocalPort.ifBlank { "9000" }) }

    fun normalizePort(value: String, fallback: String): String {
        return value.toIntOrNull()?.takeIf { it in 1..65535 }?.toString() ?: fallback
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Секреты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Заданный пароль туннеля") },
                    placeholder = { Text("Придумайте надежный пароль") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Порты", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Стандартные: DTLS 56000, WireGuard 56001, локальный 9000",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverDtlsPort,
                    onValueChange = { serverDtlsPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт сервера DTLS") },
                    placeholder = { Text("56000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverWgPort,
                    onValueChange = { serverWgPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт сервера WireGuard") },
                    placeholder = { Text("56001") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = localPort,
                    onValueChange = { localPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Локальный порт VPN") },
                    placeholder = { Text("9000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val finalDtls = normalizePort(serverDtlsPort, "56000")
                        val finalWg = normalizePort(serverWgPort, "56001")
                        val finalLocal = normalizePort(localPort, "9000")
                        scope.launch {
                            settingsStore.saveConnectionPassword(passwordInput)
                            settingsStore.savePorts(finalDtls.toInt(), finalWg.toInt(), finalLocal.toInt())
                            val customPorts = finalDtls != "56000" || finalWg != "56001" || finalLocal != "9000"
                            if (customPorts) {
                                settingsStore.saveManualPortsEnabled(true)
                            }
                            onSaved(finalDtls, finalWg, finalLocal)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = passwordInput.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PaletteCircleOption(
    paletteId: String,
    colorHex: Long,
    selectedId: String,
    onClick: (String) -> Unit
) {
    val isSelected = paletteId == selectedId
    val baseModifier = Modifier
        .size(36.dp)
        .clip(androidx.compose.foundation.shape.CircleShape)
        .background(Color(colorHex))
        .clickable { onClick(paletteId) }

    val finalModifier = if (isSelected) {
        baseModifier.border(3.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
    } else {
        baseModifier
    }

    Box(
        modifier = finalModifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoDnsSettingsSection(
    goDnsPreset: String,
    goDnsCustomInput: String,
    goDnsDohCustomInput: String,
    tunnelRunning: Boolean,
    onPresetChange: (String) -> Unit,
    onCustomChange: (String) -> Unit,
    onDohCustomChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var isCheckingDns by remember { mutableStateOf(false) }
    var checkResultText by remember { mutableStateOf<String?>(null) }
    var checkResultOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val udpPresets = remember {
        listOf(
            "yandex" to "Яндекс DNS",
            "cloudflare" to "Cloudflare",
            "google" to "Google DNS",
            "custom" to "Свой DNS",
        )
    }
    val dohPresets = remember {
        listOf(
            "doh-yandex" to "Яндекс DoH",
            "doh-cloudflare" to "Cloudflare DoH",
            "doh-google" to "Google DoH",
            "doh-custom" to "Свой DoH",
        )
    }

    fun presetSubtitle(preset: String): String {
        return when (preset) {
            "custom" -> SettingsStore.goDnsDisplay("custom", goDnsCustomInput).servers
                .joinToString(" · ")
                .ifBlank { "укажите IP ниже" }
            "doh-custom" -> SettingsStore.goDnsDisplay("doh-custom", goDnsDohCustomInput).servers
                .joinToString(" · ")
                .ifBlank { "укажите URL ниже" }
            else -> SettingsStore.goDnsDisplay(preset).servers.joinToString(" · ")
        }
    }

    val display = SettingsStore.goDnsDisplay(
        goDnsPreset,
        if (goDnsPreset == "doh-custom") goDnsDohCustomInput else goDnsCustomInput,
    )
    val isDohSelected = SettingsStore.isDohGoDnsPreset(goDnsPreset)
    val canCheck = !isCheckingDns && when (goDnsPreset) {
        "custom" -> goDnsCustomInput.isNotBlank()
        "doh-custom" -> goDnsDohCustomInput.isNotBlank()
        else -> true
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "DNS для VK",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Резолв login.vk.ru и api.vk.me при подключении.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (!tunnelRunning) expanded = !expanded }
        ) {
            OutlinedTextField(
                value = display.title,
                onValueChange = {},
                readOnly = true,
                enabled = !tunnelRunning,
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                label = { Text("Провайдер DNS") },
                supportingText = {
                    Text(
                        "${presetSubtitle(goDnsPreset)} · ${if (isDohSelected) "DoH" else "UDP :53"}"
                    )
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 280.dp)
            ) {
                Text(
                    "UDP · порт 53",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                udpPresets.forEach { (preset, title) ->
                    GoDnsDropdownItem(
                        title = title,
                        subtitle = presetSubtitle(preset),
                        selected = goDnsPreset == preset,
                        onClick = {
                            expanded = false
                            checkResultText = null
                            onPresetChange(preset)
                        }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Text(
                    "DoH · HTTPS",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                dohPresets.forEach { (preset, title) ->
                    GoDnsDropdownItem(
                        title = title,
                        subtitle = presetSubtitle(preset),
                        selected = goDnsPreset == preset,
                        onClick = {
                            expanded = false
                            checkResultText = null
                            onPresetChange(preset)
                        }
                    )
                }
            }
        }

        if (goDnsPreset == "custom" || goDnsPreset == "doh-custom") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (goDnsPreset == "custom") {
                    OutlinedTextField(
                        value = goDnsCustomInput,
                        onValueChange = { value ->
                            checkResultText = null
                            onCustomChange(value.filter { c -> c.isDigit() || c in ".,; \t" })
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !tunnelRunning,
                        label = { Text("IP-адреса DNS") },
                        placeholder = { Text("1.1.1.1, 8.8.8.8") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                if (goDnsPreset == "doh-custom") {
                    OutlinedTextField(
                        value = goDnsDohCustomInput,
                        onValueChange = { value ->
                            checkResultText = null
                            onDohCustomChange(value)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !tunnelRunning,
                        label = { Text("URL DoH") },
                        placeholder = { Text("https://…/dns-query") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                if (!canCheck) return@OutlinedButton
                scope.launch {
                    isCheckingDns = true
                    checkResultText = null
                    try {
                        val result = com.wdtt.client.GoDnsProbe.checkPreset(
                            preset = goDnsPreset,
                            customRaw = goDnsCustomInput,
                            dohCustomRaw = goDnsDohCustomInput,
                        )
                        checkResultOk = result.reachable
                        checkResultText = if (result.reachable) {
                            "Доступен: ${result.statusText}"
                        } else {
                            "Недоступен: ${result.statusText}"
                        }
                    } catch (e: Exception) {
                        checkResultOk = false
                        checkResultText = "Ошибка проверки: ${e.message ?: e::class.java.simpleName}"
                    } finally {
                        isCheckingDns = false
                    }
                }
            },
            enabled = canCheck,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isCheckingDns) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Проверка…")
            } else {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Проверить DNS")
            }
        }

        checkResultText?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (checkResultOk) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }

        if (tunnelRunning) {
            Text(
                "Перезапустите туннель, чтобы применить DNS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun GoDnsDropdownItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        title,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    )
}
