package com.glebsbp.client

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun diag(message: String, isError: Boolean = false) {
    Log.i("TunFdBridge", message)
    runCatching { TunnelManager.addRawDiagLog(message, isError) }
}

/**
 * Передаёт TUN fd в go_client через unix-сокет (SCM_RIGHTS), потому что
 * go_client — отдельный OS-процесс (ProcessBuilder), а не JNI/in-process код:
 * штатной Java-передачи fd в дочерний процесс не существует.
 *
 * Android — КЛИЕНТ (connect), go_client — СЕРВЕР (слушает, см. recvTunFD в
 * tun_fd.go). Раньше было наоборот: Android слушал (LocalServerSocket),
 * go_client дозванивался с ретраями. Это создавало гонку — go_client стартует
 * и пытается подключиться раньше, чем Android вообще успевает вызвать
 * VpnService.Builder().establish() (на некоторых устройствах — больше
 * секунды), так что первая попытка почти всегда получала connection refused.
 * Инверсия убирает гонку в корне: go_client открывает свой Unix-сокет сразу
 * при получении RAWCONF и просто ждёт в accept() сколько нужно — Android
 * подключается уже когда TUN реально готов (net.ListenUnix + AcceptUnix на
 * Go-стороне).
 *
 * Используется android.net.LocalSocket (abstract namespace — без файла на
 * диске), тот же механизм, которым системные демоны Android передают друг
 * другу дескрипторы.
 */
object TunFdBridge {
    private const val TAG = "TunFdBridge"
    private const val CONNECT_RETRY_DELAY_MS = 200L
    private const val MAX_ATTEMPTS = 25 // 25 × 200мс = 5с общего окна ожидания

    /** Голое имя сокета (без "@") — то же имя должен слушать go_client (-tun-fd-sock). */
    fun newSocketName(): String = "glebsbp_tun_fd_${android.os.Process.myPid()}_${System.nanoTime()}"

    /** То же имя с "@" — так его должен указывать go_client в -tun-fd-sock. */
    fun goSockPath(name: String): String = "@$name"

    /**
     * Подключается к unix-сокету [name], который слушает go_client, и
     * отправляет туда [pfd]. Ретраит connect(), потому что go_client может ещё
     * не успеть дойти до ListenUnix (маловероятно — Go стартует раньше и сам
     * этот шаг почти мгновенный, но страховка не помешает на медленных
     * устройствах/под нагрузкой).
     */
    suspend fun sendOnce(name: String, pfd: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            var client: LocalSocket? = null
            try {
                diag("sendOnce попытка #$attempt: connect() на $name")
                client = LocalSocket()
                client.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                diag("sendOnce попытка #$attempt: connect() OK, отправляю fd...")
                client.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                // Сами байты не важны — факт отправки несёт ancillary-данные с fd.
                client.outputStream.write(1)
                client.outputStream.flush()
                diag("TUN fd отправлен в go_client через $name (попытка #$attempt)")
                return@withContext
            } catch (e: Exception) {
                lastError = e
                diag("sendOnce попытка #$attempt FAILED: ${e.javaClass.simpleName}: ${e.message}", isError = true)
            } finally {
                runCatching { client?.close() }
            }
            delay(CONNECT_RETRY_DELAY_MS)
        }
        diag("sendOnce: не удалось подключиться к go_client за $MAX_ATTEMPTS попыток, сдаюсь", isError = true)
        throw IllegalStateException("TunFdBridge.sendOnce: не удалось подключиться к go_client за $MAX_ATTEMPTS попыток", lastError)
    }
}
