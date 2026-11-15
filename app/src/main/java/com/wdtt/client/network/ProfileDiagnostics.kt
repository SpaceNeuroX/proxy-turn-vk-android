package com.wdtt.client

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Проверка соединения для отдельного профиля (вкладка «Профили»).
 *
 * Запускает Go-клиент в режиме -diag отдельным процессом — туннель не
 * трогается, можно проверить любой профиль, даже когда подключён другой.
 * Параметры те же, с которыми профиль подключался бы сейчас: режим, порты,
 * транспорт и свои адреса TURN, маскировка, DNS (с запасным DNS сети),
 * ключ сервера из ссылки.
 */
object ProfileDiagnostics {
    /** Состояние проверки по id профиля — для листа с подробностями. */
    val states = mutableStateMapOf<String, DiagnosticsState>()

    private const val TIMEOUT_MS = 60_000L

    fun isRunning(profileId: String): Boolean =
        states[profileId]?.let { it.running || it.requested } == true

    /**
     * Запускает проверку и ждёт итога. Возвращает задержку до сервера в мс
     * (если сервер ответил) или -1.
     */
    suspend fun run(context: Context, profile: ConnectionProfile, hashes: String): Long {
        val id = profile.id
        withContext(Dispatchers.Main) { states[id] = DiagnosticsState(requested = true) }
        val cmd = buildCommand(context, profile, hashes)
        if (cmd == null) {
            finishWithError(id, "Нет данных для проверки", "Укажите сервер, пароль и VK-хеш профиля.")
            return -1
        }

        return withContext(Dispatchers.IO) {
            val process = runCatching {
                ProcessBuilder(cmd)
                    .directory(context.filesDir)
                    .redirectErrorStream(true)
                    .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }
                    .start()
            }.getOrElse {
                finishWithError(id, "Проверка не запустилась", it.message ?: "ошибка запуска")
                return@withContext -1L
            }
            try {
                coroutineScope {
                    // Чтение потока блокирующее и таймаутом не прерывается —
                    // по таймауту убиваем процесс, и чтение закончится само.
                    val killer = launch {
                        delay(TIMEOUT_MS)
                        process.destroyForcibly()
                    }
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (!trimmed.startsWith(ConnectionDiagnostics.LINE_PREFIX)) continue
                            withContext(Dispatchers.Main) {
                                val current = states[id] ?: DiagnosticsState()
                                ConnectionDiagnostics.apply(current, trimmed)?.let { states[id] = it }
                            }
                        }
                    }
                    killer.cancel()
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            withContext(Dispatchers.Main) {
                val st = states[id] ?: DiagnosticsState()
                if (st.running || st.requested || st.verdict == null) {
                    // Процесс завершился без итога (таймаут, падение).
                    states[id] = st.copy(
                        running = false,
                        requested = false,
                        finishedAtMs = System.currentTimeMillis(),
                        steps = st.steps.map {
                            if (it.state == DiagStepState.RUNNING) it.copy(state = DiagStepState.SKIP, text = "не завершено") else it
                        },
                    )
                }
            }
            val verdict = states[id]?.verdict
            val rtt = verdict?.facts?.get("rtt_ms")?.toLongOrNull()
            if (rtt != null && verdict.severity != DiagSeverity.ERROR) rtt else -1L
        }
    }

    private suspend fun finishWithError(id: String, title: String, text: String) {
        withContext(Dispatchers.Main) {
            val now = System.currentTimeMillis()
            states[id] = DiagnosticsState(
                runId = now,
                trigger = "manual",
                startedAtMs = now,
                finishedAtMs = now,
                verdict = DiagVerdict("config", DiagSeverity.ERROR, title, text, emptyList(), emptyMap()),
            )
        }
    }

    private suspend fun buildCommand(context: Context, profile: ConnectionProfile, hashes: String): List<String>? {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libclient.so")
        if (!binary.exists()) return null
        val hashList = hashes.split(Regex("[,\\s\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (profile.peer.isBlank() || profile.password.isBlank() || hashList.isEmpty()) return null

        val store = SettingsStore(context)
        val mode = SettingsStore.normalizeConnectionMode(store.connectionMode.first())
        val isRaw = mode == SettingsStore.CONNECTION_MODE_RAWTUN
        val noDtls = !isRaw && store.noDtlsEnabled.first()
        val manualPorts = store.manualPortsEnabled.first()
        val dtlsPort = if (manualPorts) store.serverDtlsPort.first() else 56000
        val directPort = if (manualPorts) store.serverDirectPort.first() else 56002
        val rawPort = if (manualPorts) store.serverRawPort.first() else 56003
        // Порт — как при подключении (TunnelService): у WG — из адреса профиля,
        // если он там есть; у Raw/Direct — из настроек.
        val peer = when {
            isRaw -> PeerAddress.withPort(profile.peer, rawPort)
            noDtls -> PeerAddress.withPort(profile.peer, directPort)
            else -> PeerAddress.ensurePort(profile.peer, dtlsPort)
        }
        val profileDtlsPort = PeerAddress.port(profile.peer) ?: dtlsPort

        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "unknown"

        return buildList {
            add(binary.absolutePath)
            add("-diag")
            addAll(listOf("-peer", peer))
            addAll(listOf("-vk", hashList.take(SettingsStore.MAX_VK_HASHES).joinToString(",")))
            addAll(listOf("-password", profile.password))
            addAll(listOf("-device-id", androidId))
            addAll(listOf("-mode", if (isRaw) "rawtun" else "vpn"))
            if (noDtls) add("-notls")
            addAll(listOf("-obfs", SettingsStore.normalizeObfsMode(store.obfsMode.first())))
            addAll(listOf("-turn-transport", SettingsStore.normalizeTurnTransport(store.turnTransport.first())))
            store.turnHosts.first().trim().takeIf { it.isNotEmpty() }?.let { addAll(listOf("-turn", it)) }
            addAll(listOf("-go-dns", store.resolveGoDnsArg()))
            NetworkDns.current(context).takeIf { it.isNotEmpty() }?.let {
                addAll(listOf("-dns-fallback", it.joinToString(",")))
            }
            addAll(listOf("-diag-ports", "dtls=$profileDtlsPort,direct=$directPort,raw=$rawPort"))
            profile.diagKey.takeIf { it.isNotBlank() }?.let { addAll(listOf("-diag-key", it)) }
        }
    }
}
