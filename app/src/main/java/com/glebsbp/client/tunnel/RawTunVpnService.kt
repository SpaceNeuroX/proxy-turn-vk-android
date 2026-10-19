package com.glebsbp.client

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * VpnService для raw-IP режима (без WireGuard): TUN устанавливается сам и
 * отдаётся напрямую в go_client через [TunFdBridge].
 */
class RawTunVpnService : VpnService() {
    @Volatile
    private var tunFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Управляет TunnelManager; сервис — просто держатель VpnService.
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "Raw VPN revoked")
        TunnelManager.onRawTunRevoked()
        stopSelf()
    }

    override fun onDestroy() {
        closeTun()
        instance = null
        super.onDestroy()
    }

    /** Поднимает TUN с параметрами из RAWCONF. */
    fun establish(ip: String, dnsCsv: String, mtu: Int): ParcelFileDescriptor {
        diag("establish() start: ip=$ip mtu=$mtu dnsCsv=$dnsCsv")
        diag("Android: SDK=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")

        val prepareResult = prepare(this)
        if (prepareResult != null) {
            diag("VpnService.prepare() returned non-null intent — VPN permission NOT granted: $prepareResult", isError = true)
            error("android: missing vpn permission")
        }
        diag("VpnService.prepare() OK — permission already granted")

        val builder = Builder()
        runCatching {
            builder.setSession("GLEBSBP-raw")
            diag("Builder.setSession OK")
        }.onFailure { diag("Builder.setSession FAILED: ${it}", isError = true) }

        runCatching {
            builder.setMtu(mtu)
            diag("Builder.setMtu($mtu) OK")
        }.onFailure { diag("Builder.setMtu($mtu) FAILED: ${it}", isError = true) }

        runCatching {
            builder.addAddress(ip, 32)
            diag("Builder.addAddress($ip/32) OK")
        }.onFailure { diag("Builder.addAddress($ip/32) FAILED: ${it}", isError = true) }

        // setMetered() тоже НЕ вызывается — оставляем минимальный набор
        // Builder-вызовов.
        // allowBypass() НЕ вызывается — намеренно. Он разрешает системе/
        // приложениям обходить этот VPN через Socket.protect(), даже без
        // системного тумблера "Заблокировать соединения без VPN". С
        // allowBypass() на некоторых устройствах/прошивках реальный
        // browser-трафик мог полностью обходить туннель (IP клиента вместо
        // IP сервера), при этом addRoute(0.0.0.0/0) технически применялся
        // корректно.
        diag("Builder.allowBypass() НЕ вызывается (полный туннель без обхода)")

        val dnsServers = dnsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        diag("DNS servers to add: $dnsServers")
        for (dnsServer in dnsServers) {
            runCatching {
                builder.addDnsServer(dnsServer)
                diag("Builder.addDnsServer($dnsServer) OK")
            }.onFailure { diag("Builder.addDnsServer($dnsServer) FAILED: ${it}", isError = true) }
        }

        applyAppFilters(builder)
        applyBypassRoutes(builder)

        closeTun()
        diag("Calling Builder.establish()...")
        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            diag("Builder.establish() THREW: ${e.javaClass.simpleName}: ${e.message}", isError = true)
            throw e
        }
        if (pfd == null) {
            diag("Builder.establish() returned NULL — Android denied/failed VPN interface creation", isError = true)
            error("android: VpnService.Builder.establish() failed")
        }
        tunFd = pfd
        diag("Raw TUN established OK: fd=${pfd.fd} ip=$ip mtu=$mtu")
        Log.i(TAG, "Raw TUN established fd=${pfd.fd} ip=$ip mtu=$mtu")
        return pfd
    }

    private fun diag(message: String, isError: Boolean = false) {
        Log.i(TAG, message)
        runCatching { TunnelManager.addRawDiagLog(message, isError) }
    }

    /**
     * Обход/исключения сайтов: в отличие от WireGuard (AllowedIPs можно
     * поменять на лету без разрыва туннеля), здесь список читается только
     * при подъёме TUN — VpnService.Builder не поддерживает изменение
     * маршрутов уже установленного интерфейса. Если список меняют при
     * активном Raw-туннеле, применится только при следующем подключении.
     */
    private fun applyBypassRoutes(builder: Builder) {
        if (FORCE_FULL_TUNNEL) {
            diag("FORCE_FULL_TUNNEL: игнорирую bypass routes — 0.0.0.0/0")
            runCatching {
                builder.addRoute("0.0.0.0", 0)
                diag("Builder.addRoute(0.0.0.0/0) OK (FORCE_FULL_TUNNEL)")
            }.onFailure { diag("Builder.addRoute(0.0.0.0/0) FAILED: ${it}", isError = true) }
            return
        }
        val store = SettingsStore(applicationContext)
        val raw = runBlocking(Dispatchers.IO) { store.bypassRoutes.first() }
        diag("Bypass routes raw setting: ${if (raw.isBlank()) "(empty)" else raw}")
        if (raw.isBlank()) {
            runCatching {
                builder.addRoute("0.0.0.0", 0)
                diag("Builder.addRoute(0.0.0.0/0) OK (no bypass configured)")
            }.onFailure { diag("Builder.addRoute(0.0.0.0/0) FAILED: ${it}", isError = true) }
            return
        }
        val result = BypassRoutes.buildAllowedIps(raw, applicationContext)
        diag("BypassRoutes.buildAllowedIps: excludeCount=${result.excludeCount} truncated=${result.truncated} unresolved=${result.unresolved}")
        if (result.unresolved.isNotEmpty()) {
            Log.w(TAG, "Bypass unresolved: ${result.unresolved.joinToString()}")
        }
        if (result.truncated) {
            Log.w(TAG, "Bypass routes truncated (excludes=${result.excludeCount})")
        }
        var added = 0
        var failed = 0
        for (entry in result.allowedIps.split(',')) {
            val cidr = entry.trim()
            if (cidr.isEmpty()) continue
            val slash = cidr.indexOf('/')
            if (slash < 0) continue
            val address = cidr.substring(0, slash)
            val prefixLen = cidr.substring(slash + 1).toIntOrNull() ?: continue
            val addResult = runCatching { builder.addRoute(address, prefixLen) }
            if (addResult.isSuccess) {
                added++
            } else {
                failed++
                diag("Builder.addRoute($address/$prefixLen) FAILED: ${addResult.exceptionOrNull()}", isError = true)
            }
        }
        diag("Bypass routes applied: added=$added failed=$failed")
        if (added == 0) {
            // Полный список маршрутов не применился (например, весь IPv4 был
            // исключён или разбор не удался) — без этого TUN вообще не
            // маршрутизирует трафик, откатываемся на полный туннель.
            Log.w(TAG, "Bypass routes produced 0 usable routes, falling back to 0.0.0.0/0")
            diag("0 usable bypass routes — falling back to 0.0.0.0/0", isError = true)
            runCatching { builder.addRoute("0.0.0.0", 0) }
                .onFailure { diag("Fallback Builder.addRoute(0.0.0.0/0) FAILED: ${it}", isError = true) }
        } else {
            BypassRoutes.noteAppliedAllowedIps(result.allowedIps)
        }
    }

    private fun applyAppFilters(builder: Builder) {
        if (FORCE_FULL_TUNNEL) {
            diag("FORCE_FULL_TUNNEL: игнорирую фильтр приложений — весь трафик через туннель")
            val transport = setOf(packageName, "com.vkontakte.android", "com.vk.calls")
            for (pkg in transport) {
                if (isInstalled(pkg)) {
                    val r = runCatching { builder.addDisallowedApplication(pkg) }
                    if (r.isFailure) diag("addDisallowedApplication($pkg) FAILED: ${r.exceptionOrNull()}", isError = true)
                }
            }
            return
        }
        val store = SettingsStore(applicationContext)
        val (packages, isWhitelist) = runBlocking(Dispatchers.IO) {
            store.migrateLegacyWhitelistMode()
            val saved = store.excludedApps.first()
            val white = store.isWhitelist.first()
            saved to white
        }
        val userSelected = packages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val transport = setOf(
            packageName,
            "com.vkontakte.android",
            "com.vk.calls",
        )
        diag("App filters: isWhitelist=$isWhitelist userSelected=$userSelected transport=$transport")

        if (isWhitelist) {
            val installed = userSelected.filter { it !in transport && isInstalled(it) }
            diag("Whitelist mode: installed selected apps = $installed")
            if (installed.isEmpty()) {
                Log.w(TAG, "whitelist empty — excluding only transport packages")
                diag("Whitelist empty — excluding only transport packages (fail-open: full traffic will tunnel)", isError = true)
                for (pkg in transport) {
                    if (isInstalled(pkg)) {
                        val r = runCatching { builder.addDisallowedApplication(pkg) }
                        if (r.isFailure) diag("addDisallowedApplication($pkg) FAILED: ${r.exceptionOrNull()}", isError = true)
                    }
                }
            } else {
                for (pkg in installed) {
                    val r = runCatching { builder.addAllowedApplication(pkg) }
                    if (r.isFailure) diag("addAllowedApplication($pkg) FAILED: ${r.exceptionOrNull()}", isError = true)
                }
            }
        } else {
            for (pkg in transport + userSelected) {
                if (isInstalled(pkg)) {
                    val r = runCatching { builder.addDisallowedApplication(pkg) }
                    if (r.isFailure) diag("addDisallowedApplication($pkg) FAILED: ${r.exceptionOrNull()}", isError = true)
                }
            }
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun closeTun() {
        runCatching { tunFd?.close() }
        tunFd = null
    }

    fun currentTunFd(): ParcelFileDescriptor? = tunFd

    companion object {
        private const val TAG = "RawTunVpnService"

        @Volatile
        var instance: RawTunVpnService? = null
            private set
    }
}
