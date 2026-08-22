package com.wdtt.client

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

private const val TUNNEL_NOTIFICATION_CHANNEL_ID = NotificationHelper.TUNNEL_CHANNEL_ID
private const val TUNNEL_NOTIFICATION_ID = 1
private const val RAW_WAKE_RESTART_THRESHOLD_MS = 60_000L

class TunnelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var updateJob: Job? = null
    private var lastNotificationText: String? = null
    
    // Network Monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRecoveryJob: Job? = null
    private var lastNetworkRecoveryAttemptMs = 0L
    private var screenStateReceiver: BroadcastReceiver? = null
    private var wakeRecoveryJob: Job? = null
    private var wakeGraceUntilMs = 0L
    private var screenOffAtMs = 0L
    private var deviceWasInteractive = true
    private val activeNetworks = mutableSetOf<Network>()
    private val networkFingerprints = mutableMapOf<Network, String>()
    private var lastUnderlyingFingerprint = ""
    private var isTunnelPaused = false
    private var recoveringFromNetworkLoss = false
    private var lastVpnReconnectAttemptMs = 0L
    private var wasOnWifi = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureTunnelChannel(this)
        createNotificationChannel()
        // Сразу берем лок при создании
        acquireWakeLock()
        setupNetworkCallback()
        setupScreenStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            restoreTunnel()
            return START_STICKY
        }

        when (intent.action) {
            "START", "START_FORCED" -> {
                val notification = createNotification("Запуск...")
                startPersistentForeground(notification)

                val appContext = applicationContext
                TunnelManager.scope.launch {
                    try {
                        val store = SettingsStore(appContext)
                        SettingsStore.awaitMigrations(appContext)
                        val basePeer = intent.getStringExtra("peer")?.takeIf { it.isNotEmpty() } ?: store.peer.first()
                        val manualPortsEnabled = store.manualPortsEnabled.first()
                        val connectionMode = SettingsStore.normalizeConnectionMode(
                            intent.getStringExtra("connection_mode")?.takeIf { it.isNotEmpty() }
                                ?: store.connectionMode.first()
                        )
                        val isRawTun = connectionMode == SettingsStore.CONNECTION_MODE_RAWTUN
                        val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
                        // Raw и так всегда без DTLS (инвариант архитектуры, не опция) — если этот
                        // переключатель читать безусловно, сохранённое значение от классического
                        // VPN-режима "утекает" в rawtun: лишний -notls в cmd и лишняя плашка
                        // "[СЕТЬ] Транспорт: без DTLS" на экране логов при активном Raw.
                        val noDtlsEnabled = !isRawTun && store.noDtlsEnabled.first()
                        val serverDirectPort = if (manualPortsEnabled) store.serverDirectPort.first() else 56002
                        val serverRawPort = if (manualPortsEnabled) store.serverRawPort.first() else 56003
                        val effectiveServerPort = when {
                            isRawTun -> serverRawPort
                            noDtlsEnabled -> serverDirectPort
                            else -> serverDtlsPort
                        }
                        val peerWithPort = if (basePeer.isBlank()) basePeer
                            else if (isRawTun || noDtlsEnabled) PeerAddress.withPort(basePeer, effectiveServerPort)
                            else PeerAddress.ensurePort(basePeer, effectiveServerPort)
                        val vkAnonPath = SettingsStore.normalizeVkAnonPath(
                            intent.getStringExtra("vk_anon_path")?.takeIf { it.isNotEmpty() }
                                ?: store.vkAnonPath.first()
                        )
                        val goDnsArg = intent.getStringExtra("go_dns_arg")?.takeIf { it.isNotEmpty() }
                            ?: store.resolveGoDnsArg()
                        val obfsMode = SettingsStore.normalizeObfsMode(
                            intent.getStringExtra("obfs_mode")?.takeIf { it.isNotEmpty() }
                                ?: store.obfsMode.first()
                        )
                        val socksPort = SettingsStore.normalizeSocksPort(
                            intent.getIntExtra("socks_port", 0).takeIf { it > 0 }
                                ?: store.socksPort.first()
                        )
                        val socksAuthEnabled = if (intent.hasExtra("socks_auth_enabled")) {
                            intent.getBooleanExtra("socks_auth_enabled", false)
                        } else {
                            store.socksAuthEnabled.first()
                        }
                        val socksUsername = intent.getStringExtra("socks_username")
                            ?: store.socksUsername.first()
                        val socksPassword = intent.getStringExtra("socks_password")
                            ?: store.socksPassword.first()
                        
                        val params = TunnelParams(
                            peer = peerWithPort,
                            vkHashes = intent.getStringExtra("vk_hashes")?.takeIf { it.isNotEmpty() } ?: store.vkHashes.first(),
                            secondaryVkHash = intent.getStringExtra("secondary_vk_hash")?.takeIf { it.isNotEmpty() } ?: store.secondaryVkHash.first(),
                            workersPerHash = intent.getIntExtra("workers_per_hash", 0).takeIf { it > 0 } ?: store.workersPerHash.first(),
                            port = intent.getIntExtra("port", 0).takeIf { it > 0 } ?: store.listenPort.first(),
                            sni = intent.getStringExtra("sni")?.takeIf { it.isNotEmpty() } ?: store.sni.first(),
                            connectionPassword = intent.getStringExtra("connection_password")?.takeIf { it.isNotEmpty() } ?: store.connectionPassword.first(),
                            protocol = intent.getStringExtra("protocol")?.takeIf { it.isNotEmpty() } ?: store.protocol.first(),
                            captchaMode = sanitizeCaptchaMode(intent.getStringExtra("captcha_mode")?.takeIf { it.isNotEmpty() } ?: store.captchaMode.first()),
                            captchaSolveMethod = intent.getStringExtra("captcha_solve_method")?.takeIf { it.isNotEmpty() } ?: store.captchaSolveMethod.first(),
                            vkAuthMode = intent.getStringExtra("vk_auth_mode")?.takeIf { it.isNotEmpty() } ?: store.vkAuthMode.first(),
                            vkAnonPath = vkAnonPath,
                            goDnsArg = goDnsArg,
                            obfsMode = obfsMode,
                            connectionMode = connectionMode,
                            socksPort = socksPort,
                            socksAuthEnabled = socksAuthEnabled,
                            socksUsername = socksUsername,
                            socksPassword = socksPassword,
                            noDtls = noDtlsEnabled,
                            turnTcp = store.turnTcpEnabled.first(),
                            detailedLogs = store.detailedLogs.first()
                        )
                        launch(Dispatchers.Main) {
                            startTunnel(params, forceStart = intent.action == "START_FORCED")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) { stopTunnel() }
                    }
                }
            }
            "STOP" -> stopTunnel()
            "DEPLOY_START" -> {
                val notification = createNotification("Установка на сервер...", "DEPLOY_CANCEL", "Отменить")
                startPersistentForeground(notification)
                acquireWakeLock()
            }
            "DEPLOY_CANCEL" -> {
                DeployManager.writeError("[!] ❌ Установка отменена пользователем")
                DeployManager.stopDeploy("error: Отменена пользователем")
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            "DEPLOY_STOP" -> {
                if (!TunnelManager.running.value) {
                    stopTunnel()
                } else {
                    updateNotification("Туннель активен")
                }
            }
        }
        return START_STICKY
    }

    private fun restoreTunnel() {
        val notification = createNotification("Восстановление соединения...")
        startPersistentForeground(notification)
        
        val appContext = applicationContext
        TunnelManager.scope.launch {
            try {
                val store = SettingsStore(appContext)
                SettingsStore.awaitMigrations(appContext)
                val basePeer = store.peer.first()
                val manualPortsEnabled = store.manualPortsEnabled.first()
                val connectionModeRestore = SettingsStore.normalizeConnectionMode(store.connectionMode.first())
                val isRawTunRestore = connectionModeRestore == SettingsStore.CONNECTION_MODE_RAWTUN
                val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
                val noDtlsEnabled = !isRawTunRestore && store.noDtlsEnabled.first()
                val serverDirectPort = if (manualPortsEnabled) store.serverDirectPort.first() else 56002
                val serverRawPort = if (manualPortsEnabled) store.serverRawPort.first() else 56003
                val effectiveServerPort = when {
                    isRawTunRestore -> serverRawPort
                    noDtlsEnabled -> serverDirectPort
                    else -> serverDtlsPort
                }
                val peerWithPort = if (basePeer.isBlank()) basePeer
                    else if (isRawTunRestore || noDtlsEnabled) PeerAddress.withPort(basePeer, effectiveServerPort)
                    else PeerAddress.ensurePort(basePeer, effectiveServerPort)
                val params = TunnelParams(
                    peer = peerWithPort,
                    vkHashes = store.vkHashes.first(),
                    secondaryVkHash = store.secondaryVkHash.first(),
                    workersPerHash = store.workersPerHash.first(),
                    port = store.listenPort.first(),
                    sni = store.sni.first(),
                    connectionPassword = store.connectionPassword.first(),
                    captchaMode = sanitizeCaptchaMode(store.captchaMode.first()),
                    captchaSolveMethod = store.captchaSolveMethod.first(),
                    vkAuthMode = store.vkAuthMode.first(),
                    vkAnonPath = SettingsStore.normalizeVkAnonPath(store.vkAnonPath.first()),
                    goDnsArg = store.resolveGoDnsArg(),
                    obfsMode = SettingsStore.normalizeObfsMode(store.obfsMode.first()),
                    connectionMode = SettingsStore.normalizeConnectionMode(store.connectionMode.first()),
                    socksPort = SettingsStore.normalizeSocksPort(store.socksPort.first()),
                    socksAuthEnabled = store.socksAuthEnabled.first(),
                    socksUsername = store.socksUsername.first(),
                    socksPassword = store.socksPassword.first(),
                    noDtls = noDtlsEnabled,
                    turnTcp = store.turnTcpEnabled.first(),
                    detailedLogs = store.detailedLogs.first()
                )
                if (params.peer.isNotEmpty() && params.vkHashes.isNotEmpty()) {
                    launch(Dispatchers.Main) {
                        startTunnel(params)
                    }
                } else {
                    launch(Dispatchers.Main) {
                        stopTunnel()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    stopTunnel()
                }
            }
        }
    }

    private fun startTunnel(params: TunnelParams, forceStart: Boolean = false) {
        // Учитываем и невалидированный Wi‑Fi: иначе через секунду VALIDATED
        // выглядит как «переход на Wi‑Fi» и стоп по опции убивает ручной старт.
        wasOnWifi = isUnderlyingWifiActive() || isUnderlyingWifiPresent()
        updateNotification("Подключение...")
        acquireWakeLock()
        acquireWifiLock()

        // Подготавливаем CaptchaWebViewManager (не создаёт WebView — просто сохраняет контекст)
        // Вызываем всегда — дёшево, а WebView создаётся на лету при каждом запросе капчи
        CaptchaWebViewManager.onTunnelStart(applicationContext)

        TunnelManager.start(this, params, isSwitching = false, forceStart = forceStart)
        startStatsUpdater()
    }

    private fun stopTunnel() {
        updateJob?.cancel()

        // Уничтожаем текущий WebView (если капча решается) и чистим контекст
        CaptchaWebViewManager.onTunnelStop()

        TunnelManager.stop()
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(TUNNEL_NOTIFICATION_ID)
        TunnelWidgetProvider.updateWidgetState(applicationContext, false, "Нажмите для подключения")
        QuickToggleTileService.requestTileUpdate(applicationContext)
        AppShortcuts.refreshAsync(applicationContext)
        stopSelf()
    }

    private fun setupNetworkCallback() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        activeNetworks.clear()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val wasEmpty = activeNetworks.isEmpty()
                activeNetworks.add(network)
                rememberNetworkFingerprint(network)
                checkWifiStopOnTransition()
                if (wasEmpty) {
                    if (isTunnelPaused) {
                        isTunnelPaused = false
                        Log.d("TunnelService", "Сеть появилась, возобновляем туннель")
                        recoveringFromNetworkLoss = true
                        updateNotification("Ожидание сети...")
                        scheduleNetworkReturnRecovery()
                    } else {
                        noteUnderlyingNetworkChange()
                    }
                } else {
                    noteUnderlyingNetworkChange()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                activeNetworks.remove(network)
                networkFingerprints.remove(network)
                checkWifiStopOnTransition()
                if (activeNetworks.isEmpty() && TunnelManager.running.value && !isTunnelPaused) {
                    isTunnelPaused = true
                    recoveringFromNetworkLoss = true
                    lastUnderlyingFingerprint = ""
                    Log.d("TunnelService", "Сеть потеряна, приостанавливаем туннель")
                    TunnelManager.pause()
                    updateNotification("Ожидание сети...")
                } else {
                    noteUnderlyingNetworkChange()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                if (network !in activeNetworks) return
                val prev = networkFingerprints[network]
                val next = rememberNetworkFingerprint(network)
                checkWifiStopOnTransition()
                if (prev != null && prev != next) {
                    noteUnderlyingNetworkChange()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                super.onLinkPropertiesChanged(network, linkProperties)
                if (network !in activeNetworks) return
                checkWifiStopOnTransition()
                noteUnderlyingNetworkChange()
            }
        }

        // ВАЖНО: Слушаем только реальные (не VPN) сети с доступом в интернет.
        // Иначе интерфейс VPN (tun0) считается активной сетью, и при "Режиме полёта" activeNetworks не падает до 0.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
            
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        wasOnWifi = isUnderlyingWifiPresent()
    }

    /**
     * Wi‑Fi для stop-on-Wi‑Fi: только валидированная сеть.
     * Иначе на OnePlus Wi‑Fi уже в activeNetworks до переключения трафика.
     */
    private fun isUnderlyingWifiActive(): Boolean {
        val cm = connectivityManager ?: return false
        return activeNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    /** Wi‑Fi уже всплыл, но ещё может быть без VALIDATED (момент перед стопом). */
    private fun isUnderlyingWifiPresent(): Boolean {
        val cm = connectivityManager ?: return false
        return activeNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun networkCapabilityFingerprint(caps: NetworkCapabilities): String {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }.joinToString("+")
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return "$transports|validated=$validated|internet=$internet"
    }

    private fun rememberNetworkFingerprint(network: Network): String {
        val cm = connectivityManager ?: return ""
        val caps = cm.getNetworkCapabilities(network) ?: return ""
        val fingerprint = networkCapabilityFingerprint(caps)
        networkFingerprints[network] = fingerprint
        return fingerprint
    }

    private fun activeUnderlyingFingerprint(): String {
        val cm = connectivityManager ?: return ""
        return activeNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@mapNotNull null
            "$network:${networkCapabilityFingerprint(caps)}"
        }.sorted().joinToString("|")
    }

    private fun noteUnderlyingNetworkChange() {
        val fingerprint = activeUnderlyingFingerprint()
        if (fingerprint.isEmpty()) return
        if (lastUnderlyingFingerprint.isEmpty()) {
            lastUnderlyingFingerprint = fingerprint
            return
        }
        if (fingerprint == lastUnderlyingFingerprint) return
        lastUnderlyingFingerprint = fingerprint
        handleNetworkChange()
    }

    private fun checkWifiStopOnTransition() {
        val nowOnWifi = isUnderlyingWifiPresent()
        val transitionedToWifi = nowOnWifi && !wasOnWifi
        wasOnWifi = nowOnWifi
        if (!transitionedToWifi || !TunnelManager.running.value || isTunnelPaused) return

        TunnelManager.scope.launch {
            if (!SettingsStore(applicationContext).stopOnWifi.first()) return@launch
            Log.d("TunnelService", "Подключились к Wi‑Fi — отключаем туннель по настройке")
            TunnelManager.addNetworkLog("[СЕТЬ] Wi‑Fi: туннель отключён (опция «Отключать на Wi‑Fi»)")
            launch(Dispatchers.Main) { stopTunnel() }
        }
    }

    private fun handleNetworkChange() {
        TunnelManager.scope.launch {
            if (recoveringFromNetworkLoss) {
                scheduleNetworkReturnRecovery()
            } else {
                scheduleNetworkRecovery()
            }
        }
    }

    private fun scheduleNetworkReturnRecovery() {
        networkRecoveryJob?.cancel()
        networkRecoveryJob = TunnelManager.scope.launch {
            if (!TunnelManager.running.value || isTunnelPaused) return@launch
            TunnelManager.addNetworkLog("[СЕТЬ] Сеть появилась. Ждём подтверждения и стабилизации.")
            delay(5_000)
            val validationDeadline = System.currentTimeMillis() + 25_000L
            while (!hasValidatedUnderlyingNetwork() && System.currentTimeMillis() < validationDeadline) {
                if (!TunnelManager.running.value || isTunnelPaused) return@launch
                delay(1_000)
            }
            if (!TunnelManager.running.value || isTunnelPaused || !hasValidatedUnderlyingNetwork()) return@launch
            recoveringFromNetworkLoss = false
            wasOnWifi = isUnderlyingWifiActive()
            performHybridNetworkRecovery("сеть появилась")
        }
    }

    private fun scheduleNetworkRecovery() {
        networkRecoveryJob?.cancel()
        networkRecoveryJob = TunnelManager.scope.launch {
            if (!TunnelManager.running.value || isTunnelPaused) return@launch
            val changedAt = System.currentTimeMillis()
            TunnelManager.addNetworkLog("[СЕТЬ] Сеть изменилась. Даём туннелю 15 секунд на самостоятельное восстановление.")
            delay(15_000)
            if (!TunnelManager.running.value || isTunnelPaused ||
                System.currentTimeMillis() < wakeGraceUntilMs || !hasValidatedUnderlyingNetwork()) return@launch
            if (TunnelManager.hasDownlinkTrafficSince(changedAt)) {
                TunnelManager.addNetworkLog("[СЕТЬ] Туннель сам восстановил трафик на новой сети.")
                return@launch
            }
            performHybridNetworkRecovery("смена сети")
        }
    }

    private suspend fun performHybridNetworkRecovery(reason: String) {
        if (!TunnelManager.running.value || isTunnelPaused || !hasValidatedUnderlyingNetwork()) return
        lastNetworkRecoveryAttemptMs = System.currentTimeMillis()
        val softRestartAt = System.currentTimeMillis()
        updateNotification("Восстановление транспорта...")
        TunnelManager.addNetworkLog("[СЕТЬ] Мягко перезапускаем транспорт, не закрывая VPN-интерфейс.")
        TunnelManager.restartTransport(reason, force = true)
        delay(30_000)
        if (!TunnelManager.running.value || isTunnelPaused || !hasValidatedUnderlyingNetwork()) return
        if (TunnelManager.hasHealthyRestartSince(softRestartAt)) {
            updateNotification("Подключено")
            TunnelManager.addNetworkLog("[СЕТЬ] Транспорт восстановился без пересоздания VPN.")
            return
        }
        lastNetworkRecoveryAttemptMs = System.currentTimeMillis()
        updateNotification("Полное восстановление...")
        TunnelManager.addNetworkLog("[СЕТЬ] Мягкий перезапуск не вернул связь. Полностью пересоздаём VPN и транспорт.")
        TunnelManager.reconnectAll("$reason: резервное восстановление", force = true)
    }

    private fun hasValidatedUnderlyingNetwork(): Boolean {
        val cm = connectivityManager ?: return false
        return activeNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
    }

    private fun setupScreenStateReceiver() {
        deviceWasInteractive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (!deviceWasInteractive) screenOffAtMs = System.currentTimeMillis()
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        deviceWasInteractive = false
                        screenOffAtMs = System.currentTimeMillis()
                        wakeRecoveryJob?.cancel()
                        wakeRecoveryJob = null
                        wakeGraceUntilMs = 0L
                        TunnelManager.addNetworkLog("[СОН] Экран выключен. Не перезапускаем VPN из-за фоновых событий сети.")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        deviceWasInteractive = true
                        val now = System.currentTimeMillis()
                        val screenOffDurationMs = if (screenOffAtMs > 0L) now - screenOffAtMs else 0L
                        screenOffAtMs = 0L
                        scheduleWakeRecovery(screenOffDurationMs)
                    }
                }
            }
        }
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    private fun scheduleWakeRecovery(screenOffDurationMs: Long) {
        wakeRecoveryJob?.cancel()
        val restartRawTransport = TunnelManager.isRawTunModeActive() &&
            screenOffDurationMs >= RAW_WAKE_RESTART_THRESHOLD_MS
        wakeGraceUntilMs = System.currentTimeMillis() + if (restartRawTransport) 45_000L else 30_000L
        wakeRecoveryJob = TunnelManager.scope.launch {
            if (!TunnelManager.running.value || isTunnelPaused) return@launch
            if (restartRawTransport) {
                networkRecoveryJob?.cancel()
                TunnelManager.addNetworkLog("[СОН] RAW был в фоне ${screenOffDurationMs / 1000} сек. Обновляем транспортные потоки.")
                val validationDeadline = System.currentTimeMillis() + 20_000L
                while (!hasValidatedUnderlyingNetwork() && System.currentTimeMillis() < validationDeadline) {
                    if (!deviceWasInteractive || !TunnelManager.running.value || isTunnelPaused) return@launch
                    delay(500)
                }
                if (!deviceWasInteractive || !TunnelManager.running.value || isTunnelPaused ||
                    !hasValidatedUnderlyingNetwork()) return@launch
                delay(2_000)
                if (!deviceWasInteractive || !TunnelManager.running.value || isTunnelPaused) return@launch
                lastNetworkRecoveryAttemptMs = System.currentTimeMillis()
                updateNotification("Восстановление после сна...")
                TunnelManager.restartTransport("пробуждение RAW после сна", force = true)
                return@launch
            }
            TunnelManager.addNetworkLog("[СОН] Устройство проснулось; даём текущему VPN восстановиться без перезапуска.")
            delay(30_000)
            if (!deviceWasInteractive || !TunnelManager.running.value || isTunnelPaused || !hasValidatedUnderlyingNetwork()) return@launch
            if (TunnelManager.hasRecentTransportActivity(45_000L)) {
                TunnelManager.addNetworkLog("[СОН] VPN подал свежие признаки жизни после пробуждения.")
                return@launch
            }
            val now = System.currentTimeMillis()
            if (now - lastNetworkRecoveryAttemptMs < 120_000L) return@launch
            lastNetworkRecoveryAttemptMs = now
            updateNotification("Восстановление после сна...")
            TunnelManager.addNetworkLog("[СОН] VPN не ожил после пробуждения. Мягко переподключаем транспорт.")
            TunnelManager.restartTransport()
        }
    }

    private fun sanitizeCaptchaMode(mode: String?): String {
        return when (mode?.lowercase()) {
            "auto" -> "auto"
            "rjs" -> "rjs"
            "wv" -> "wv"
            else -> "auto"
        }
    }


    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "wdtt:tunnel_cpu"
        ).apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        
        // Используем WIFI_MODE_FULL_LOW_LATENCY для Android 10+, 
        // это предотвращает отключение радиомодуля при выключенном экране
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        
        wifiLock = wm.createWifiLock(mode, "wdtt:wifi_perf").apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        wifiLock = null
    }

    private fun startStatsUpdater() {
        updateJob?.cancel()
        updateJob = TunnelManager.scope.launch(Dispatchers.Main) {
            // Сторож следит за пропажей уже поднятого VPN-интерфейса, а не за его
            // отсутствием во время подключения (капча, VK-креды и т.д.).
            var wasEverUp = false
            delay(1000)

            val reconnectObserver = launch {
                TunnelManager.isReconnecting.collectLatest { reconnecting ->
                    if (reconnecting && TunnelManager.running.value && !isTunnelPaused) {
                        updateNotification("Переподключение...")
                    }
                }
            }

            try {
                while (isActive) {
                    if (!TunnelManager.running.value && !isTunnelPaused) {
                        if (TunnelManager.isReconnecting.value || TunnelManager.transportRestartInProgress) {
                            delay(2000)
                            continue
                        }
                        // Старт ещё идёт (вход по аккаунту VK, DNS, капча…) — FGS нельзя убивать,
                        // иначе Android снимает уведомление и усыпляет приложение.
                        if (TunnelManager.isConnecting.value) {
                            val connectingText = TunnelManager.stats.value.trim().ifEmpty { "Подключение..." }
                            updateNotification(connectingText)
                            delay(2000)
                            continue
                        }
                        // Туннель полностью остановлен (не на паузе) — убиваем сервис
                        stopSelf()
                        break
                    }
                    if (TunnelManager.running.value && !isTunnelPaused && !TunnelManager.isSocksModeActive()) {
                        val helper = WireGuardHelper(applicationContext)
                        when (helper.watchdogState()) {
                            WireGuardHelper.WatchdogState.UP -> wasEverUp = true
                            WireGuardHelper.WatchdogState.DISABLED_BY_EMPTY_WHITELIST -> Unit
                            WireGuardHelper.WatchdogState.DOWN -> {
                                if (wasEverUp) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastVpnReconnectAttemptMs >= 15_000) {
                                        lastVpnReconnectAttemptMs = now
                                        Log.w(
                                            "TunnelService",
                                            "VPN-интерфейс пропал — пробуем переподключение"
                                        )
                                        updateNotification("Переподключение VPN...")
                                        TunnelManager.reconnectAll("пропал VPN-интерфейс")
                                        wasEverUp = false
                                    }
                                }
                            }
                        }
                    }
                    if (!isTunnelPaused && !TunnelManager.isReconnecting.value) {
                        updateNotification(buildTunnelNotificationText())
                    }
                    delay(2000)
                }
            } finally {
                reconnectObserver.cancel()
            }
        }
    }

    private fun buildTunnelNotificationText(): String {
        val socks = TunnelManager.activeSocksListenAddress()
        if (socks != null) {
            val statsText = TunnelManager.stats.value.trim()
            return when {
                statsText.isEmpty() || statsText == "Ожидание данных..." || statsText.startsWith("SOCKS") ->
                    "SOCKS $socks"
                else -> "$statsText · $socks"
            }
        }
        val statsText = TunnelManager.stats.value.trim()
        return when {
            statsText.isEmpty() -> "Туннель активен"
            statsText == "Ожидание данных..." -> "Туннель активен"
            else -> statsText
        }
    }

    private fun createNotificationChannel() {
        NotificationHelper.ensureTunnelChannel(this)
    }

    private fun createNotification(text: String, actionName: String = "STOP", actionTitle: String = "Отключить"): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = PendingIntent.getService(
            this, if (actionName == "STOP") 1 else 2,
            Intent(this, TunnelService::class.java).apply { action = actionName },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TUNNEL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("qWDTT")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setOngoing(true)
            .setLocalOnly(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, actionTitle, stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
            // ВАЖНО: Делаем уведомление публичным (видимым на локскрине)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Категория SERVICE помогает системе понять важность
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(false)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun startPersistentForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(TUNNEL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TUNNEL_NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (lastNotificationText == text) return
        lastNotificationText = text
        val notification = createNotification(text)
        // Обновляем через startForeground — так надёжнее на Android 13+ и китайских прошивках.
        startPersistentForeground(notification)
        TunnelWidgetProvider.updateWidgetState(applicationContext, TunnelManager.running.value, text)
        QuickToggleTileService.requestTileUpdate(applicationContext)
        AppShortcuts.refreshAsync(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkRecoveryJob?.cancel()
        wakeRecoveryJob?.cancel()
        screenStateReceiver?.let { unregisterReceiver(it) }
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
        stopTunnel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
