package com.wdtt.client

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

import androidx.compose.runtime.Stable

@Stable
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99, // 0 - Creds, 1 - DTLS, 2 - Ready, 3 - Stats, 99 - Errors/Other
    val isError: Boolean = false
)

object TunnelManager {
    // 100% защита от утечек: единый управляемый глобальный Scope
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    private var readerJob: Job? = null
    private var watchdogJob: Job? = null
    private var detailedLogsJob: Job? = null
    private var wgHelper: WireGuardHelper? = null
    
    @Volatile
    private var isDetailedLogsEnabled = false
    @Volatile
    private var isConnectionPipelineEnabled = true

    // Error counters for circuit breaker
    private var floodCount = 0
    private var mismatchCount = 0
    private var refusedCount = 0
    private var currentHashErrorCount = 0
    private var wrapAuthTimeoutCount = 0
    private var processStartedAtMs = 0L
    private var lastActiveAtMs = 0L
    private var lastStatsReceivedAtMs = 0L
    @Volatile private var lastUplinkMb = 0.0
    @Volatile private var lastDownlinkMb = 0.0
    @Volatile private var lastUplinkChangedAtMs = 0L
    @Volatile private var lastDownlinkChangedAtMs = 0L
    private var lastReconnectAtMs = 0L
    private val reconnectMutex = Mutex()
    @Volatile private var keepWireGuardOnNextConfig = false
    @Volatile private var preservedWireGuardConfig: String? = null
    @Volatile
    private var preserveVpnDuringReconnect = false

    private const val STALE_STATS_MS = 90_000L
    private const val HEALTH_CHECK_GRACE_MS = 120_000L
    private const val MIN_RECONNECT_INTERVAL_MS = 10_000L
    private var activeHashIndex = 0 // 0: primary, 1: secondary
    private var currentParams: TunnelParams? = null
    // Имя unix-сокета (без "@") для передачи TUN fd в go_client в режиме rawtun.
    // Генерируется заново на каждый запуск в start(), читается в startLogReader().
    @Volatile
    private var rawTunSockName: String = ""
    private var lastContext: java.lang.ref.WeakReference<Context>? = null
    private var forceRegenerateUA = false // принудительная перегенерация UA при ошибках
    private var currentCaptchaMode = "wv" // режим обхода капчи: "wv" или "rjs"
    private var currentCaptchaSolveMethod = "auto" // "manual" или "auto"
    private var restartAttempts = 0
    private val maxRestartBackoffSec = 30

    private var activeProfileId = ""
    private var lastSavedTrafficMb = 0.0
    private var lastSessionTrafficMb = 0.0

    val running = MutableStateFlow(false)
    /** true с момента нажатия «Подключить» до запуска Go-процесса или ошибки/отмены. */
    val isConnecting = MutableStateFlow(false)
    /** Epoch ms когда текущий Go-процесс стал running; 0 если не подключён. */
    val connectedSinceMs = MutableStateFlow(0L)
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val unreadErrorCount = MutableStateFlow(0)
    /**
     * Последняя фатальная ошибка запуска, переживающая clearLogs(). Без этого
     * пользователь не успевал прочитать/скопировать текст ошибки вроде
     * "Ошибка запуска VPN: ..." — при повторном нажатии "Подключить" start()
     * вызывает clearLogs() и сообщение исчезает мгновенно вместе со всем логом.
     */
    val lastFatalError = MutableStateFlow<String?>(null)
    val config = MutableStateFlow<String?>(null)
    val stats = MutableStateFlow("Ожидание данных...")
    val activeWorkers = MutableStateFlow(0)
    val isReconnecting = MutableStateFlow(false)
    val connectionPipeline = MutableStateFlow(ConnectionPipelineState())
    /** Плановый рестарт транспорта (смена сети): log reader не должен сбрасывать running. */
    @Volatile
    var transportRestartInProgress: Boolean = false
        private set

    fun formatUptime(elapsedMs: Long): String {
        val totalSec = (elapsedMs.coerceAtLeast(0L)) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun markRunning(value: Boolean) {
        running.value = value
        connectedSinceMs.value = if (value) System.currentTimeMillis() else 0L
    }
    
    val cooldownSeconds = MutableStateFlow(0)
    private var cooldownJob: Job? = null
    private var startJob: Job? = null
    private var stopJob: Job? = null
    private var pipelineHideJob: Job? = null
    private var pipelineStepTimeoutJob: Job? = null
    @Volatile
    private var connectingStartedAtMs = 0L
    private val startGate = Any()
    private const val CONNECT_STOP_GRACE_MS = 2_500L
    /** После успешного подключения схема скрывается, чтобы не занимать логи. */
    private const val PIPELINE_HIDE_AFTER_SUCCESS_MS = 4_000L
    /** Лимит на один шаг схемы (кроме Потоков и Капчи). */
    private const val PIPELINE_STEP_TIMEOUT_MS = 10_000L
    private const val PIPELINE_RAW_STEP_TIMEOUT_MS = 25_000L
    /** Вход в несколько звонков через аккаунт VK может занять дольше. */
    private const val PIPELINE_VK_STEP_TIMEOUT_MS = 30_000L
    /** Инкремент → MainActivity / SettingsTab открывают диалог ⚙️ настроек. */
    val openAppSettingsRequest = MutableStateFlow(0L)

    fun requestOpenAppSettings() {
        openAppSettingsRequest.value = System.currentTimeMillis()
    }

    /** Сразу показывает статус на вкладке «Логи», ещё до старта сервиса / VK auth. */
    fun beginConnecting(hint: String = "Подключение…") {
        if (running.value) return
        connectingStartedAtMs = System.currentTimeMillis()
        isConnecting.value = true
        stats.value = hint
        val ctx = lastContext?.get()
        if (ctx != null) {
            scope.launch {
                isConnectionPipelineEnabled = SettingsStore(ctx).connectionPipelineEnabled.first()
                if (!isConnectionPipelineEnabled) {
                    hideConnectionPipeline()
                } else if (isConnecting.value && !running.value) {
                    resetConnectionPipeline()
                }
            }
        }
        resetConnectionPipeline()
    }

    /** Вызывается из настроек при выключении схемы. */
    fun hideConnectionPipelineForSettings() {
        isConnectionPipelineEnabled = false
        hideConnectionPipeline()
    }

    fun cancelConnectingIfNeeded() {
        if (!isConnecting.value || running.value) return
        stop(force = true)
    }

    fun clearUnreadErrors() {
        unreadErrorCount.value = 0
    }

    // Добавляем лог с Деплоя
    fun addDeployErrorLog(message: String) {
        val hash = message.hashCode().toString()
        updateLog("deploy_err_$hash", "[ДЕПЛОЙ] $message", 99, true)
    }

    fun addDeploySuccessLog(message: String) {
        val hash = message.hashCode().toString() + System.currentTimeMillis()
        updateLog("deploy_ok_$hash", "[ДЕПЛОЙ] $message", 2, false)
    }

    fun addDeployLog(message: String) {
        val key = "deploy_info_${message.take(48).hashCode()}"
        updateLog(key, "[ДЕПЛОЙ] $message", 50, false)
    }

    fun addVkAuthLog(message: String, isError: Boolean = false, verbose: Boolean = false) {
        if (verbose && !isDetailedLogsEnabled && !isError) return
        val key = "vk_auth_dbg_${message.hashCode()}_${System.nanoTime()}"
        updateLog(key, "[VK Auth] $message", 5, isError)
    }

    fun addNetworkLog(message: String) {
        updateLog("network_event", message, 2, false)
    }

    /**
     * Подробная диагностика подъёма Raw TUN (см. RawTunVpnService/RawTunEngine).
     * В отличие от addNetworkLog — каждый вызов отдельная строка, не схлопывается
     * с предыдущей: нужна полная последовательность шагов для разбора отказов
     * на конкретных устройствах/версиях Android, а не только последний шаг.
     *
     * В обычном (не подробном) режиме в UI-лог НЕ идёт вообще — иначе экран
     * логов забивается десятками уникальных строк на каждое подключение
     * (ключ включает System.nanoTime(), так что дедупликация updateLog их
     * никогда не схлопывает). Каждый вызывающий (RawTunEngine/RawTunVpnService/
     * TunFdBridge) уже пишет то же сообщение напрямую в android.util.Log
     * отдельно от этого метода — так что для logcat/кнопки "Поделиться" эти
     * строки всё равно доступны независимо от режима. isError всегда
     * показываем — реальная ошибка должна быть видна сразу, не только при
     * включённом подробном режиме.
     */
    fun addRawDiagLog(message: String, isError: Boolean = false) {
        if (!isDetailedLogsEnabled && !isError) return
        val key = "raw_diag_${message.hashCode()}_${System.nanoTime()}"
        updateLog(key, "[RAW-DIAG ${formatRawDiagTime()}] $message", 3, isError)
    }

    // HH:mm:ss.SSS — нужна именно миллисекундная точность, чтобы сопоставлять
    // моменты Android-стороны (TunFdBridge.sendOnce) с моментами go_client
    // (recvTunFD, свои [RAW-DIAG]-строки в stdout) при разборе таймлайна
    // подъёма Raw TUN на устройствах, где он подвисает. Новый Calendar на
    // каждый вызов — SimpleDateFormat не потокобезопасен, а этот лог зовут
    // и из RawTunEngine (Dispatchers.IO), и из RawTunVpnService.
    private fun formatRawDiagTime(): String {
        val c = java.util.Calendar.getInstance()
        return String.format(
            java.util.Locale.US, "%02d:%02d:%02d.%03d",
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND),
            c.get(java.util.Calendar.MILLISECOND)
        )
    }

    private fun updateLog(key: String, message: String, priority: Int, isError: Boolean = false) {
        if (isError) {
            val list = logs.value
            if (list.none { it.key == key }) {
                unreadErrorCount.value++
            }
        }
        logs.update { currentList ->
            val current = currentList.toMutableList()
            val index = current.indexOfFirst { it.key == key }

            if (index != -1) {
                // Обновляем текст и счётчик НА МЕСТЕ
                val entry = current[index]
                current[index] = entry.copy(count = entry.count + 1, message = message, priority = priority, isError = isError)
            } else {
                // Новая запись
                current.add(LogEntry(key, message, 1, priority, isError))
            }

            // Сортировка: по приоритету (наименьший сверху), затем ошибки
            // Приоритеты: Основной=1, Капча=5, Готов=10, Статы=100, Ошибки=200
            val sorted = current.sortedWith(compareBy({ it.priority }, { if (it.isError) 1 else 0 }, { it.key }))

            // Лимит 100 записей
            if (sorted.size > 100) sorted.take(100) else sorted
        }
    }

    fun start(context: Context, params: TunnelParams, isSwitching: Boolean = false, forceStart: Boolean = false) {
        android.util.Log.d("WDTT", "TunnelManager.start() called. isSwitching=$isSwitching, forceStart=$forceStart, running=${running.value}, connecting=${isConnecting.value}")
        synchronized(startGate) {
            if (running.value && !isSwitching) return
            // Повторный START (сервис/UI) не должен убивать текущий вход в звонок.
            if (!isSwitching && startJob?.isActive == true) {
                android.util.Log.d("WDTT", "start() ignored: connect already in progress")
                return
            }
        }
        
        val appContext = context.applicationContext // Защита от Memory Leak
        
        if (!isSwitching) {
            clearLogs()
            lastFatalError.value = null
            // Флаг обновится в startJob через first()/collect; пока — кэш.
            currentParams = params
            resetConnectionPipeline()
            config.value = null
            connectingStartedAtMs = System.currentTimeMillis()
            isConnecting.value = true
            stats.value = "Подключение…"
            
            detailedLogsJob?.cancel()
            detailedLogsJob = scope.launch {
                launch {
                    SettingsStore(appContext).detailedLogs.collect {
                        isDetailedLogsEnabled = it
                    }
                }
                launch {
                    SettingsStore(appContext).connectionPipelineEnabled.collect { enabled ->
                        isConnectionPipelineEnabled = enabled
                        if (!enabled) hideConnectionPipeline()
                    }
                }
            }
            floodCount = 0
            mismatchCount = 0
            refusedCount = 0
            currentHashErrorCount = 0
            wrapAuthTimeoutCount = 0
            processStartedAtMs = 0L
            lastActiveAtMs = 0L
            lastStatsReceivedAtMs = 0L
            resetTransportTrafficHealth()
            keepWireGuardOnNextConfig = false
            preservedWireGuardConfig = null
            activeHashIndex = 0
            lastContext = java.lang.ref.WeakReference(appContext)
            forceRegenerateUA = false
            currentCaptchaMode = params.captchaMode
            currentCaptchaSolveMethod = params.captchaSolveMethod
            activeProfileId = ""
            lastSavedTrafficMb = 0.0
            lastSessionTrafficMb = 0.0
        }
        
        wgHelper = WireGuardHelper(appContext)

        synchronized(startGate) {
            if (!isSwitching && startJob?.isActive == true) {
                android.util.Log.d("WDTT", "start() ignored after prepare: already in progress")
                return
            }
            startJob = scope.launch {
            try {
                // Дожидаемся завершения фонового teardown от предыдущего stop()
                // (см. stop(): он больше не блокирует вызывающий поток через
                // runBlocking, а запускает teardown в stopJob асинхронно) —
                // иначе новый старт может поднять WireGuard/Raw поверх ещё не
                // до конца погашенного предыдущего интерфейса.
                stopJob?.join()
                isDetailedLogsEnabled = runCatching {
                    SettingsStore(appContext).detailedLogs.first()
                }.getOrDefault(false)
                if (!isSwitching) {
                    isConnectionPipelineEnabled = runCatching {
                        SettingsStore(appContext).connectionPipelineEnabled.first()
                    }.getOrDefault(true)
                    if (!isConnectionPipelineEnabled) {
                        hideConnectionPipeline()
                    } else if (!connectionPipeline.value.visible) {
                        resetConnectionPipeline()
                    }
                }

                if (!isSwitching) {
                    try {
                        activeProfileId = SettingsStore(appContext).currentProfileId.first()
                    } catch (_: Exception) {
                        activeProfileId = ""
                    }
                }
                val targetHash = if (activeHashIndex == 0) params.vkHashes else params.secondaryVkHash
                
                // Robust hash parsing: split by comma, newline, or whitespace
                val hashList = targetHash
                    .split(Regex("[,\\s\\n]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(SettingsStore.MAX_VK_HASHES)

                if (hashList.isEmpty()) {
                    updateLog("hash_error", "Ошибка: Хеш не указан", 99, true)
                    abortStart(isSwitching, "Хеш не указан")
                    return@launch
                }
                if (params.connectionPassword.isBlank()) {
                    updateLog("password_error", "Ошибка: пароль подключения не указан", 99, true)
                    abortStart(isSwitching, "Пароль не указан")
                    return@launch
                }
                if (
                    SettingsStore.normalizeConnectionMode(params.connectionMode) == SettingsStore.CONNECTION_MODE_SOCKS &&
                    params.socksAuthEnabled &&
                    (params.socksUsername.isBlank() || params.socksPassword.isBlank() ||
                        params.socksUsername.toByteArray().size > 255 || params.socksPassword.toByteArray().size > 255)
                ) {
                    updateLog("socks_auth_error", "Ошибка: заполните корректные логин и пароль SOCKS5", 99, true)
                    abortStart(isSwitching, "Не заполнены данные авторизации SOCKS5")
                    return@launch
                }

                val hashCount = hashList.size.coerceIn(1, SettingsStore.MAX_VK_HASHES)
                val accountMode = !params.vkAuthMode.equals("anonymous", ignoreCase = true)
                val maxWorkers = if (accountMode) {
                    SettingsStore.VK_ACCOUNT_MAX_WORKERS
                } else {
                    SettingsStore.maxAnonymousWorkers(hashCount)
                }
                val totalWorkers = params.workersPerHash.coerceIn(1, maxWorkers)
                
                val hashMode = if (activeHashIndex == 0) "Основной" else "Запасной"
                updateLog("config_info", "[$hashMode] Хешей=$hashCount, Потоков=$totalWorkers", 1)


                // CRITICAL FIX: Use nativeLibraryDir with extractNativeLibs="true"
                val binaryPath = context.applicationInfo.nativeLibraryDir + "/libclient.so"
                val binaryFile = File(binaryPath)
                
                if (!binaryFile.exists()) {
                    updateLog("binary_error", "Ошибка: Бинарный файл не найден", 99, true)
                    abortStart(isSwitching, "Бинарный файл не найден")
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    ensureTransportStopped(params.port)
                    VkCaptchaProfile.writeForGo(appContext)
                }

                val cmd = mutableListOf(
                    binaryPath,
                    "-peer", params.peer,
                    "-vk", hashList.joinToString(","),
                    "-n", totalWorkers.toString(),
                    "-listen", "127.0.0.1:${params.port}"
                )

                val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
                cmd.add("-device-id")
                cmd.add(androidId)

                cmd.add("-password")
                cmd.add(params.connectionPassword)

                // Captcha mode: wv или rjs
                cmd.add("-captcha-mode")
                cmd.add(params.captchaMode)

                cmd.add("-vk-auth")
                cmd.add(if (params.vkAuthMode.equals("anonymous", ignoreCase = true)) "anonymous" else "account")

                if (params.vkAuthMode.equals("anonymous", ignoreCase = true)) {
                    cmd.add("-vk-anon-path")
                    cmd.add(params.vkAnonPath)
                    updateLog("vk_anon_path", "[КЛИЕНТ] Режим VK: ${params.vkAnonPath}", 1, false)
                }

                cmd.add("-go-dns")
                cmd.add(params.goDnsArg)

                cmd.add("-obfs")
                cmd.add(SettingsStore.normalizeObfsMode(params.obfsMode))
                updateLog(
                    "obfs_mode",
                    "[СЕТЬ] Маскировка: ${SettingsStore.obfsModeDisplay(params.obfsMode)}",
                    1,
                    false
                )

                if (params.noDtls) {
                    cmd.add("-notls")
                    updateLog(
                        "no_dtls",
                        "[СЕТЬ] Транспорт: без DTLS (RTP-obfs напрямую, нужен сервер с -listen-direct)",
                        1,
                        false
                    )
                }

                if (params.turnTcp) {
                    cmd.add("-turn-tcp")
                }

                val mode = SettingsStore.normalizeConnectionMode(params.connectionMode)
                cmd.add("-mode")
                when {
                    // rawtun: go_client понимает "-mode rawtun", TUN-fd прилетит позже через unix-сокет.
                    mode == SettingsStore.CONNECTION_MODE_RAWTUN -> {
                        cmd.add("rawtun")
                        rawTunSockName = TunFdBridge.newSocketName()
                        cmd.add("-tun-fd-sock")
                        cmd.add(TunFdBridge.goSockPath(rawTunSockName))
                        updateLog("conn_mode", "[СЕТЬ] Режим: VPN (raw-IP, без WireGuard)", 1, false)
                    }
                    mode == SettingsStore.CONNECTION_MODE_VPN -> {
                        cmd.add("vpn")
                        updateLog("conn_mode", "[СЕТЬ] Режим: VPN (WireGuard)", 1, false)
                    }
                    else -> {
                        cmd.add("socks")
                        val socks = params.socksListenAddress
                        cmd.add("-socks")
                        cmd.add(socks)
                        if (params.socksAuthEnabled) {
                            cmd.add("-socks-auth")
                            cmd.add("-socks-user")
                            cmd.add(params.socksUsername)
                            cmd.add("-socks-pass")
                            cmd.add(params.socksPassword)
                        }
                        val authLabel = if (params.socksAuthEnabled) ", с авторизацией" else ""
                        updateLog("conn_mode", "[СЕТЬ] Режим: SOCKS5 ($socks$authLabel), без VPN", 1, false)
                    }
                }

                setConnectionPipelineCurrent(ConnectionStep.DNS)
                var dnsProbe = GoDnsProbe.check(params.goDnsArg)
                if (isSwitching && !dnsProbe.reachable) {
                    for (attempt in 2..4) {
                        updateLog(
                            "go_dns_reconnect_wait",
                            "[СЕТЬ] DNS ещё не готов после смены сети. Повторяем проверку ($attempt/4)...",
                            50,
                            false,
                        )
                        delay(2_000)
                        dnsProbe = GoDnsProbe.check(params.goDnsArg)
                        if (dnsProbe.reachable) break
                    }
                }
                if (!dnsProbe.reachable) {
                    updateLog(
                        "go_dns_precheck_fail",
                        "[СЕТЬ] DNS недоступен: ${dnsProbe.statusText}",
                        50,
                        true
                    )
                    failConnectionPipeline(ConnectionStep.DNS)
                    updateLog(
                        "go_dns_tip",
                        "[СЕТЬ] Смените DNS в ⚙️ → Сеть (Яндекс / Cloudflare / Google / DoH / Свой)",
                        50,
                        true
                    )
                    abortStart(isSwitching, "DNS недоступен")
                    return@launch
                } else {
                    updateLog("go_dns_precheck_ok", "[СЕТЬ] DNS доступен: ${dnsProbe.statusText}", 1, false)
                    advanceConnectionPipeline(ConnectionStep.DNS, ConnectionStep.VK)
                }

                if (!params.vkAuthMode.equals("anonymous", ignoreCase = true)) {
                    try {
                        stats.value = "VK: вход в звонок…"
                        updateLog("vk_auth_start", "[VK Auth] Вход в звонок…", 5, false)
                        setConnectionPipelineCurrent(ConnectionStep.VK)
                        val credsByHash = VkAuthWebViewManager.authenticateAll(appContext, hashList)
                        val credsFile = VkAuthWebViewManager.writeCredsFile(appContext, credsByHash)
                        cmd.add("-vk-creds-file")
                        cmd.add(credsFile.absolutePath)
                        stats.value = "Запуск туннеля…"
                        updateLog("vk_auth_ok", "[VK Auth] TURN OK (${credsByHash.size})", 5, false)
                        advanceConnectionPipeline(ConnectionStep.VK, ConnectionStep.WRAP)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val msg = e.message ?: e::class.java.simpleName
                        updateLog("vk_auth_fail", "Ошибка авторизации VK: $msg", 99, true)
                        failConnectionPipeline(ConnectionStep.VK)
                        abortStart(isSwitching, msg)
                        return@launch
                    }
                }

                if (!isActive) {
                    abortStart(isSwitching, "Подключение прервано")
                    return@launch
                }

                val pb = ProcessBuilder(cmd)
                pb.directory(context.filesDir) // Устанавливаем рабочую директорию
                pb.redirectErrorStream(true)
                
                // Set LD_LIBRARY_PATH
                val env = pb.environment()
                env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

                process = pb.start()
                processStartedAtMs = System.currentTimeMillis()
                wrapAuthTimeoutCount = 0
                lastActiveAtMs = 0L
                lastStatsReceivedAtMs = System.currentTimeMillis()
                if (!isSwitching) {
                    transportRestartInProgress = false
                }
                isConnecting.value = false
                markRunning(true)
                stats.value = "Ожидание данных..."
                startLogReader()
                startWatchdog(appContext, params)

            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!isSwitching) {
                    updateLog("start_cancelled", "Подключение отменено", 50, false)
                    finishConnectingFailed()
                }
                throw e
            } catch (e: Exception) {
                if (isSwitching) {
                    handleSwitchingStartFailed("Критическая ошибка: ${e.message}")
                } else {
                    updateLog("critical_start_error", "Критическая ошибка запуска: ${e.message}", 99, true)
                    e.printStackTrace()
                    finishConnectingFailed()
                }
            }
        }
        }
    }

    private fun abortStart(isSwitching: Boolean, message: String) {
        if (isSwitching) {
            handleSwitchingStartFailed(message)
        } else {
            finishConnectingFailed()
        }
    }

    private fun finishConnectingFailed() {
        transportRestartInProgress = false
        isConnecting.value = false
        markRunning(false)
        if (stats.value == "Подключение…" ||
            stats.value.startsWith("VK:") ||
            stats.value == "Запуск туннеля…"
        ) {
            stats.value = "Ожидание данных..."
        }
    }

    /**
     * Останавливает raw-VPN, если текущая (ещё не сброшенная) сессия была в
     * этом режиме — синхронно (blocking), чтобы к моменту возврата из stop()
     * TUN/VpnService уже были закрыты. Раньше это запускалось fire-and-forget
     * в отдельной корутине и не дожидалось завершения — при быстром
     * переключении режима (Raw -> VPN) новый go_client мог начать
     * подниматься раньше, чем предыдущая Raw-сессия успевала освободить
     * ресурсы, что иногда приводило к "WireGuard start failed" на новом
     * старте. stopLocked() внутри RawTunEngine.stop() лёгкая (closeTun +
     * stopService), так что runBlocking здесь не создаёт заметной задержки.
     */
    private fun stopRawTunIfNeeded() {
        if (currentParams?.isRawTunMode != true) return
        val ctx = lastContext?.get() ?: return
        runBlocking { runCatching { RawTunEngine.stop(ctx) } }
    }

    /** Вызывается из RawTunVpnService.onRevoke() — система сама отозвала VPN-разрешение. */
    fun onRawTunRevoked() {
        RawTunEngine.onVpnRevoked()
    }

    private fun handleReconnectFailed(reason: String) {
        transportRestartInProgress = false
        isReconnecting.value = false
        updateLog("reconnect_fail", "❌ Переподключение не удалось: $reason", 99, true)
        scope.launch(Dispatchers.Main) {
            wgHelper?.stopTunnel()
            stop(force = true)
        }
    }

    private fun handleSwitchingStartFailed(reason: String) {
        if (!preserveVpnDuringReconnect) {
            handleReconnectFailed(reason)
            return
        }
        transportRestartInProgress = false
        isReconnecting.value = false
        isConnecting.value = false
        markRunning(true)
        updateLog("soft_reconnect_fail", "⚠ Мягкое восстановление не удалось: $reason", 99, true)
    }

    @SuppressLint("StaticFieldLeak")
    private fun startLogReader() {
        readerJob = scope.launch {
            val observedProcess = process ?: return@launch
            val reader = observedProcess.inputStream.bufferedReader()
            var collectingConfig = false
            val configBuilder = StringBuilder()
            var collectingRawConfig = false
            val rawConfigBuilder = StringBuilder()

            try {
                var lastResetTime = System.currentTimeMillis()

                reader.forEachLine { line ->
                    // Периодический сброс счетчиков ошибок (раз в 60 сек)
                    val now = System.currentTimeMillis()
                    if (now - lastResetTime > 60000) {
                        refusedCount = 0
                        floodCount = 0
                        mismatchCount = 0
                        currentHashErrorCount = 0
                        lastResetTime = now
                    }

                    // Чистим лог от даты из Go (например, "2023/10/24 12:34:56.123456 [ВОРКЕР...")
                    val msgPrefixReplaced = line.replace(Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s"), "")
                    val lineTrim = msgPrefixReplaced.trim()

                    // SOCKS IPv6: туннель только IPv4 — клиент сам уйдёт на A-запись.
                    if (isBenignSocksIpv6Noise(lineTrim)) {
                        return@forEachLine
                    }

                    val isError = lineTrim.contains("Ошибка", true) || lineTrim.contains("error", true) || lineTrim.contains("FAIL", true) || lineTrim.contains("timeout", true) || lineTrim.contains("refused", true) || lineTrim.contains("FATAL_AUTH", true)

                    // 0. FATAL AUTH — мгновенная остановка (пароль / срок / устройство)
                    if (lineTrim.contains("FATAL_AUTH")) {
                        val reason = when {
                            lineTrim.contains("неверный пароль") -> "Неверный пароль подключения"
                            lineTrim.contains("истёк") -> "Срок действия пароля истёк"
                            lineTrim.contains("другому устройству") -> "Пароль привязан к другому устройству"
                            else -> "Ошибка авторизации"
                        }
                        handleCriticalError("\uD83D\uDD12 $reason. Воркеры остановлены.")
                        return@forEachLine
                    }

                    // 0a. WRAP auth timeout — не фатально для отдельного воркера.
                    // Критичным считаем только ситуацию, когда за стартовое окно не поднялся ни один поток.
                    if (lineTrim.contains("WRAP_AUTH_TIMEOUT", true)) {
                        if (activeWorkers.value > 0) {
                            wrapAuthTimeoutCount = 0
                            updateLog(
                                "wrap_timeout_recovered",
                                "[WRAP] Один поток не прошёл handshake, активных=${activeWorkers.value}; повторяем",
                                50,
                                true
                            )
                        } else {
                            wrapAuthTimeoutCount++
                            updateLog(
                                "wrap_timeout_wait",
                                wrapHandshakeWaitMessage(wrapAuthTimeoutCount),
                                50,
                                true
                            )
                            updateLog(
                                "wrap_timeout_hint",
                                "[ПОДСКАЗКА] Проверьте пароль профиля, IP/порт сервера и что wdtt-server запущен. " +
                                    "Если VK режет UDP — попробуйте другую сеть или меньше потоков.",
                                50,
                                true
                            )
                            if (activeWorkers.value <= 0) {
                                failConnectionPipeline(ConnectionStep.DTLS)
                            }
                        }
                        return@forEachLine
                    }

                    // 0b. CAPTCHA_SOLVE — запрос от Go для WBV-режима.
                    if (lineTrim.startsWith("CAPTCHA_SOLVE|")) {
                        val payload = lineTrim.substringAfter("CAPTCHA_SOLVE|")
                        val parts = payload.split("|", limit = 3)
                        when (parts.size) {
                            3 -> {
                                val requestMode = parts[0]
                                val redirectUri = parts[1]
                                val sessionToken = parts[2]
                                scope.launch {
                                    handleCaptchaSolve(requestMode, redirectUri, sessionToken)
                                }
                            }
                            2 -> {
                                val redirectUri = parts[0]
                                val sessionToken = parts[1]
                                scope.launch {
                                    handleCaptchaSolve("selected", redirectUri, sessionToken)
                                }
                            }
                            else -> {
                                writeCaptchaResult("error:invalid CAPTCHA_SOLVE format")
                            }
                        }
                        return@forEachLine
                    }

                    // 0c. VK_AUTH_REQUIRED — обновление TURN через аккаунт VK
                    if (lineTrim.startsWith("VK_AUTH_REQUIRED|")) {
                        val hash = lineTrim.substringAfter("VK_AUTH_REQUIRED|").trim()
                        if (hash.isNotEmpty()) {
                            scope.launch {
                                handleVkAuthRequired(hash)
                            }
                        }
                        return@forEachLine
                    }

                    // 1. ПРЕДОХРАНИТЕЛЬ (Circuit Breaker)
                    if (isError) {
                        when {
                            lineTrim.contains("Flood control", true) -> {
                                floodCount++
                                if (floodCount >= 5) {
                                    handleCriticalError("Flood Control (ВК ограничил ваш IP). Попробуйте позже.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("ip mismatch", true) -> {
                                mismatchCount++
                                if (mismatchCount >= 5) {
                                    handleCriticalError("IP Mismatch (IP утерян). Попробуйте переподключиться.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("connection refused", true) || lineTrim.contains("timeout", true) -> {
                                // Огромный лимит, потому что каждый воркер кидает эту ошибку при смене сети
                                refusedCount++
                                if (refusedCount >= 400) {
                                    handleCriticalError("Критическое отсутствие сети (400+ таймаутов). Отключение.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("9000") || lineTrim.contains("Call not found", true) -> {
                                currentHashErrorCount++
                                // Нужно больше попыток, так как 1 воркер может спамить
                                if (currentHashErrorCount >= 10) {
                                    handleHashError()
                                    return@forEachLine
                                }
                            }
                        }
                    }

                    // 1. Статистика (Обновляемая строка)
                    if (lineTrim.contains("[СТАТИСТИКА]")) {
                        val msg = lineTrim.substringAfter("[СТАТИСТИКА]").trim()
                        stats.value = msg
                        lastStatsReceivedAtMs = now

                        val match = Regex("Активных:\\s*(\\d+)").find(msg)
                        if (match != null) {
                            val active = match.groupValues[1].toIntOrNull() ?: 0
                            activeWorkers.value = active
                            if (active > 0) {
                                lastActiveAtMs = now
                                wrapAuthTimeoutCount = 0
                                if (connectionPipeline.value.failed == null) {
                                    finishConnectionPipeline()
                                }
                            }
                        }

                        // Парсинг и инкрементальное сохранение трафика для активного профиля
                        val matchTraffic = Regex("Трафик:\\s*([\\d.,]+)").find(msg)
                        val currentTraffic = matchTraffic?.groupValues?.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
                        if (currentTraffic != null) {
                            lastSessionTrafficMb = currentTraffic
                            val profId = activeProfileId
                            if (profId.isNotEmpty()) {
                                val diff = currentTraffic - lastSavedTrafficMb
                                if (diff >= 1.0) { // Каждые 1 МБ трафика
                                    val toSave = diff
                                    scope.launch {
                                        try {
                                            val ctx = lastContext?.get() ?: return@launch
                                            ProfilesStore(ctx).incrementProfileTraffic(profId, toSave)
                                        } catch (_: Exception) {}
                                    }
                                    lastSavedTrafficMb = currentTraffic
                                }
                            }
                        }

                        val downlinkMb = Regex("↓\\s*([\\d.,]+)").find(msg)
                            ?.groupValues?.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
                        val uplinkMb = Regex("↑\\s*([\\d.,]+)").find(msg)
                            ?.groupValues?.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
                        if (downlinkMb != null && uplinkMb != null) {
                            if (downlinkMb > lastDownlinkMb) lastDownlinkChangedAtMs = now
                            if (uplinkMb > lastUplinkMb) lastUplinkChangedAtMs = now
                            lastDownlinkMb = downlinkMb
                            lastUplinkMb = uplinkMb
                        }

                        updateLog("stats", "[СТАТИСТИКА] $msg", 3, false)
                        return@forEachLine
                    }

                    // 2. Этапы подключения и Ошибки
                    when {

                        // ═══ Авто-оркестратор капчи ═══
                        lineTrim.contains("[КАПЧА] AUTO:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] AUTO:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val isErr = text.contains("ошибка", true) ||
                                text.contains("timeout", true) ||
                                text.contains("не решил", true)
                            val stableKey = when {
                                text.contains("старт") -> "captcha_auto_1"
                                text.contains("Go v2") && text.contains("2 попыт") -> "captcha_auto_2"
                                text.contains("WBV Auto попытка") -> "captcha_auto_3"
                                text.contains("финальная") -> "captcha_auto_4"
                                text.contains("ручной WebView") -> "captcha_auto_5"
                                text.contains("решил") || text.contains("решила") -> "captcha_auto_done"
                                else -> "captcha_auto_${text.take(18).hashCode()}"
                            }
                            updateLog(stableKey, "[КАПЧА AUTO] $text", 5, isErr)
                        }

                        // ═══ RJS капча логи: [КАПЧА RJS] со стабильными ключами-шагами ═══
                        lineTrim.contains("[КАПЧА] RJS:") -> {
                            // Удаляем тайминги и лишние скобки: (123мс), (diff=2), (общее время...)
                            var text = lineTrim.substringAfter("[КАПЧА] RJS:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val stableKey = when {
                                text.contains("Загрузка") || text.contains("fetch") -> "captcha_rjs_1"
                                text.contains("PoW") -> "captcha_rjs_2"
                                text.contains("осматривает") || text.contains("человек") -> "captcha_rjs_3"
                                text.contains("captchaNotRobot") || text.contains("Отправка") -> "captcha_rjs_4"
                                text.contains("endSession") -> "captcha_rjs_5"
                                text.contains("решена") -> "captcha_rjs_6"
                                else -> "captcha_rjs_${text.take(15).hashCode()}"
                            }
                            updateLog(stableKey, "[КАПЧА RJS] $text", 5, false)
                        }

                        // ═══ WV капча логи от Go: [КАПЧА WBV] со стабильными ключами ═══
                        lineTrim.contains("[КАПЧА] WBV:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] WBV:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val isErr = text.contains("Ошибка")
                            val stableKey = when {
                                text.contains("Запрос") -> "captcha_wv_step_2" // Step 2 (после создания WV)
                                text.contains("Токен") -> "captcha_wv_step_5"  // Step 5 (перед уничтожением)
                                isErr -> "captcha_wv_err"
                                else -> "captcha_wv_go_other"
                            }
                            updateLog(stableKey, "[КАПЧА WBV] $text", 5, isErr)
                        }

                        lineTrim.contains("Старт") || lineTrim.contains("Ожидайте") -> {
                            updateLog("creds_start", "[ВК] Получение учетных данных...", 2, false)
                            setConnectionPipelineCurrent(ConnectionStep.VK)
                        }
                        lineTrim.contains("Креды получены") ->
                            updateLog("creds_lifetime", lineTrim, 2, false)
                        lineTrim.contains("Креды OK") || lineTrim.contains("Первые креды") -> {
                            updateLog("creds_ok", "[ВК] Учетные данные проверены ✓", 2, false)
                            advanceConnectionPipeline(ConnectionStep.VK, ConnectionStep.WRAP)
                        }
                        lineTrim.contains("Решаю VK Smart Captcha") -> {
                            updateLog("captcha_start", "[КАПЧА] Решение капчи...", 5, false)
                            markConnectionPipelineCaptchaRequired()
                        }
                        lineTrim.contains("Smart Captcha решена") -> {
                            updateLog("captcha_done", "[КАПЧА] Капча решена ✓", 5, false)
                            advanceConnectionPipeline(ConnectionStep.CAPTCHA, ConnectionStep.WRAP)
                        }
                        lineTrim.contains("капча не решена") || lineTrim.contains("ошибка решения капчи") -> {
                            updateLog("captcha_failed", "[КАПЧА] Ошибка решения капчи", 5, true)
                            failConnectionPipeline(ConnectionStep.CAPTCHA)
                        }
                        lineTrim.contains("DNS для VK:") -> {
                            // Не дублируем выбор DNS в ленте — достаточно precheck OK/fail.
                        }
                        lineTrim.contains("[WRAP]") -> {
                            val text = lineTrim.substringAfter("[WRAP]").trim()
                            updateLog("wrap_status", "[WRAP] $text", 1, false)
                            markConnectionPipelineCompleted(ConnectionStep.WRAP)
                            if (connectionPipeline.value.current?.order == ConnectionStep.WRAP.order) {
                                setConnectionPipelineCurrent(ConnectionStep.TURN)
                            }
                        }
                        lineTrim.contains("[TURN]") -> {
                            val text = lineTrim.substringAfter("[TURN]").trim()
                            val turnError = text.contains("Ошибка", true) ||
                                text.contains("не удалось", true) ||
                                text.contains("неполный ответ", true)
                            updateLog("turn_${text.take(32).hashCode()}", "[TURN] $text", 2, turnError)
                            if (turnError) {
                                failConnectionPipeline(ConnectionStep.TURN)
                            } else {
                                markConnectionPipelineCompleted(ConnectionStep.TURN)
                                if ((connectionPipeline.value.current?.order ?: 0) <= ConnectionStep.TURN.order) {
                                    setConnectionPipelineCurrent(ConnectionStep.TURN)
                                }
                            }
                        }
                        lineTrim.contains("Relay:") -> {
                            advanceConnectionPipeline(ConnectionStep.TURN, ConnectionStep.DTLS)
                        }
                        lineTrim.contains("[ПРЯМОЙ]") -> {
                            // Raw/no-DTLS: RTP-obfs AEAD напрямую поверх TURN, без DTLS вообще —
                            // шаг DTLS в пайплайне мгновенно завершается, а не показывает хендшейк,
                            // которого в этом режиме реально нет (см. session.go, ветка NoDTLS/RawMode).
                            advanceConnectionPipeline(ConnectionStep.DTLS, ConnectionStep.WORKERS)
                        }
                        lineTrim.contains("[DTLS] Рукопожатие") -> {
                            updateLog("dtls_start", "[DTLS] Рукопожатие (Handshake)...", 1, false)
                        }
                        lineTrim.contains("DTLS ОК") -> {
                            updateLog("dtls_ok", "[DTLS] Соединение установлено ✓", 1, false)
                            advanceConnectionPipeline(ConnectionStep.DTLS, ConnectionStep.WORKERS)
                        }
                        lineTrim.contains("[READY]") -> {
                            advanceConnectionPipeline(ConnectionStep.WORKERS, transportPipelineStep())
                        }
                        
                        // Ошибки (в конец). [RAW-DIAG] строки исключены: это диагностика
                        // локальной fd-передачи (recvTunFD и т.п.), не сеть до сервера — их
                        // текст ("connection refused" к unix-сокету go_client<->Android,
                        // "FAILED") ложно триггерил connectionErrorHint() с подсказкой
                        // "Сервер отклонил подключение", хотя сервер тут вообще не при чём.
                        isError && !lineTrim.contains("[RAW-DIAG") -> {
                            val pipeParts = lineTrim.split(" | ", limit = 2)
                            val mainLine = pipeParts[0]
                            val goHint = pipeParts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                            // Формируем уникальный ключ ошибки на основе её типа (группируем по типу ошибки)
                            val errorKey = when {
                                mainLine.contains("lookup login.vk.ru", true) -> "err_vk_dns"
                                mainLine.contains("connection refused", true) -> "err_conn_refused"
                                mainLine.contains("timeout", true) || mainLine.contains("context canceled", true) -> "err_timeout"
                                mainLine.contains("кредов", true) -> "err_creds"
                                mainLine.contains("DTLS", true) -> "err_dtls"
                                mainLine.contains("[TURN]", true) -> "err_turn"
                                mainLine.contains("[ВОРКЕР", true) -> "err_worker"
                                else -> "general_error_" + mainLine.take(15).hashCode()
                            }
                            val errorMessage = when (errorKey) {
                                "err_vk_dns" ->
                                    "[СЕТЬ] DNS до VK недоступен: login.vk.ru — смените DNS в ⚙️ → Сеть"
                                "err_dtls", "err_worker" -> shortenWorkerError(mainLine)
                                else -> mainLine
                            }
                            updateLog(errorKey, errorMessage, 99, true)
                            val hint = goHint ?: connectionErrorHint(mainLine)
                            if (hint != null) {
                                updateLog("${errorKey}_hint", "[ПОДСКАЗКА] $hint", 99, true)
                            }
                            if (errorKey == "err_vk_dns") {
                                failConnectionPipeline(ConnectionStep.DNS)
                                updateLog(
                                    "go_dns_tip",
                                    "[СЕТЬ] Откройте ⚙️ → Сеть и выберите другой DNS (Яндекс / Cloudflare / Google / Свой)",
                                    99,
                                    true
                                )
                            } else if (errorKey == "err_dtls" || errorKey == "err_worker" || errorKey == "err_timeout") {
                                failConnectionPipeline(ConnectionStep.DTLS)
                            }
                        }
                    }

                    // 3. Обработка конфига (Скрываем от пользователя)
                    if (line.contains("╔") && line.contains("WireGuard")) {
                        collectingConfig = true
                        configBuilder.clear()
                        setConnectionPipelineCurrent(transportPipelineStep())
                        return@forEachLine
                    } else if (line.contains("╔") && line.contains("RAW Конфиг")) {
                        collectingRawConfig = true
                        rawConfigBuilder.clear()
                        setConnectionPipelineCurrent(transportPipelineStep())
                        return@forEachLine
                    } else if (collectingRawConfig) {
                        if (line.contains("╚")) {
                            collectingRawConfig = false
                            val raw = rawConfigBuilder.toString().trim()
                            // Строки вида "IP = 10.70.x.y", "DNS = ...", "MTU = ..."
                            val fields = raw.lines().associate { l ->
                                val (k, v) = l.split("=", limit = 2).map { it.trim() }.let {
                                    (it.getOrElse(0) { "" }) to (it.getOrElse(1) { "" })
                                }
                                k to v
                            }
                            val ip = fields["IP"].orEmpty()
                            val dnsCsv = fields["DNS"].orEmpty()
                            val mtu = fields["MTU"]?.toIntOrNull() ?: 1350
                            val rawParams = currentParams
                            val rawCtx = lastContext?.get()
                            if (ip.isEmpty() || rawCtx == null) {
                                failConnectionPipeline(ConnectionStep.RAW)
                                updateLog("rawtun_conf_error", "[RAW] Некорректный конфиг от сервера или нет контекста", 99, true)
                            } else {
                                scope.launch(Dispatchers.Main) {
                                    try {
                                        RawTunEngine.start(rawCtx, ip, dnsCsv, mtu, rawTunSockName)
                                        markConnectionPipelineCompleted(ConnectionStep.RAW)
                                        finishConnectionPipeline()
                                        stats.value = "RAW $ip"
                                    } catch (e: Exception) {
                                        failConnectionPipeline(ConnectionStep.RAW)
                                        val msg = "Ошибка запуска raw-VPN: ${e.readableMessage()}"
                                        updateLog("rawtun_start_error", msg, 99, true)
                                        lastFatalError.value = msg
                                    }
                                }
                            }
                        } else if (line.contains("║")) {
                            val content = line.replace("║", "").trim()
                            if (content.isNotEmpty()) {
                                rawConfigBuilder.appendLine(content)
                            }
                        }
                        return@forEachLine
                    } else if (lineTrim.contains("[SOCKS] listening")) {
                        val isRaw = currentParams?.isRawTunMode == true
                        updateLog(
                            "socks_ready",
                            "[SOCKS] listening ${currentParams?.socksListenAddress ?: lineTrim.substringAfter("listening").trim()}",
                            1,
                            false
                        )
                        // Raw использует тот же локальный Go-listener (userspace WG/
                        // SOCKS-обвязка), что и настоящий SOCKS5-режим, но это не
                        // финальный шаг для Raw — дальше идёт RAW-конфиг (см. блок
                        // collectingRawConfig выше), поэтому здесь его не завершаем.
                        if (!isRaw) {
                            markConnectionPipelineCompleted(ConnectionStep.SOCKS)
                            finishConnectionPipeline()
                        }
                        stats.value = if (isRaw) {
                            "Ожидание данных..."
                        } else {
                            currentParams?.socksListenAddress?.let { "SOCKS $it" } ?: "SOCKS активен"
                        }
                        return@forEachLine
                    } else if (lineTrim.contains("[SOCKS] Ошибка") || lineTrim.contains("[SOCKS] Сервер остановлен")) {
                        failConnectionPipeline(transportPipelineStep())
                        updateLog("socks_error", lineTrim, 99, true)
                        return@forEachLine
                    } else if (collectingConfig) {
                        if (line.contains("╚")) {
                            collectingConfig = false
                            val configStr = configBuilder.toString().trim()
                            config.value = configStr

                            if (currentParams?.isSocksMode == true) {
                                // Userspace WG + SOCKS поднимает Go; Android VPN не трогаем.
                                updateLog("socks_wg_conf", "[SOCKS] Конфиг получен, ждём локальный прокси…", 1, false)
                            } else {
                                scope.launch(Dispatchers.Main) {
                                    try {
                                        val keepExisting = keepWireGuardOnNextConfig &&
                                            preservedWireGuardConfig == configStr &&
                                            wgHelper?.watchdogState() == WireGuardHelper.WatchdogState.UP
                                        keepWireGuardOnNextConfig = false
                                        preservedWireGuardConfig = null
                                        if (!keepExisting) {
                                            wgHelper?.startTunnel(configStr)
                                        } else {
                                            updateLog("network_wg_kept", "[VPN] WireGuard-интерфейс сохранён при перезапуске транспорта", 1, false)
                                        }
                                        markConnectionPipelineCompleted(ConnectionStep.VPN)
                                        finishConnectionPipeline()
                                    } catch (e: Exception) {
                                        failConnectionPipeline(ConnectionStep.VPN)
                                        val msg = "Ошибка запуска VPN: ${e.readableMessage()}"
                                        updateLog("vpn_start_error", msg, 99, true)
                                        lastFatalError.value = msg
                                    }
                                }
                            }
                        } else if (line.contains("║")) {
                            val content = line.replace("║", "").trim()
                            if (content.isNotEmpty()) {
                                configBuilder.appendLine(content)
                            }
                        }
                        return@forEachLine
                    } else if (lineTrim.isNotEmpty() && !lineTrim.contains("ВОРКЕР") && !lineTrim.contains("ПИНГ") && !lineTrim.contains("Байт/сек")) {
                        // Если строка вообще ни подо что не подошла (например, panic или linker error)
                        // [RAW-DIAG] из go_client (см. rawDiagf в go_client/raw_diag.go) — в обычном
                        // (не подробном) режиме в UI-лог НЕ идут, только в logcat (см. ниже), иначе
                        // экран логов забивается десятками технических строк на каждое подключение.
                        // Реальный "немой зависон" всё равно будет виден: recvTunFD/readLoop таймауты
                        // в итоге приводят к isError-строкам (FATAL_AUTH, таймауты пайплайна и т.п.),
                        // которые показываются всегда независимо от режима.
                        val isRawDiag = lineTrim.contains("[RAW-DIAG")
                        if (isRawDiag) {
                            // go_client's stdout не идёт через android.util.Log сам по
                            // себе — без явного вызова здесь эти строки были бы видны
                            // только в UI-логе приложения, но не в реальном logcat,
                            // который тянет кнопка "Поделиться" на экране логов.
                            if (isError) android.util.Log.w("Go", lineTrim) else android.util.Log.i("Go", lineTrim)
                        }
                        if (isDetailedLogsEnabled || isError) {
                            updateLog("go_unhandled_${lineTrim.hashCode()}", "[Go] $lineTrim", 3, isError)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!transportRestartInProgress) {
                    updateLog("sys_error", "Процесс остановлен: ${e.message}", -1, true)
                }
            } finally {
                // Если процесс умер сам, ловим код выхода
                try {
                    val exitCode = observedProcess.exitValue()
                    if (exitCode != 0 && !transportRestartInProgress) {
                        updateLog("sys_exit", "Процесс крашнулся с кодом $exitCode", 99, true)
                    }
                } catch (_: IllegalThreadStateException) {
                    if (!transportRestartInProgress) {
                        observedProcess.destroy()
                    }
                }
                if (process === observedProcess) {
                    process = null
                    if (!transportRestartInProgress) {
                        markRunning(false)
                    }
                }
            }
        }
    }

    private fun handleCriticalError(message: String) {
        updateLog("circuit_breaker", "[СТОП] $message", -1, true)
        stop()
    }

    private fun handleHashError() {
        val params = currentParams ?: return
        val context = lastContext?.get() ?: return

        currentHashErrorCount = 0
        forceRegenerateUA = true // Перегенерируем UA при следующих ошибках

        if (params.secondaryVkHash.isNotEmpty() && activeHashIndex == 0) {
            updateLog("hash_switch", "Основной хеш мертв. Переключение на запасной...", 50, true)
            activeHashIndex = 1
            stopOnlyProcess()
            start(context, params, isSwitching = true)
        } else {
            val msg = if (activeHashIndex == 1) "Запасной хеш тоже мертв. Отключение." else "Хеш умер, запасного нет. Отключение."
            handleCriticalError(msg)
        }
    }

    // ==================== WATCHDOG ====================
    // Проверяет, жив ли Go-процесс. Если умер — перезапускает.
    // Если процесс жив, но 0 воркеров уже 30 сек — тоже перезапуск (зомби).
    private fun startWatchdog(context: Context, params: TunnelParams) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var zeroWorkersSince = 0L
            delay(10_000) // Даём 10 сек на старт
            while (isActive && running.value) {
                val proc = process
                if (proc == null || !proc.isAlive) {
                    // Go-процесс мёртв! применяем экспоненциальный бэкофф перед перезапуском
                    val backoffMs = min(maxRestartBackoffSec * 1000L, (1000.0 * 2.0.pow(restartAttempts.toDouble())).toLong())
                    updateLog("watchdog", "⚠ Процесс упал. Перезапуск... (попытка ${restartAttempts + 1}, задержка ${backoffMs / 1000}s)", 50, true)
                    activeWorkers.value = 0
                    forceRegenerateUA = true
                    delay(backoffMs)
                    if (running.value) {
                        restartAttempts = (restartAttempts + 1).coerceAtMost(6)
                        reconnectAll("процесс упал")
                    }
                    return@launch // startWatchdog будет перезапущен из start()
                }

                // Детекция зомби: процесс жив, но 0 воркеров
                val workers = activeWorkers.value
                if (workers <= 0) {
                    if (zeroWorkersSince == 0L) {
                        zeroWorkersSince = System.currentTimeMillis()
                    } else if (
                        wrapAuthTimeoutCount >= 3 &&
                        processStartedAtMs > 0L &&
                        System.currentTimeMillis() - processStartedAtMs > 30_000 &&
                        lastActiveAtMs == 0L &&
                        !ManlCaptchaWebViewManager.isCaptchaPending
                    ) {
                        handleCriticalError("\uD83D\uDD12 Неверный пароль подключения или несовместимый WRAP. Воркеры остановлены.")
                        return@launch
                    } else if (System.currentTimeMillis() - zeroWorkersSince > 90_000 && !ManlCaptchaWebViewManager.isCaptchaPending) {
                        updateLog("watchdog", "⚠ Зомби-процесс (0 воркеров 90с). Перезапуск...", 50, true)
                        forceRegenerateUA = true
                        reconnectAll("зомби-процесс")
                        return@launch
                    }
                } else {
                    zeroWorkersSince = 0L
                    // Успешная активность — сбрасываем счётчик попыток рестарта
                    restartAttempts = 0

                    val now = System.currentTimeMillis()
                    if (
                        processStartedAtMs > 0L &&
                        now - processStartedAtMs > HEALTH_CHECK_GRACE_MS &&
                        lastStatsReceivedAtMs > 0L &&
                        now - lastStatsReceivedAtMs > STALE_STATS_MS &&
                        !ManlCaptchaWebViewManager.isCaptchaPending
                    ) {
                        updateLog(
                            "health_stale",
                            "⚠ Нет статистики от воркеров ${STALE_STATS_MS / 1000}с — переподключение...",
                            50,
                            true
                        )
                        reconnectAll("зависшее соединение")
                        return@launch
                    }
                }

                delay(5_000)
            }
        }
    }

    fun restartTransport(reason: String = "смена сети", force: Boolean = false) {
        reconnectAll(reason, force = force, preserveVpn = true)
    }

    fun isRawTunModeActive(): Boolean = currentParams?.isRawTunMode == true

    fun hasRecentTransportActivity(maxAgeMs: Long = 25_000L): Boolean {
        val now = System.currentTimeMillis()
        return running.value &&
            activeWorkers.value > 0 &&
            lastStatsReceivedAtMs > 0L &&
            now - lastStatsReceivedAtMs <= maxAgeMs
    }

    fun hasDownlinkTrafficSince(sinceMs: Long): Boolean = lastDownlinkChangedAtMs >= sinceMs

    fun hasHealthyRestartSince(sinceMs: Long, unansweredGraceMs: Long = 10_000L): Boolean {
        val now = System.currentTimeMillis()
        val transportReady = running.value &&
            activeWorkers.value > 0 &&
            lastActiveAtMs >= sinceMs &&
            lastStatsReceivedAtMs >= sinceMs
        if (!transportReady) return false
        val unansweredTraffic = lastUplinkChangedAtMs >= sinceMs &&
            lastDownlinkChangedAtMs < lastUplinkChangedAtMs &&
            now - lastUplinkChangedAtMs >= unansweredGraceMs
        return !unansweredTraffic
    }

    fun reconnectAll(reason: String, force: Boolean = false, preserveVpn: Boolean = false) {
        val params = currentParams ?: return
        val context = lastContext?.get() ?: return

        scope.launch {
            reconnectMutex.withLock {
                val now = System.currentTimeMillis()
                if (!force && now - lastReconnectAtMs < MIN_RECONNECT_INTERVAL_MS) {
                    updateLog("reconnect_skip", "Переподключение уже выполняется…", 50, false)
                    return@launch
                }
                lastReconnectAtMs = now

                isReconnecting.value = true
                transportRestartInProgress = true
                preserveVpnDuringReconnect = preserveVpn
                updateLog("reconnect", "🔄 Переподключение ($reason)...", 50, false)
                try {
                    val previousWireGuardConfig = config.value?.trim()
                    keepWireGuardOnNextConfig = false
                    preservedWireGuardConfig = null
                    withContext(Dispatchers.IO) {
                        ensureTransportStopped(params.port)
                        if (params.isRawTunMode) {
                            if (preserveVpn) {
                                RawTunEngine.prepareForTransportRestart()
                            } else {
                                RawTunEngine.prepareForReconnect()
                            }
                        } else if (!params.isSocksMode) {
                            if (preserveVpn) {
                                keepWireGuardOnNextConfig = true
                                preservedWireGuardConfig = previousWireGuardConfig
                            } else {
                                keepWireGuardOnNextConfig = false
                                preservedWireGuardConfig = null
                                wgHelper?.stopTunnel()
                            }
                        }
                    }
                    activeWorkers.value = 0
                    processStartedAtMs = 0L
                    lastActiveAtMs = 0L
                    lastStatsReceivedAtMs = 0L
                    resetTransportTrafficHealth()
                    markRunning(false)
                    config.value = null
                    start(context, params, isSwitching = true)
                    startJob?.join()
                    if (currentParams == null || process == null) return@withLock
                    if (params.isRawTunMode) {
                        awaitRawTunReady()
                    } else if (!params.isSocksMode) {
                        awaitWireGuardReady()
                    }
                } catch (e: CancellationException) {
                    transportRestartInProgress = false
                    throw e
                } catch (e: Exception) {
                    handleSwitchingStartFailed(e.message ?: e::class.java.simpleName)
                } finally {
                    preserveVpnDuringReconnect = false
                    transportRestartInProgress = false
                    isReconnecting.value = false
                }
            }
        }
    }

    fun pause() {
        if (!running.value) return
        killProcess() // Не ставим running=false, чтоб сервис не умер
        activeWorkers.value = 0
    }

    fun resume() {
        restartTransport("сеть появилась", force = true)
    }

    private fun resetTransportTrafficHealth() {
        lastUplinkMb = 0.0
        lastDownlinkMb = 0.0
        lastUplinkChangedAtMs = 0L
        lastDownlinkChangedAtMs = 0L
    }

    private suspend fun awaitRawTunReady(timeoutMs: Long = 20_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (RawTunEngine.running) return
            val proc = process
            if (proc == null || !proc.isAlive) {
                error("RAW: go_client завершился до подъёма TUN")
            }
            delay(100)
        }
        error("RAW TUN не поднялся за ${timeoutMs / 1000} с")
    }

    private suspend fun awaitWireGuardReady(timeoutMs: Long = 20_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (wgHelper?.watchdogState()) {
                WireGuardHelper.WatchdogState.UP,
                WireGuardHelper.WatchdogState.DISABLED_BY_EMPTY_WHITELIST -> return
                else -> Unit
            }
            val proc = process
            if (proc == null || !proc.isAlive) {
                error("WireGuard: go_client завершился до подъёма VPN")
            }
            delay(100)
        }
        error("WireGuard VPN не поднялся за ${timeoutMs / 1000} с")
    }

    // Убивает процесс без изменения running
    private fun killProcess() {
        watchdogJob?.cancel()
        readerJob?.cancel()
        stopGoProcessGracefully()
    }

    private fun stopGoProcessGracefully() {
        val proc = process
        process = null
        if (proc == null) return
        try {
            proc.outputStream.write("STOP\n".toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        } catch (_: Exception) {
        }
        try {
            proc.waitFor(400, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        if (proc.isAlive) {
            try {
                proc.destroy()
            } catch (_: Exception) {
            }
            try {
                proc.waitFor(800, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
        }
        if (proc.isAlive) {
            try {
                proc.destroyForcibly()
            } catch (_: Exception) {
            }
            try {
                proc.waitFor(1500, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
        }
    }

    private fun canBindUdpPort(port: Int): Boolean {
        return try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", port))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun waitForUdpPortFree(port: Int, timeoutMs: Long = 6000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (canBindUdpPort(port)) return
            delay(100)
        }
        updateLog(
            "port_wait_warn",
            "Порт $port занят дольше обычного, пробуем запуск…",
            50,
            true
        )
    }

    private suspend fun ensureTransportStopped(port: Int) {
        killProcess()
        waitForUdpPortFree(port)
    }

    private fun saveRemainingTraffic() {
        val id = activeProfileId
        val total = lastSessionTrafficMb
        val saved = lastSavedTrafficMb
        val diff = total - saved
        val context = lastContext?.get()
        if (id.isNotEmpty() && diff > 0.0 && context != null) {
            val appContext = context.applicationContext
            scope.launch {
                try {
                    ProfilesStore(appContext).incrementProfileTraffic(id, diff)
                } catch (_: Exception) {}
            }
        }
        activeProfileId = ""
        lastSavedTrafficMb = 0.0
        lastSessionTrafficMb = 0.0
    }

    private fun stopOnlyProcess() {
        saveRemainingTraffic()
        killProcess()
        markRunning(false)
        isConnecting.value = false
    }

    private fun log(message: String) {
        updateLog("internal_${message.hashCode()}", message, 50, false)
    }

    fun stop(force: Boolean = false) {
        if (!force && isConnecting.value && !running.value) {
            val age = System.currentTimeMillis() - connectingStartedAtMs
            if (age in 0 until CONNECT_STOP_GRACE_MS) {
                android.util.Log.w("WDTT", "Ignoring STOP during connect grace (${age}ms)")
                return
            }
        }
        saveRemainingTraffic()
        startJob?.cancel()
        startJob = null
        try {
            VkAuthWebViewManager.notifyCancelled()
        } catch (_: Exception) {
        }
        // Мгновенно отражаем "выключено" в UI — кнопка не должна казаться
        // зависшей, пока где-то внутри backend.setState()/RawTunEngine.stop()
        // или stopGoProcessGracefully() (до 2.7с синхронных waitFor на
        // SIGTERM/SIGKILL, если Go-процесс не отвечает на STOP) идёт
        // медленный (или подвисший) системный вызов. Реальный teardown уходит
        // в фоновую корутину (stopJob); следующий start() дожидается именно
        // stopJob, а не блокирует вызывающий (обычно главный) поток.
        watchdogJob?.cancel()
        readerJob?.cancel()
        markRunning(false)
        isConnecting.value = false
        activeWorkers.value = 0
        // При ошибке шага оставляем схему с крестиком; иначе прячем.
        if (connectionPipeline.value.failed == null) {
            hideConnectionPipeline()
        } else {
            cancelPipelineStepTimeout()
        }
        ManlCaptchaWebViewManager.cancelCaptcha()

        val paramsSnapshot = currentParams
        val wgHelperSnapshot = wgHelper
        currentParams = null
        stopJob = scope.launch(Dispatchers.IO) {
            runCatching { stopGoProcessGracefully() }
            if (paramsSnapshot?.isRawTunMode == true) {
                val ctx = lastContext?.get()
                if (ctx != null) {
                    runCatching { RawTunEngine.stop(ctx) }
                }
            }
            runCatching { wgHelperSnapshot?.stopTunnel() }
        }
    }

    fun reloadWireGuard() {
        if (running.value && currentParams?.isSocksMode != true) {
            scope.launch {
                wgHelper?.reloadTunnel()
            }
        }
    }

    // ==================== CAPTCHA SOLVER (WebView Mode) ====================

    /**
     * Вызывается при получении CAPTCHA_SOLVE от Go-процесса.
     * auto: одна короткая скрытая попытка для Go-оркестратора.
     * manual: сразу видимый WebView.
     * selected: старое поведение из UI, когда пользователь сам выбрал режим.
     * Результат ВСЕГДА отправляется обратно в Go через writeCaptchaResult.
     */
    private suspend fun handleCaptchaSolve(requestMode: String, redirectUri: String, sessionToken: String) {
        val ctx = lastContext?.get() ?: run {
            writeCaptchaResult("error:context is null")
            return
        }
        val mode = requestMode.lowercase()

        try {
            if (mode == "manual") {
                VkWebViewCookies.clearCaptchaCookies()
            }
            val token = when (mode) {
                "auto" -> solveSingleAutoWebViewCaptcha(redirectUri, sessionToken)
                "manual" -> {
                    updateLog("captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                    ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                else -> {
                    if (currentCaptchaSolveMethod == "auto") {
                        solveAutoWebViewCaptcha(ctx, redirectUri, sessionToken)
                    } else {
                        updateLog("captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                        ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                    }
                }
            }
            updateLog("captcha_wv_step_4", "[КАПЧА WBV] Капча решена ✓", 5, false)
            writeCaptchaResult(token)
        } catch (e: IllegalStateException) {
            val errorMsg = e.message ?: "WV state error"
            updateLog("captcha_wv_err", "[КАПЧА WBV] $errorMsg", 5, true)
            writeCaptchaResult("error:$errorMsg")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            updateLog("captcha_wv_err", "[КАПЧА WBV] Таймаут WebView", 5, true)
            writeCaptchaResult("error:timeout")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            updateLog("captcha_wv_err", "[КАПЧА WBV] Отменено", 5, true)
            writeCaptchaResult("error:cancelled")
        } catch (e: Exception) {
            val errorMsg = e.message ?: "${e::class.simpleName}"
            if (errorMsg != "tunnel stopped") {
                updateLog("captcha_wv_err", "[КАПЧА WBV] Ошибка — $errorMsg", 5, true)
            }
            writeCaptchaResult("error:$errorMsg")
        }

        // WebView уничтожен в finally блоке соответствующего менеджера.
        updateLog("captcha_wv_step_6", "[КАПЧА WBV] WebView уничтожен", 5, false)
    }

    private suspend fun solveSingleAutoWebViewCaptcha(
        redirectUri: String,
        sessionToken: String
    ): String {
        updateLog("captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка 10с...", 5, false)
        return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
            updateLog("captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
        }
    }

    private suspend fun solveAutoWebViewCaptcha(
        ctx: Context,
        redirectUri: String,
        sessionToken: String
    ): String {
        for (attempt in 1..2) {
            updateLog("captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка $attempt/2...", 5, false)
            try {
                return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
                    updateLog("captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                updateLog("captcha_wv_timeout_$attempt", "[КАПЧА WBV] Авто таймаут 10с ($attempt/2)", 5, attempt == 2)
                if (attempt == 2) {
                    updateLog("captcha_wv_fallback", "[КАПЧА WBV] 2 таймаута авто, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
            } catch (e: IllegalStateException) {
                if (e.message == CaptchaWebViewManager.ERROR_SLIDER_DETECTED) {
                    updateLog("captcha_wv_fallback", "[КАПЧА WBV] Обнаружен слайдер, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                throw e
            }
        }
        return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
    }

    /**
     * Записывает результат решения капчи в stdin Go-процесса.
     */
    private fun writeCaptchaResult(result: String) {
        val proc = process
        if (proc == null || !proc.isAlive) return
        try {
            val line = "CAPTCHA_RESULT|$result\n"
            proc.outputStream.write(line.toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        } catch (e: Exception) {
            updateLog("captcha_write_err", "[КАПЧА] Ошибка записи: ${e.message}", 200, true)
        }
    }

    private suspend fun handleVkAuthRequired(hash: String) {
        val ctx = lastContext?.get()
        if (ctx == null) {
            writeTurnCredsError()
            return
        }
        updateLog("vk_auth_refresh", "[VK Auth] Обновление TURN для ${hash.take(8)}…", 5, false)
        try {
            val result = VkAuthWebViewManager.authenticate(ctx, hash)
            val creds = result.getOrElse {
                writeTurnCredsError()
                updateLog("vk_auth_refresh_fail", "[VK Auth] Ошибка: ${it.message}", 99, true)
                return
            }
            writeTurnCreds(hash, creds)
            updateLog("vk_auth_refresh_ok", "[VK Auth] TURN обновлены", 5, false)
        } catch (e: Exception) {
            writeTurnCredsError()
            updateLog("vk_auth_refresh_fail", "VK auth: ${e.message}", 99, true)
        }
    }

    private fun writeTurnCreds(hash: String, creds: VkTurnCreds) {
        val proc = process
        if (proc == null || !proc.isAlive) return
        try {
            val line = VkAuthWebViewManager.encodeTurnCredsPayload(hash, creds)
            proc.outputStream.write(line.toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        } catch (e: Exception) {
            updateLog("vk_auth_write_err", "Ошибка записи TURN: ${e.message}", 200, true)
        }
    }

    private fun writeTurnCredsError() {
        val proc = process
        if (proc == null || !proc.isAlive) return
        try {
            proc.outputStream.write("TURN_CREDS|error:cancelled\n".toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        } catch (_: Exception) {
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
        activeWorkers.value = 0
    }

    fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        cooldownSeconds.value = seconds
        cooldownJob = scope.launch(Dispatchers.Main) {
            while (cooldownSeconds.value > 0) {
                delay(1000)
                cooldownSeconds.update { it - 1 }
            }
        }
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }

    private fun resetConnectionPipeline() {
        pipelineHideJob?.cancel()
        pipelineHideJob = null
        cancelPipelineStepTimeout()
        if (!isConnectionPipelineEnabled) {
            connectionPipeline.value = ConnectionPipelineState()
            return
        }
        connectionPipeline.value = ConnectionPipelineState(
            current = ConnectionStep.DNS,
            completed = emptySet(),
            visible = true,
            transportKind = when {
                currentParams?.isRawTunMode == true -> ConnectionTransportKind.RAW
                currentParams?.isSocksMode == true -> ConnectionTransportKind.SOCKS
                else -> ConnectionTransportKind.VPN
            },
            // Raw и Direct(noDtls) идут напрямую RTP-obfs AEAD, без DTLS —
            // шаг DTLS в схеме имеет смысл показывать только для
            // классического VPN-режима.
            dtlsUsed = currentParams?.let { !it.noDtls && !it.isRawTunMode } ?: true,
        )
        armPipelineStepTimeout(ConnectionStep.DNS)
    }

    private fun transportPipelineStep(): ConnectionStep = when {
        currentParams?.isRawTunMode == true -> ConnectionStep.RAW
        currentParams?.isSocksMode == true -> ConnectionStep.SOCKS
        else -> ConnectionStep.VPN
    }

    fun isSocksModeActive(): Boolean = currentParams?.isSocksMode == true

    fun activeSocksListenAddress(): String? =
        currentParams?.takeIf { it.isSocksMode && !it.isRawTunMode }?.socksListenAddress

    /**
     * Сводка текущего режима/настроек подключения без секретов (пароли, VK-
     * хеши, adminPassword и т.п. намеренно не включены) — для кнопки
     * "Поделиться" на экране логов. peer — это адрес сервера, не секрет.
     */
    fun connectionDiagnosticsSummary(): String {
        val p = currentParams ?: return "Нет активного/последнего подключения"
        val mode = when {
            p.isRawTunMode -> "Raw"
            p.isSocksMode -> "SOCKS5"
            else -> "VPN (WireGuard)"
        }
        return buildString {
            appendLine("Режим: $mode")
            appendLine("Сервер (peer): ${p.peer}")
            appendLine("Протокол: ${p.protocol}")
            appendLine("Воркеров на хеш: ${p.workersPerHash}")
            appendLine("Маскировка: ${p.obfsMode}")
            appendLine("VK auth: ${p.vkAuthMode} (${p.vkAnonPath})")
            appendLine("Captcha: ${p.captchaMode}/${p.captchaSolveMethod}")
            appendLine("DNS: ${p.goDnsArg}")
            appendLine("Без DTLS: ${p.noDtls}")
            appendLine("TURN по TCP: ${p.turnTcp}")
            append("Подробные логи: ${p.detailedLogs}")
        }
    }

    private fun hideConnectionPipeline() {
        pipelineHideJob?.cancel()
        pipelineHideJob = null
        cancelPipelineStepTimeout()
        connectionPipeline.value = ConnectionPipelineState()
    }

    private fun scheduleHideConnectionPipeline() {
        pipelineHideJob?.cancel()
        pipelineHideJob = scope.launch {
            delay(PIPELINE_HIDE_AFTER_SUCCESS_MS)
            val state = connectionPipeline.value
            if (state.visible && state.failed == null && state.current == ConnectionStep.DONE) {
                connectionPipeline.value = ConnectionPipelineState()
            }
        }
    }

    private fun cancelPipelineStepTimeout() {
        pipelineStepTimeoutJob?.cancel()
        pipelineStepTimeoutJob = null
    }

    private fun isAccountVkAuthActive(): Boolean =
        currentParams?.vkAuthMode?.equals("anonymous", ignoreCase = true) == false

    private fun pipelineTimeoutFor(step: ConnectionStep): Long = when (step) {
        ConnectionStep.VK -> PIPELINE_VK_STEP_TIMEOUT_MS
        ConnectionStep.RAW -> PIPELINE_RAW_STEP_TIMEOUT_MS
        else -> PIPELINE_STEP_TIMEOUT_MS
    }

    private fun armPipelineStepTimeout(step: ConnectionStep?) {
        cancelPipelineStepTimeout()
        if (step == null || step == ConnectionStep.DONE) return
        // Много потоков поднимаются постепенно; капча / вход по аккаунту VK ждут пользователя.
        if (step == ConnectionStep.WORKERS || step == ConnectionStep.CAPTCHA) return
        if (step == ConnectionStep.VK && isAccountVkAuthActive()) return

        val timeoutMs = pipelineTimeoutFor(step)
        pipelineStepTimeoutJob = scope.launch {
            delay(timeoutMs)
            val state = connectionPipeline.value
            if (!state.visible || state.failed != null || state.current != step) return@launch
            onPipelineStepTimeout(step, timeoutMs)
        }
    }

    private fun onPipelineStepTimeout(step: ConnectionStep, timeoutMs: Long = pipelineTimeoutFor(step)) {
        cancelPipelineStepTimeout()
        pipelineHideJob?.cancel()
        pipelineHideJob = null
        connectionPipeline.update { state ->
            if (!state.visible) {
                state
            } else {
                state.copy(failed = step, timedOut = true, current = step, timeoutSec = (timeoutMs / 1000L).toInt())
            }
        }
        updateLog(
            "pipeline_timeout",
            "[СХЕМА] Шаг «${step.label}» не завершился за ${timeoutMs / 1000} с — подключение остановлено",
            99,
            true
        )
        startJob?.cancel()
        startJob = null
        if (running.value || process != null) {
            stopRawTunIfNeeded()
            scope.launch(Dispatchers.Main) {
                wgHelper?.stopTunnel()
            }
            killProcess()
            markRunning(false)
            isConnecting.value = false
            activeWorkers.value = 0
            currentParams = null
            runCatching { ManlCaptchaWebViewManager.cancelCaptcha() }
        } else {
            finishConnectingFailed()
        }
    }

    private fun setConnectionPipelineCurrent(step: ConnectionStep) {
        connectionPipeline.update { state ->
            if (!state.visible) state else state.copy(current = step, failed = null, timedOut = false)
        }
        armPipelineStepTimeout(step)
    }

    private fun markConnectionPipelineCompleted(step: ConnectionStep) {
        connectionPipeline.update { state ->
            if (!state.visible) {
                state
            } else {
                state.copy(completed = state.completed + step, failed = null, timedOut = false)
            }
        }
    }

    private fun markConnectionPipelineCaptchaRequired() {
        connectionPipeline.update { state ->
            if (!state.visible) {
                state
            } else {
                state.copy(
                    captchaRequired = true,
                    current = ConnectionStep.CAPTCHA,
                    failed = null,
                    timedOut = false,
                )
            }
        }
        armPipelineStepTimeout(ConnectionStep.CAPTCHA)
    }

    private fun advanceConnectionPipeline(completed: ConnectionStep, next: ConnectionStep) {
        connectionPipeline.update { state ->
            if (!state.visible) {
                state
            } else {
                state.copy(
                    completed = state.completed + completed,
                    current = next,
                    failed = null,
                    timedOut = false,
                )
            }
        }
        armPipelineStepTimeout(next)
    }

    private fun failConnectionPipeline(step: ConnectionStep) {
        pipelineHideJob?.cancel()
        pipelineHideJob = null
        cancelPipelineStepTimeout()
        connectionPipeline.update { state ->
            if (!state.visible) state else state.copy(failed = step, timedOut = false)
        }
    }

    private fun finishConnectionPipeline() {
        var shouldScheduleHide = false
        connectionPipeline.update { state ->
            if (!state.visible) {
                state
            } else if (state.current == ConnectionStep.DONE && state.failed == null) {
                state
            } else {
                shouldScheduleHide = true
                val doneSteps = state.stepsToShow().toSet() + ConnectionStep.DONE
                state.copy(
                    current = ConnectionStep.DONE,
                    completed = doneSteps,
                    failed = null,
                    timedOut = false,
                )
            }
        }
        cancelPipelineStepTimeout()
        if (shouldScheduleHide) {
            scheduleHideConnectionPipeline()
        }
    }

    private fun wrapHandshakeWaitMessage(count: Int): String =
        "[WRAP] Handshake не подтвердился ($count). " +
            "Возможно: неверный пароль, сервер недоступен, UDP режет оператор или wdtt-server не запущен"

    private fun shortenWorkerError(line: String): String {
        val attempt = Regex("попытка\\s+(\\d+)", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.getOrNull(1)
        val worker = Regex("#(\\d+)").find(line)?.groupValues?.getOrNull(1)
        val prefix = buildString {
            append("[ВОРКЕР")
            if (worker != null) append(" #$worker")
            append("] ")
        }
        val lower = line.lowercase()
        val core = when {
            lower.contains("wrap_auth_timeout") || lower.contains("dtls timeout") ->
                "WRAP/DTLS не подтверждён"
            lower.contains("context canceled") ->
                "DTLS handshake прерван"
            lower.contains("connection refused") ->
                "сервер отклонил подключение"
            lower.contains("connection reset") ->
                "сервер сбросил соединение"
            lower.contains("timeout") || lower.contains("deadline") ->
                "таймаут DTLS handshake"
            lower.contains("turn квота") || lower.contains("quota") ->
                "исчерпана квота TURN"
            lower.contains("turn allocate") ->
                "ошибка TURN Allocate"
            lower.contains("[turn]") ->
                line.substringAfter("[TURN]", line).trim().take(96)
            else ->
                line.substringAfter(": ", line).take(96)
        }
        return buildString {
            append(prefix)
            append(core)
            if (attempt != null) append(" (попытка $attempt)")
        }
    }

    private fun isBenignSocksIpv6Noise(line: String): Boolean {
        val lower = line.lowercase()
        if (!lower.contains("socks")) return false
        if (lower.contains("blocked by rules")) return true
        if (lower.contains("ipv6 skipped") || lower.contains("ipv6")) return true
        // Connect to [2001:…] / :: … no route
        val looksIpv6 = line.contains("::") || (line.contains('[') && line.contains(']') && line.contains(':'))
        return looksIpv6 && (
            lower.contains("no route") ||
                lower.contains("failed to handle") ||
                lower.contains("connect to")
            )
    }

    private fun connectionErrorHint(line: String): String? {
        val lower = line.lowercase()
        if (isBenignSocksIpv6Noise(line)) return null
        return when {
            lower.contains("wrap_auth_timeout") || lower.contains("dtls timeout") ->
                "Сервер не ответил на WRAP/DTLS — проверьте пароль профиля, IP/порт VPS и что wdtt-server запущен"
            lower.contains("context canceled") ->
                "Соединение прервано до handshake — часто сервер недоступен, UDP режет оператор или сменилась сеть"
            lower.contains("connection refused") ->
                "Сервер отклонил подключение — проверьте IP, порт DTLS и что wdtt-server запущен на VPS"
            lower.contains("connection reset") ->
                "Сервер сбросил соединение — возможен неверный пароль WRAP или перезапуск wdtt-server"
            lower.contains("no route") || lower.contains("network is unreachable") ->
                "Нет маршрута до сервера — проверьте интернет; отключите другие VPN/прокси"
            lower.contains("lookup") || lower.contains("no such host") ->
                "DNS не резолвит адрес — смените DNS в ⚙️ → Сеть"
            lower.contains("turn квота") || lower.contains("quota") || lower.contains("486") ->
                "VK исчерпал TURN-слоты — уменьшите число потоков или смените VK-хеш"
            lower.contains("turn allocate") ->
                "Ошибка TURN relay — VK может резать UDP; попробуйте другой хеш или режим капчи"
            lower.contains("rate limit") || lower.contains("flood") || lower.contains("error 29") ->
                "VK временно ограничил запросы — подождите или смените IP/хеш"
            lower.contains("rtp aead") || lower.contains("auth failed") ->
                "Ошибка WRAP/RTP — неверный пароль или несовместимая версия сервера"
            lower.contains("timeout") || lower.contains("deadline") ->
                "Таймаут — сервер не отвечает, проверьте доступность VPS и пароль"
            else -> null
        }
    }
}

data class TunnelParams(
    val peer: String,
    val vkHashes: String,
    val secondaryVkHash: String = "",
    val workersPerHash: Int,
    val port: Int,
    val sni: String = "",
    val connectionPassword: String = "",
    val protocol: String = "udp",
    val captchaMode: String = "auto", // "auto", "wv" или "rjs"
    val captchaSolveMethod: String = "auto", // "manual" или "auto"
    val vkAuthMode: String = "anonymous", // "account" или "anonymous"
    val vkAnonPath: String = "vkcalls", // "vkcalls" или "legacy" (только anonymous)
    val goDnsArg: String = "yandex", // yandex/cloudflare/google, doh-*, custom:IP, doh:URL
    val obfsMode: String = "audio", // "audio" or "video"
    val connectionMode: String = SettingsStore.CONNECTION_MODE_VPN, // vpn | socks | box
    val socksPort: Int = SettingsStore.DEFAULT_SOCKS_PORT,
    val socksAuthEnabled: Boolean = false,
    val socksUsername: String = "",
    val socksPassword: String = "",
    /** Экспериментально: RTP-obfs AEAD без DTLS. Нужен сервер с -listen-direct, peer уже должен указывать на direct-порт. */
    val noDtls: Boolean = false,
    /** TURN-relay по TCP вместо UDP — обход UDP-душения на некоторых сетях (напр. Ростелеком). */
    val turnTcp: Boolean = false,
    val detailedLogs: Boolean = false
) {
    /** Go поднимает локальный SOCKS5 (userspace WG) вместо Android GoBackend — верно и для socks, и для rawtun. */
    val isSocksMode: Boolean
        get() = SettingsStore.normalizeConnectionMode(connectionMode) != SettingsStore.CONNECTION_MODE_VPN

    /** rawtun: сырые IP-пакеты напрямую в go_client через TUN-fd, без WireGuard вообще (ни Android, ни userspace). */
    val isRawTunMode: Boolean
        get() = SettingsStore.normalizeConnectionMode(connectionMode) == SettingsStore.CONNECTION_MODE_RAWTUN

    val socksListenAddress: String
        get() = SettingsStore.socksListenAddress(socksPort)
}
