package com.wdtt.client

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    companion object {
        /** Макс. потоков при входе через аккаунт VK (лимит TURN relay на звонок). */
        const val VK_ACCOUNT_MAX_WORKERS = 4
        /** Потоков в одной группе Go-клиента (workersPerGroup). */
        const val WORKERS_PER_VK_GROUP = 9
        const val MAX_VK_HASHES = 4

        /** Макс. потоков: 27 на каждый хеш (1→27, 2→54, 3→81, 4→108). */
        fun maxAnonymousWorkers(hashCount: Int): Int {
            val n = hashCount.coerceIn(1, MAX_VK_HASHES)
            return n * WORKERS_PER_VK_GROUP * 3
        }

        private val Context.dataStore by preferencesDataStore("settings")
        private val PEER = stringPreferencesKey("peer")
        private val VK_HASHES = stringPreferencesKey("vk_hashes")
        private val GLOBAL_VK_HASHES = stringPreferencesKey("global_vk_hashes")
        private val SECONDARY_VK_HASH = stringPreferencesKey("secondary_vk_hash")
        private val WORKERS_PER_HASH = intPreferencesKey("workers_per_hash")
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val LISTEN_PORT = intPreferencesKey("listen_port")
        private val MANUAL_PORTS_ENABLED = booleanPreferencesKey("manual_ports_enabled")
        private val SERVER_DTLS_PORT = intPreferencesKey("server_dtls_port")
        private val SERVER_WG_PORT = intPreferencesKey("server_wg_port")
        /** Экспериментально: RTP-obfs AEAD без DTLS (на сервере нужен -listen-direct). */
        private val NO_DTLS_ENABLED = booleanPreferencesKey("no_dtls_enabled")
        private val SERVER_DIRECT_PORT = intPreferencesKey("server_direct_port")
        /** Порт сервера для raw-IP режима (на сервере нужен -listen-raw). */
        private val SERVER_RAW_PORT = intPreferencesKey("server_raw_port")
        /** Соединяться с TURN-relay по TCP вместо UDP (обход UDP-душения на некоторых сетях). */
        private val TURN_TCP_ENABLED = booleanPreferencesKey("turn_tcp_enabled")
        private val SNI = stringPreferencesKey("sni")

        private val DEPLOY_IP = stringPreferencesKey("deploy_ip")
        private val DEPLOY_LOGIN = stringPreferencesKey("deploy_login")
        private val DEPLOY_PASSWORD = stringPreferencesKey("deploy_password")
        private val DEPLOY_PASSWORD_ENCRYPTED = stringPreferencesKey("deploy_password_encrypted")
        private val DEPLOY_SSH_USE_KEY = booleanPreferencesKey("deploy_ssh_use_key")
        private val DEPLOY_SSH_PRIVATE_KEY_ENCRYPTED = stringPreferencesKey("deploy_ssh_private_key_encrypted")
        private val DEPLOY_SSH_PRIVATE_KEY = stringPreferencesKey("deploy_ssh_private_key") // legacy plaintext
        private val DEPLOY_SSH_KEY_PASSPHRASE_ENCRYPTED = stringPreferencesKey("deploy_ssh_key_passphrase_encrypted")
        private val DEPLOY_SSH_KEY_PASSPHRASE = stringPreferencesKey("deploy_ssh_key_passphrase") // legacy
        private val DEPLOY_SSH_KEY_NAME = stringPreferencesKey("deploy_ssh_key_name")
        private val DEPLOY_SSH_PORT = stringPreferencesKey("deploy_ssh_port")
        private val DEPLOY_DNS1 = stringPreferencesKey("deploy_dns1")
        private val DEPLOY_DNS2 = stringPreferencesKey("deploy_dns2")
        private val EXCLUDED_APPS = stringPreferencesKey("excluded_apps")
        
        private val DETAILED_LOGS = booleanPreferencesKey("detailed_logs")
        
        // ═══ Пароли и Управление ═══
        private val CONNECTION_PASSWORD = stringPreferencesKey("connection_password")
        private val CONNECTION_PASSWORD_ENCRYPTED = stringPreferencesKey("connection_password_encrypted")
        private val DEPLOY_MAIN_PASSWORD = stringPreferencesKey("deploy_main_password")
        private val DEPLOY_MAIN_PASSWORD_ENCRYPTED = stringPreferencesKey("deploy_main_password_encrypted")
        private val DEPLOY_ADMIN_ID = stringPreferencesKey("deploy_admin_id")
        private val DEPLOY_ADMIN_ID_ENCRYPTED = stringPreferencesKey("deploy_admin_id_encrypted")
        private val DEPLOY_BOT_TOKEN = stringPreferencesKey("deploy_bot_token")
        private val DEPLOY_BOT_TOKEN_ENCRYPTED = stringPreferencesKey("deploy_bot_token_encrypted")

        // ═══ Captcha Solve Mode ═══
        private val CAPTCHA_MODE = stringPreferencesKey("captcha_mode") // "auto", "wv", or "rjs"
        private val CAPTCHA_SOLVE_METHOD = stringPreferencesKey("captcha_solve_method") // "manual" or "auto"
        private val CAPTCHA_WBV_SOLVE_METHOD = stringPreferencesKey("captcha_wbv_solve_method") // "manual" or "auto"

        private val VK_AUTH_MODE = stringPreferencesKey("vk_auth_mode") // "account" or "anonymous"
        private val VK_ANON_PATH = stringPreferencesKey("vk_anon_path") // "vkcalls" or "legacy"
        private val GO_DNS_PRESET = stringPreferencesKey("go_dns_preset") // yandex/cloudflare/google/custom
        private val GO_DNS_CUSTOM = stringPreferencesKey("go_dns_custom")
        private val GO_DNS_DOH_CUSTOM = stringPreferencesKey("go_dns_doh_custom")
        private val OBFS_MODE = stringPreferencesKey("obfs_mode") // "audio" or "video"
        private val INTERFACE_ROLE = stringPreferencesKey("interface_role") // "user" or "admin"
        
        // ═══ VPN Exclusions Mode ═══
        private val IS_WHITELIST = booleanPreferencesKey("is_whitelist")
        private val SPLIT_TUNNEL_WHITELIST_MIGRATED = booleanPreferencesKey("split_tunnel_whitelist_migrated")
        /** Обход VPN: IP/CIDR/wildcard/домены, по одному на строку. */
        private val BYPASS_ROUTES = stringPreferencesKey("bypass_routes")

        // ═══ Theme Mode ═══
        private val THEME_MODE = stringPreferencesKey("theme_mode") // "system", "light", "dark"
        private val IS_DYNAMIC_COLOR = booleanPreferencesKey("is_dynamic_color")
        private val THEME_PALETTE = stringPreferencesKey("theme_palette")

        val CURRENT_PROFILE_ID = stringPreferencesKey("current_profile_id")
        val CURRENT_PROFILE_NAME = stringPreferencesKey("current_profile_name")

        private val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
        private val UPDATE_LATEST_VERSION = stringPreferencesKey("update_latest_version")
        private val UPDATE_LAST_ERROR = stringPreferencesKey("update_last_error")
        private val UPDATE_CHECK_INTERVAL_HOURS = intPreferencesKey("update_check_interval_hours")
        private val UPDATE_POSTPONE_UNTIL = longPreferencesKey("update_postpone_until")
        private val UPDATE_POSTPONE_VERSION = stringPreferencesKey("update_postpone_version")
        private val INCLUDE_BETA_UPDATES = booleanPreferencesKey("include_beta_updates")
        private val SUPPORT_NOTICE_SHOWN_VERSION_CODE = intPreferencesKey("support_notice_shown_version_code")

        /** versionCode, при первом запуске которого показывается экран поддержки (донат + канал). */
        const val SUPPORT_NOTICE_VERSION_CODE = 28

        // ═══ Поведение ═══
        private val AUTO_SWITCH_TO_LOGS = booleanPreferencesKey("auto_switch_to_logs")
        private val STOP_ON_WIFI = booleanPreferencesKey("stop_on_wifi")
        /** Автоперерезолв доменов обхода и reload WG при смене IP. */
        private val BYPASS_AUTO_REFRESH = booleanPreferencesKey("bypass_auto_refresh")
        private val CONNECTION_PIPELINE_ENABLED = booleanPreferencesKey("connection_pipeline_enabled")
        private val SORT_PROFILES_BY_PING = booleanPreferencesKey("sort_profiles_by_ping")
        /** vpn = Android VpnService; socks = локальный SOCKS5 без VPN. */
        private val CONNECTION_MODE = stringPreferencesKey("connection_mode")
        private val SOCKS_PORT = intPreferencesKey("socks_port")
        private val SOCKS_AUTH_ENABLED = booleanPreferencesKey("socks_auth_enabled")
        private val SOCKS_USERNAME = stringPreferencesKey("socks_username")
        private val SOCKS_PASSWORD = stringPreferencesKey("socks_password")
        private val SOCKS_PASSWORD_ENCRYPTED = stringPreferencesKey("socks_password_encrypted")
        /** -1 = выкл, 0 = при каждом открытии, иначе интервал в часах (6/12/24). */
        private val SUB_AUTO_REFRESH_HOURS = intPreferencesKey("sub_auto_refresh_hours")

        const val SUB_AUTO_REFRESH_NEVER = -1
        const val SUB_AUTO_REFRESH_EVERY_OPEN = 0
        const val DEFAULT_SUB_AUTO_REFRESH_HOURS = 12
        const val DEFAULT_SOCKS_PORT = 1080
        const val CONNECTION_MODE_VPN = "vpn"
        const val CONNECTION_MODE_SOCKS = "socks"
        /** rawtun = сырые IP-пакеты напрямую в go_client через TUN-fd, вообще без WireGuard. */
        const val CONNECTION_MODE_RAWTUN = "rawtun"

        private val HAS_SEEN_WELCOME_DIALOG = booleanPreferencesKey("has_seen_welcome_dialog")
        private val LAST_SEEN_VERSION_CODE = intPreferencesKey("last_seen_version_code")

        /** versionCode, при первом запуске после которого включается VKCalls у всех. */
        const val VKCALLS_FORCE_MIGRATION_VERSION = 23

        private val migrationMutex = Mutex()
        private val migrationReady = CompletableDeferred<Unit>()
        @Volatile
        private var migrationStarted = false

        /** Нормализованный путь VK для Go-клиента. */
        fun normalizeVkAnonPath(path: String?): String {
            return if (path.equals("legacy", ignoreCase = true)) "legacy" else "vkcalls"
        }

        fun normalizeObfsMode(mode: String?): String {
            return if (mode.equals("video", ignoreCase = true)) "video" else "audio"
        }

        fun normalizeConnectionMode(mode: String?): String {
            return when {
                mode.equals(CONNECTION_MODE_SOCKS, ignoreCase = true) -> CONNECTION_MODE_SOCKS
                mode.equals(CONNECTION_MODE_RAWTUN, ignoreCase = true) -> CONNECTION_MODE_RAWTUN
                else -> CONNECTION_MODE_VPN
            }
        }

        fun normalizeSocksPort(port: Int): Int = port.coerceIn(1, 65535)

        fun socksListenAddress(port: Int): String = "127.0.0.1:${normalizeSocksPort(port)}"

        fun obfsModeDisplay(mode: String): String {
            return if (normalizeObfsMode(mode) == "video") "Видеозвонок (H.264)" else "Аудиозвонок (OPUS)"
        }

        /** DNS для Go-резолвера (login.vk.ru, api.vk.me). */
        fun normalizeGoDnsPreset(preset: String?): String {
            return when (preset?.trim()?.lowercase()) {
                "cloudflare", "google", "custom",
                "doh-yandex", "doh-cloudflare", "doh-google", "doh-custom" -> preset.trim().lowercase()
                else -> "yandex" // включая устаревший "auto"
            }
        }

        fun isDohGoDnsPreset(preset: String?): Boolean {
            return normalizeGoDnsPreset(preset).startsWith("doh")
        }

        fun normalizeGoDnsServers(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.split(",", " ", "\n", ";")
                .map { it.trim() }
                .filter { it.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")) }
                .distinct()
                .joinToString(",")
        }

        fun normalizeGoDnsDohUrls(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.split(",", "\n", ";")
                .mapNotNull { normalizeGoDnsDohUrlSingle(it) }
                .distinct()
                .joinToString(",")
        }

        private fun normalizeGoDnsDohUrlSingle(raw: String): String? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) return null
            return try {
                val url = java.net.URL(trimmed)
                if (url.host.isNullOrBlank()) return null
                val path = url.path.trim('/')
                if (path.isEmpty()) trimmed.trimEnd('/') + "/dns-query" else trimmed
            } catch (_: Exception) {
                null
            }
        }

        data class GoDnsDisplay(
            val preset: String,
            val title: String,
            val servers: List<String>,
        )

        fun goDnsDisplay(preset: String, customRaw: String = ""): GoDnsDisplay {
            return when (normalizeGoDnsPreset(preset)) {
                "cloudflare" -> GoDnsDisplay("cloudflare", "Cloudflare", listOf("1.1.1.1", "1.0.0.1"))
                "google" -> GoDnsDisplay("google", "Google DNS", listOf("8.8.8.8", "8.8.4.4"))
                "doh-yandex" -> GoDnsDisplay("doh-yandex", "Яндекс DoH", listOf("common.dot.dns.yandex.net"))
                "doh-cloudflare" -> GoDnsDisplay("doh-cloudflare", "Cloudflare DoH", listOf("cloudflare-dns.com"))
                "doh-google" -> GoDnsDisplay("doh-google", "Google DoH", listOf("dns.google"))
                "doh-custom" -> {
                    val urls = normalizeGoDnsDohUrls(customRaw)
                        .split(",")
                        .filter { it.isNotEmpty() }
                    GoDnsDisplay("doh-custom", "Свой DoH", urls)
                }
                "custom" -> {
                    val servers = normalizeGoDnsServers(customRaw)
                        .split(",")
                        .filter { it.isNotEmpty() }
                    GoDnsDisplay("custom", "Свой DNS", servers)
                }
                else -> GoDnsDisplay("yandex", "Яндекс DNS", listOf("77.88.8.8", "77.88.8.1"))
            }
        }

        fun goDnsDisplayFromArg(arg: String): GoDnsDisplay {
            val trimmed = arg.trim()
            return when {
                trimmed.startsWith("custom:", ignoreCase = true) ->
                    goDnsDisplay("custom", trimmed.removePrefix("custom:").removePrefix("CUSTOM:"))
                trimmed.startsWith("doh:", ignoreCase = true) ->
                    goDnsDisplay("doh-custom", trimmed.removePrefix("doh:").removePrefix("DOH:"))
                else -> goDnsDisplay(trimmed)
            }
        }

        suspend fun resolveGoDnsArg(context: Context): String {
            awaitMigrations(context)
            val store = SettingsStore(context)
            return store.resolveGoDnsArg()
        }

        suspend fun awaitMigrations(context: Context) {
            if (!migrationStarted) {
                SettingsStore(context.applicationContext)
            }
            migrationReady.await()
        }

        suspend fun resolveVkAnonPath(context: Context): String {
            awaitMigrations(context)
            return normalizeVkAnonPath(SettingsStore(context).vkAnonPath.first())
        }

        private fun scheduleMigrations(store: SettingsStore) {
            if (migrationStarted) return
            synchronized(this) {
                if (migrationStarted) return
                migrationStarted = true
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    migrationMutex.withLock {
                        try {
                            store.migrateSecretsToKeystore()
                            store.migrateLegacyWhitelistMode()
                            store.runVersionMigrations()
                        } finally {
                            if (!migrationReady.isCompleted) {
                                migrationReady.complete(Unit)
                            }
                        }
                    }
                }
            }
        }
    }

    private val dataStore = appContext.dataStore
    private val secureStore = SecureStringStore(appContext)

    init {
        scheduleMigrations(this)
    }

    val peer: Flow<String> = dataStore.data.map { it[PEER] ?: "" }
    val vkHashes: Flow<String> = dataStore.data.map { it[VK_HASHES] ?: "" }
    val globalVkHashes: Flow<String> = appContext.dataStore.data.map { it[GLOBAL_VK_HASHES] ?: "" }
    val secondaryVkHash: Flow<String> = appContext.dataStore.data.map { it[SECONDARY_VK_HASH] ?: "" }
    val workersPerHash: Flow<Int> = dataStore.data.map { it[WORKERS_PER_HASH] ?: 9 }
    val protocol: Flow<String> = dataStore.data.map { it[PROTOCOL] ?: "udp" }
    val listenPort: Flow<Int> = dataStore.data.map { it[LISTEN_PORT] ?: 9000 }
    val manualPortsEnabled: Flow<Boolean> = dataStore.data.map { it[MANUAL_PORTS_ENABLED] ?: false }
    val serverDtlsPort: Flow<Int> = dataStore.data.map { it[SERVER_DTLS_PORT] ?: 56000 }
    val serverWgPort: Flow<Int> = dataStore.data.map { it[SERVER_WG_PORT] ?: 56001 }
    /** Требует сервер с флагом -listen-direct и совместимую версию сервера. */
    val noDtlsEnabled: Flow<Boolean> = dataStore.data.map { it[NO_DTLS_ENABLED] ?: false }
    val serverDirectPort: Flow<Int> = dataStore.data.map { it[SERVER_DIRECT_PORT] ?: 56002 }
    val serverRawPort: Flow<Int> = dataStore.data.map { it[SERVER_RAW_PORT] ?: 56003 }
    /** TURN-relay по TCP вместо UDP — обход UDP-душения на некоторых сетях (напр. Ростелеком). По умолчанию включено. */
    val turnTcpEnabled: Flow<Boolean> = dataStore.data.map { it[TURN_TCP_ENABLED] ?: true }
    val sni: Flow<String> = dataStore.data.map { it[SNI] ?: "" }

    val deployIp: Flow<String> = dataStore.data.map { it[DEPLOY_IP] ?: "" }
    val deployLogin: Flow<String> = dataStore.data.map { it[DEPLOY_LOGIN] ?: "" }
    val deployPassword: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD)
    }
    val deploySshUseKey: Flow<Boolean> = dataStore.data.map { it[DEPLOY_SSH_USE_KEY] ?: false }
    val deploySshPrivateKey: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_SSH_PRIVATE_KEY_ENCRYPTED, DEPLOY_SSH_PRIVATE_KEY)
    }
    val deploySshKeyPassphrase: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_SSH_KEY_PASSPHRASE_ENCRYPTED, DEPLOY_SSH_KEY_PASSPHRASE)
    }
    val deploySshKeyName: Flow<String> = dataStore.data.map { it[DEPLOY_SSH_KEY_NAME] ?: "" }
    val deploySshPort: Flow<String> = dataStore.data.map { it[DEPLOY_SSH_PORT] ?: "" }
    val deployDns1: Flow<String> = dataStore.data.map { it[DEPLOY_DNS1] ?: "1.1.1.1" }
    val deployDns2: Flow<String> = dataStore.data.map { it[DEPLOY_DNS2] ?: "1.0.0.1" }
    
    val excludedApps: Flow<String> = dataStore.data.map { it[EXCLUDED_APPS] ?: "" }
    
    val detailedLogs: Flow<Boolean> = dataStore.data.map { it[DETAILED_LOGS] ?: false }
    
    // ═══ Пароли и Управление ═══
    val connectionPassword: Flow<String> = dataStore.data.map {
        readSecret(it, CONNECTION_PASSWORD_ENCRYPTED, CONNECTION_PASSWORD)
    }
    val deployMainPassword: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD)
    }
    val deployAdminId: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_ADMIN_ID_ENCRYPTED, DEPLOY_ADMIN_ID)
    }
    val deployBotToken: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_BOT_TOKEN_ENCRYPTED, DEPLOY_BOT_TOKEN)
    }

    // ═══ Captcha Solve Mode ═══
    val captchaMode: Flow<String> = dataStore.data.map { it[CAPTCHA_MODE] ?: "auto" }
    val captchaSolveMethod: Flow<String> = dataStore.data.map { it[CAPTCHA_SOLVE_METHOD] ?: "auto" }
    val vkAuthMode: Flow<String> = dataStore.data.map { it[VK_AUTH_MODE] ?: "anonymous" }
    val vkAnonPath: Flow<String> = dataStore.data.map { it[VK_ANON_PATH] ?: "vkcalls" }
    val goDnsPreset: Flow<String> = dataStore.data.map { normalizeGoDnsPreset(it[GO_DNS_PRESET]) }
    val goDnsCustom: Flow<String> = dataStore.data.map { it[GO_DNS_CUSTOM] ?: "" }
    val goDnsDohCustom: Flow<String> = dataStore.data.map { it[GO_DNS_DOH_CUSTOM] ?: "" }
    val obfsMode: Flow<String> = dataStore.data.map { normalizeObfsMode(it[OBFS_MODE]) }
    /** "admin" показывает вкладку Деплой; "user" скрывает. По умолчанию admin. */
    val interfaceRole: Flow<String> = dataStore.data.map {
        if ((it[INTERFACE_ROLE] ?: "admin").equals("user", ignoreCase = true)) "user" else "admin"
    }
    val captchaWbvSolveMethod: Flow<String> = dataStore.data.map { it[CAPTCHA_WBV_SOLVE_METHOD] ?: "auto" }

    // ═══ VPN Exclusions Mode ═══
    val isWhitelist: Flow<Boolean> = dataStore.data.map { it[IS_WHITELIST] ?: false }
    val bypassRoutes: Flow<String> = dataStore.data.map { it[BYPASS_ROUTES] ?: "" }

    // ═══ Theme Mode ═══
    val hasSeenWelcomeDialog: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[HAS_SEEN_WELCOME_DIALOG] ?: false
        }

    suspend fun saveHasSeenWelcomeDialog(hasSeen: Boolean) {
        dataStore.edit { preferences ->
            preferences[HAS_SEEN_WELCOME_DIALOG] = hasSeen
        }
    }

    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val isDynamicColor: Flow<Boolean> = dataStore.data.map { it[IS_DYNAMIC_COLOR] ?: false }
    val themePalette: Flow<String> = dataStore.data.map { it[THEME_PALETTE] ?: "indigo" }

    val currentProfileId: Flow<String> = dataStore.data.map { it[CURRENT_PROFILE_ID] ?: "" }
    val currentProfileName: Flow<String> = dataStore.data.map { it[CURRENT_PROFILE_NAME] ?: "" }

    val updateLastCheckAt: Flow<Long> = dataStore.data.map { it[UPDATE_LAST_CHECK_AT] ?: 0L }
    val updateLatestVersion: Flow<String> = dataStore.data.map { it[UPDATE_LATEST_VERSION] ?: "" }
    val updateLastError: Flow<String> = dataStore.data.map { it[UPDATE_LAST_ERROR] ?: "" }
    val updateCheckIntervalHours: Flow<Int> = dataStore.data.map { it[UPDATE_CHECK_INTERVAL_HOURS] ?: 24 }
    val updatePostponeUntil: Flow<Long> = dataStore.data.map { it[UPDATE_POSTPONE_UNTIL] ?: 0L }
    val updatePostponeVersion: Flow<String> = dataStore.data.map { it[UPDATE_POSTPONE_VERSION] ?: "" }
    val includeBetaUpdates: Flow<Boolean> = dataStore.data.map { it[INCLUDE_BETA_UPDATES] ?: false }
    val supportNoticeShownVersionCode: Flow<Int> = dataStore.data.map { it[SUPPORT_NOTICE_SHOWN_VERSION_CODE] ?: 0 }

    // ═══ Поведение ═══
    val autoSwitchToLogs: Flow<Boolean> = dataStore.data.map { it[AUTO_SWITCH_TO_LOGS] ?: true }
    val stopOnWifi: Flow<Boolean> = dataStore.data.map { it[STOP_ON_WIFI] ?: false }
    /** Периодический перерезолв доменов обхода. По умолчанию включён. */
    val bypassAutoRefresh: Flow<Boolean> = dataStore.data.map { it[BYPASS_AUTO_REFRESH] ?: true }
    /** Схема этапов подключения на вкладке «Логи». По умолчанию включена. */
    val connectionPipelineEnabled: Flow<Boolean> = dataStore.data.map { it[CONNECTION_PIPELINE_ENABLED] ?: true }
    val sortProfilesByPing: Flow<Boolean> = dataStore.data.map { it[SORT_PROFILES_BY_PING] ?: false }
    val connectionMode: Flow<String> = dataStore.data.map { normalizeConnectionMode(it[CONNECTION_MODE]) }
    val socksPort: Flow<Int> = dataStore.data.map { normalizeSocksPort(it[SOCKS_PORT] ?: DEFAULT_SOCKS_PORT) }
    val socksAuthEnabled: Flow<Boolean> = dataStore.data.map { it[SOCKS_AUTH_ENABLED] ?: false }
    val socksUsername: Flow<String> = dataStore.data.map { it[SOCKS_USERNAME] ?: "" }
    val socksPassword: Flow<String> = dataStore.data.map {
        readSecret(it, SOCKS_PASSWORD_ENCRYPTED, SOCKS_PASSWORD)
    }
    val subscriptionAutoRefreshHours: Flow<Int> = dataStore.data.map {
        it[SUB_AUTO_REFRESH_HOURS] ?: DEFAULT_SUB_AUTO_REFRESH_HOURS
    }

    suspend fun saveAutoSwitchToLogs(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_SWITCH_TO_LOGS] = enabled }
    }

    suspend fun saveStopOnWifi(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[STOP_ON_WIFI] = enabled }
    }

    suspend fun saveBypassAutoRefresh(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[BYPASS_AUTO_REFRESH] = enabled }
    }

    suspend fun saveConnectionMode(mode: String) {
        dataStore.edit { prefs -> prefs[CONNECTION_MODE] = normalizeConnectionMode(mode) }
    }

    suspend fun saveSocksPort(port: Int) {
        dataStore.edit { prefs -> prefs[SOCKS_PORT] = normalizeSocksPort(port) }
    }

    suspend fun saveSocksAuthEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SOCKS_AUTH_ENABLED] = enabled }
    }

    suspend fun saveSocksUsername(username: String) {
        dataStore.edit { prefs -> prefs[SOCKS_USERNAME] = username }
    }

    suspend fun saveSocksPassword(password: String) {
        dataStore.edit { prefs ->
            prefs.putSecret(SOCKS_PASSWORD_ENCRYPTED, SOCKS_PASSWORD, password)
        }
    }

    suspend fun saveConnectionPipelineEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[CONNECTION_PIPELINE_ENABLED] = enabled }
    }

    suspend fun saveSubscriptionAutoRefreshHours(hours: Int) {
        val normalized = when (hours) {
            SUB_AUTO_REFRESH_NEVER, SUB_AUTO_REFRESH_EVERY_OPEN -> hours
            6, 12, 24 -> hours
            else -> DEFAULT_SUB_AUTO_REFRESH_HOURS
        }
        dataStore.edit { prefs -> prefs[SUB_AUTO_REFRESH_HOURS] = normalized }
    }

    suspend fun saveSortProfilesByPing(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SORT_PROFILES_BY_PING] = enabled }
    }

    suspend fun saveThemeMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode
        }
    }

    suspend fun saveDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun saveThemePalette(palette: String) {
        dataStore.edit { prefs ->
            prefs[THEME_PALETTE] = palette
        }
    }

    suspend fun saveCurrentProfile(id: String, name: String) {
        dataStore.edit { prefs ->
            prefs[CURRENT_PROFILE_ID] = id
            prefs[CURRENT_PROFILE_NAME] = name
        }
    }

    suspend fun saveUpdateState(lastCheckAt: Long, latestVersion: String, error: String) {
        dataStore.edit { prefs ->
            prefs[UPDATE_LAST_CHECK_AT] = lastCheckAt
            prefs[UPDATE_LATEST_VERSION] = latestVersion
            prefs[UPDATE_LAST_ERROR] = error
        }
    }

    suspend fun saveUpdateCheckIntervalHours(hours: Int) {
        dataStore.edit { prefs ->
            prefs[UPDATE_CHECK_INTERVAL_HOURS] = hours
        }
    }

    suspend fun saveIncludeBetaUpdates(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[INCLUDE_BETA_UPDATES] = enabled
        }
    }

    suspend fun saveSupportNoticeShownVersionCode(versionCode: Int) {
        dataStore.edit { prefs ->
            prefs[SUPPORT_NOTICE_SHOWN_VERSION_CODE] = versionCode
        }
    }

    suspend fun saveUpdatePostpone(version: String, until: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_POSTPONE_VERSION] = version
            prefs[UPDATE_POSTPONE_UNTIL] = until
        }
    }

    suspend fun save(
        peer: String,
        vkHashes: String,
        secondaryVkHash: String,
        workersPerHash: Int,
        protocol: String,
        listenPort: Int,
        sni: String = "",
    ) {
        dataStore.edit { prefs ->
            val cleanVkHashes = vkHashes.split(Regex("[,\\s\\n]+"))
                .map { stripVkUrlStatic(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(",")

            prefs[PEER] = peer
            prefs[VK_HASHES] = cleanVkHashes
            prefs[SECONDARY_VK_HASH] = secondaryVkHash
            prefs[WORKERS_PER_HASH] = workersPerHash
            prefs[PROTOCOL] = protocol
            prefs[LISTEN_PORT] = listenPort
            prefs[SNI] = sni
        }
    }

    suspend fun saveManualPortsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[MANUAL_PORTS_ENABLED] = enabled
        }
    }

    suspend fun savePorts(serverDtlsPort: Int, serverWgPort: Int, listenPort: Int) {
        dataStore.edit { prefs ->
            prefs[SERVER_DTLS_PORT] = serverDtlsPort
            prefs[SERVER_WG_PORT] = serverWgPort
            prefs[LISTEN_PORT] = listenPort
        }
    }

    suspend fun saveServerRawPort(port: Int) {
        dataStore.edit { prefs -> prefs[SERVER_RAW_PORT] = port.coerceIn(1, 65535) }
    }

    suspend fun saveTurnTcpEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[TURN_TCP_ENABLED] = enabled }
    }

    suspend fun saveGlobalVkHashes(hashes: String) {
        appContext.dataStore.edit { it[GLOBAL_VK_HASHES] = hashes }
    }

    suspend fun saveDeploy(
        ip: String,
        login: String,
        pass: String,
        sshPort: String,
        dns1: String,
        dns2: String,
        useSshKey: Boolean? = null,
        keyPassphrase: String? = null,
    ) {
        dataStore.edit { prefs ->
            prefs[DEPLOY_IP] = ip
            prefs[DEPLOY_LOGIN] = login
            prefs.putSecret(DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD, pass)
            prefs[DEPLOY_SSH_PORT] = sshPort
            prefs[DEPLOY_DNS1] = dns1
            prefs[DEPLOY_DNS2] = dns2
            useSshKey?.let { prefs[DEPLOY_SSH_USE_KEY] = it }
            keyPassphrase?.let {
                prefs.putSecret(DEPLOY_SSH_KEY_PASSPHRASE_ENCRYPTED, DEPLOY_SSH_KEY_PASSPHRASE, it)
            }
        }
    }

    suspend fun saveDeploySshPrivateKey(keyContent: String, keyName: String) {
        dataStore.edit { prefs ->
            prefs.putSecret(DEPLOY_SSH_PRIVATE_KEY_ENCRYPTED, DEPLOY_SSH_PRIVATE_KEY, keyContent)
            prefs[DEPLOY_SSH_KEY_NAME] = keyName
            prefs[DEPLOY_SSH_USE_KEY] = true
        }
    }

    suspend fun clearDeploySshPrivateKey() {
        dataStore.edit { prefs ->
            prefs.remove(DEPLOY_SSH_PRIVATE_KEY_ENCRYPTED)
            prefs.remove(DEPLOY_SSH_PRIVATE_KEY)
            prefs.remove(DEPLOY_SSH_KEY_NAME)
        }
    }

    suspend fun saveExcludedApps(packages: String) {
        dataStore.edit { prefs ->
            prefs[EXCLUDED_APPS] = packages
            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }
    
    suspend fun saveDetailedLogs(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[DETAILED_LOGS] = enabled
        }
    }
    
    // ═══ Сохранение пароля подключения ═══
    suspend fun saveConnectionPassword(password: String) {
        dataStore.edit { prefs ->
            prefs.putSecret(CONNECTION_PASSWORD_ENCRYPTED, CONNECTION_PASSWORD, password)
        }
    }
    
    // ═══ Сохранение секретов деплоя ═══
    suspend fun saveDeploySecrets(mainPass: String, adminId: String, botToken: String, sshPort: String) {
        dataStore.edit { prefs ->
            prefs.putSecret(DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD, mainPass)
            prefs.putSecret(DEPLOY_ADMIN_ID_ENCRYPTED, DEPLOY_ADMIN_ID, adminId)
            prefs.putSecret(DEPLOY_BOT_TOKEN_ENCRYPTED, DEPLOY_BOT_TOKEN, botToken)
            prefs[DEPLOY_SSH_PORT] = sshPort
        }
    }

    // ═══ Сохранение режима обхода капчи ═══
    suspend fun saveCaptchaMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[CAPTCHA_MODE] = mode
        }
    }

    suspend fun saveCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            prefs[CAPTCHA_SOLVE_METHOD] = method
        }
    }

    suspend fun saveVkAuthMode(mode: String) {
        val normalized = if (mode.equals("anonymous", ignoreCase = true)) "anonymous" else "account"
        dataStore.edit { prefs ->
            prefs[VK_AUTH_MODE] = normalized
        }
    }

    suspend fun saveVkAnonPath(path: String) {
        val normalized = if (path.equals("legacy", ignoreCase = true)) "legacy" else "vkcalls"
        dataStore.edit { prefs ->
            prefs[VK_ANON_PATH] = normalized
        }
    }

    suspend fun saveGoDns(preset: String, custom: String? = null, dohCustom: String? = null) {
        dataStore.edit { prefs ->
            val normalizedPreset = normalizeGoDnsPreset(preset)
            prefs[GO_DNS_PRESET] = normalizedPreset
            if (custom != null) {
                prefs[GO_DNS_CUSTOM] = normalizeGoDnsServers(custom)
            }
            if (dohCustom != null) {
                prefs[GO_DNS_DOH_CUSTOM] = normalizeGoDnsDohUrls(dohCustom)
            }
        }
    }

    suspend fun saveObfsMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[OBFS_MODE] = normalizeObfsMode(mode)
        }
    }

    suspend fun saveInterfaceRole(role: String) {
        val normalized = if (role.equals("user", ignoreCase = true)) "user" else "admin"
        dataStore.edit { prefs ->
            prefs[INTERFACE_ROLE] = normalized
        }
    }

    suspend fun resolveGoDnsArg(): String {
        return when (val preset = normalizeGoDnsPreset(goDnsPreset.first())) {
            "custom" -> {
                val servers = normalizeGoDnsServers(goDnsCustom.first())
                if (servers.isNotEmpty()) "custom:$servers" else "yandex"
            }
            "doh-custom" -> {
                val urls = normalizeGoDnsDohUrls(goDnsDohCustom.first())
                if (urls.isNotEmpty()) "doh:$urls" else "doh-yandex"
            }
            else -> preset
        }
    }

    suspend fun saveWbvCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            prefs[CAPTCHA_WBV_SOLVE_METHOD] = method
            if (prefs[CAPTCHA_MODE] == "wv") {
                prefs[CAPTCHA_SOLVE_METHOD] = method
            }
        }
    }

    suspend fun saveBypassRoutes(raw: String) {
        dataStore.edit { prefs -> prefs[BYPASS_ROUTES] = raw.trim() }
    }

    suspend fun saveExceptionsMode(packages: String, isWhitelist: Boolean) {
        dataStore.edit { prefs ->
            prefs[EXCLUDED_APPS] = packages
            prefs[IS_WHITELIST] = isWhitelist
            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }

    suspend fun saveExceptionsMode(isWhitelist: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_WHITELIST] = isWhitelist
            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }

    suspend fun migrateLegacyWhitelistMode() {
        val currentPrefs = dataStore.data.first()
        if (currentPrefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] == true) return

        val hasLegacyWhitelist = currentPrefs[IS_WHITELIST] == true
        val installedApps = if (hasLegacyWhitelist) installedSplitTunnelPackages() else emptySet()
        dataStore.edit { prefs ->
            if (prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] == true) return@edit

            if (prefs[IS_WHITELIST] == true) {
                val legacyExcluded = prefs[EXCLUDED_APPS]
                    ?.split(",")
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet()
                prefs[EXCLUDED_APPS] = (installedApps - legacyExcluded).sorted().joinToString(",")
            }

            prefs[SPLIT_TUNNEL_WHITELIST_MIGRATED] = true
        }
    }

    private fun installedSplitTunnelPackages(): Set<String> {
        return runCatching {
            appContext.packageManager
                .getInstalledApplications(PackageManager.GET_META_DATA)
                .map { it.packageName }
                .filter {
                    it != appContext.packageName &&
                        !it.contains("vkontakte") &&
                        !it.contains("vk.calls")
                }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private suspend fun migrateSecretsToKeystore() {
        dataStore.edit { prefs ->
            prefs.migrateSecret(DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD)
            prefs.migrateSecret(CONNECTION_PASSWORD_ENCRYPTED, CONNECTION_PASSWORD)
            prefs.migrateSecret(DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD)
            prefs.migrateSecret(DEPLOY_ADMIN_ID_ENCRYPTED, DEPLOY_ADMIN_ID)
            prefs.migrateSecret(DEPLOY_BOT_TOKEN_ENCRYPTED, DEPLOY_BOT_TOKEN)
            prefs.migrateSecret(SOCKS_PASSWORD_ENCRYPTED, SOCKS_PASSWORD)
        }
    }

    private suspend fun runVersionMigrations() {
        val currentCode = BuildConfig.VERSION_CODE
        dataStore.edit { prefs ->
            val lastSeen = prefs[LAST_SEEN_VERSION_CODE] ?: 0
            if (currentCode <= lastSeen) {
                return@edit
            }
            if (lastSeen < VKCALLS_FORCE_MIGRATION_VERSION && currentCode >= VKCALLS_FORCE_MIGRATION_VERSION) {
                prefs[VK_ANON_PATH] = "vkcalls"
            }
            prefs[LAST_SEEN_VERSION_CODE] = currentCode
        }
    }

    private fun readSecret(
        prefs: Preferences,
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>
    ): String {
        val encrypted = prefs[encryptedKey]
        val fromSecure = secureStore.decrypt(encrypted)
        if (!fromSecure.isNullOrEmpty()) return fromSecure
        // Если encrypted-ключ ещё хранит legacy plaintext (без v1:)
        if (!encrypted.isNullOrBlank() && !encrypted.startsWith("v1:")) {
            return encrypted
        }
        if (legacyKey != encryptedKey) {
            return prefs[legacyKey] ?: ""
        }
        return ""
    }

    private fun MutablePreferences.putSecret(
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        value: String
    ) {
        if (value.isBlank()) {
            remove(encryptedKey)
            if (legacyKey != encryptedKey) remove(legacyKey)
        } else {
            this[encryptedKey] = secureStore.encrypt(value)
            // Важно: для SSH-ключа encryptedKey == legacyKey — нельзя remove после записи.
            if (legacyKey != encryptedKey) {
                remove(legacyKey)
            }
        }
    }

    private fun MutablePreferences.migrateSecret(
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>
    ) {
        val legacyValue = this[legacyKey]
        val encryptedValue = this[encryptedKey]
        if (!encryptedValue.isNullOrBlank()) {
            remove(legacyKey)
            return
        }
        if (!legacyValue.isNullOrBlank()) {
            runCatching {
                this[encryptedKey] = secureStore.encrypt(legacyValue)
                remove(legacyKey)
            }
        }
    }
}

/** Извлекает хеш из VK ссылки */
fun stripVkUrlStatic(input: String): String {
    var s = input.trim()
    val lower = s.lowercase()
    val prefixes = listOf(
        "https://vk.com/call/join/",
        "http://vk.com/call/join/",
        "https://m.vk.com/call/join/",
        "http://m.vk.com/call/join/",
        "https://vk.ru/call/join/",
        "http://vk.ru/call/join/",
        "https://m.vk.ru/call/join/",
        "http://m.vk.ru/call/join/",
        "m.vk.com/call/join/",
        "vk.com/call/join/",
        "m.vk.ru/call/join/",
        "vk.ru/call/join/"
    )
    for (prefix in prefixes) {
        if (lower.startsWith(prefix)) {
            s = s.substring(prefix.length)
            break
        }
    }
    val qIdx = s.indexOf('?')
    if (qIdx != -1) s = s.substring(0, qIdx)
    val hIdx = s.indexOf('#')
    if (hIdx != -1) s = s.substring(0, hIdx)
    return s.trimEnd('/')
}
