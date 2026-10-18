package com.glebsbp.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class HashCheckResult(
    val hash: String,
    val status: String,
    val message: String,
)

object HashCheckHelper {
    /**
     * Проверяет VK-хеши через libclient.so `-check-hashes`.
     * Порядок слотов в [hashes] = Pair(slot, hash), slot обычно 1..4.
     * [onUpdate] вызывается с Main не гарантируется — обновляйте UI через callback сами.
     */
    suspend fun checkHashes(
        context: Context,
        hashes: List<Pair<Int, String>>,
        captchaMode: String = "auto",
        vkAnonPath: String = "vkcalls",
        goDnsArg: String = "yandex",
        onUpdate: (Int, HashCheckResult) -> Unit = { _, _ -> },
    ): Map<Int, HashCheckResult> = withContext(Dispatchers.IO) {
        if (hashes.isEmpty()) return@withContext emptyMap()

        val binaryPath = context.applicationInfo.nativeLibraryDir + "/libclient.so"
        if (!java.io.File(binaryPath).exists()) {
            return@withContext hashes.associate { (slot, hash) ->
                slot to HashCheckResult(hash, "error", "Бинарный клиент не найден")
            }
        }

        val cmd = mutableListOf(
            binaryPath,
            "-check-hashes",
            "-vk", hashes.joinToString(",") { it.second },
            "-captcha-mode", captchaMode,
            "-vk-auth", "anonymous",
            "-vk-anon-path", if (vkAnonPath.equals("legacy", true)) "legacy" else "vkcalls",
            "-go-dns", goDnsArg,
            "-device-id",
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown",
        )

        val process = ProcessBuilder(cmd)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            }
            .start()

        val byOrder = hashes.mapIndexed { order, pair -> (order + 1) to pair }.toMap()
        val parsed = mutableMapOf<Int, HashCheckResult>()
        var timedOut = false
        var currentSlot: Int? = null
        val timeoutMs = (hashes.size * 100_000L).coerceAtLeast(90_000L)
        var cleanedUp = false
        val startedAutoWebView = !TunnelManager.running.value

        fun cleanup() {
            if (cleanedUp) return
            cleanedUp = true
            if (process.isAlive) {
                runCatching { process.destroyForcibly() }
            }
            ManlCaptchaWebViewManager.cancelCaptcha()
            if (startedAutoWebView && !TunnelManager.running.value) {
                CaptchaWebViewManager.onTunnelStop()
            }
        }

        if (startedAutoWebView) {
            CaptchaWebViewManager.onTunnelStart(context.applicationContext)
        }

        val killer = Thread {
            try {
                Thread.sleep(timeoutMs)
                if (process.isAlive) {
                    timedOut = true
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("HASH_CHECK_START|") -> {
                        val parts = line.split("|", limit = 3)
                        val order = parts.getOrNull(1)?.toIntOrNull() ?: continue
                        val original = byOrder[order] ?: continue
                        currentSlot = original.first
                        val result = HashCheckResult(
                            hash = original.second,
                            status = "checking",
                            message = "Проверяется VK Хеш ${original.first}"
                        )
                        parsed[original.first] = result
                        onUpdate(original.first, result)
                    }

                    line.startsWith("CAPTCHA_SOLVE|") -> {
                        val payload = line.substringAfter("CAPTCHA_SOLVE|")
                        val parts = payload.split("|", limit = 3)
                        val slot = currentSlot
                        val mode = parts.getOrNull(0).orEmpty()
                        val redirectUri = parts.getOrNull(1).orEmpty()
                        val sessionToken = parts.getOrNull(2).orEmpty()
                        val currentHash = slot?.let { parsed[it]?.hash }
                            ?: hashes.firstOrNull { it.first == slot }?.second
                            ?: ""

                        if (slot != null && redirectUri.isNotBlank() && sessionToken.isNotBlank()) {
                            val solving = HashCheckResult(
                                hash = currentHash,
                                status = "solving_captcha",
                                message = "VK запросил капчу, решаем…"
                            )
                            parsed[slot] = solving
                            onUpdate(slot, solving)

                            val captchaResult = runCatching {
                                val normalized = mode.lowercase().trim()
                                if (normalized == "manual") {
                                    ManlCaptchaWebViewManager.solveCaptchaAsync(
                                        context,
                                        redirectUri,
                                        sessionToken
                                    )
                                } else {
                                    CaptchaWebViewManager.solveCaptchaAsync(
                                        redirectUri,
                                        sessionToken
                                    )
                                }
                            }.getOrElse { err ->
                                "error:${err.message ?: "captcha failed"}"
                            }

                            writer.write("CAPTCHA_RESULT|$captchaResult\n")
                            writer.flush()
                        } else {
                            writer.write("CAPTCHA_RESULT|error:invalid CAPTCHA_SOLVE format\n")
                            writer.flush()
                        }
                    }

                    line.startsWith("HASH_CHECK|") -> {
                        val parts = line.split("|", limit = 5)
                        if (parts.size >= 5) {
                            val order = parts[1].toIntOrNull() ?: continue
                            val original = byOrder[order] ?: continue
                            currentSlot = null
                            val result = HashCheckResult(
                                hash = parts[2],
                                status = parts[3],
                                message = parts[4].ifBlank { defaultMessage(parts[3]) }
                            )
                            parsed[original.first] = result
                            onUpdate(original.first, result)
                        }
                    }
                }
            }

            process.waitFor()
        } catch (_: Exception) {
            cleanup()
        } finally {
            killer.interrupt()
            cleanup()
        }

        hashes.associate { (slot, hash) ->
            val fallbackStatus = if (timedOut) "network" else "error"
            val fallbackMessage = if (timedOut) {
                "Проверка не завершилась за отведённое время"
            } else {
                "Нет ответа проверки"
            }
            slot to (parsed[slot] ?: HashCheckResult(hash, fallbackStatus, fallbackMessage))
        }
    }

    private fun defaultMessage(status: String): String = when (status) {
        "ok" -> "TURN доступен"
        "dead" -> "Звонок закрыт или хеш недействителен"
        "captcha" -> "Нужна капча VK"
        "limited" -> "VK ограничил частоту запросов"
        "network" -> "Не достучались по сети/DNS"
        "cancelled" -> "Остановлено"
        else -> "Диагностика не удалась"
    }
}
