package com.glebsbp.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeployManager {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isDeploying = MutableStateFlow(false)
    val deployProgress = MutableStateFlow(0f)
    val currentStep = MutableStateFlow("")

    @Volatile
    var activeSession: com.jcraft.jsch.Session? = null
    private var deployStartTime = 0L
    private var errorsFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** Вызвать один раз при старте приложения */
    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        errorsFile = File(dir, "errors.log")
    }

    /** Записать ошибку в файл (потокобезопасно) и во вкладку «Логи» */
    @Synchronized
    fun writeError(msg: String) {
        TunnelManager.addDeployErrorLog(msg)
        val file = errorsFile ?: return
        try {
            val timestamp = dateFormat.format(Date())
            file.appendText("[$timestamp] $msg\n")
            // Ротация: если файл > 500 КБ, обрезаем до последних 200 КБ
            if (file.length() > 500_000) {
                val text = file.readText()
                file.writeText(text.takeLast(200_000))
            }
        } catch (_: Exception) { }
    }

    fun startDeploy() {
        // Автосброс зависшего деплоя > 30 минут
        if (isDeploying.value && deployStartTime > 0 &&
            System.currentTimeMillis() - deployStartTime > 30 * 60 * 1000) {
            writeError("Автосброс: предыдущий деплой завис >30 мин")
            forceReset()
        }
        isDeploying.value = true
        deployStartTime = System.currentTimeMillis()
        deployProgress.value = 0f
        currentStep.value = "Инициализация..."
        TunnelManager.addDeployLog("Старт установки…")
    }

    fun stopDeploy(result: String = "") {
        isDeploying.value = false
        deployStartTime = 0L
        if (result.isNotBlank() && !result.equals("success", ignoreCase = true)) {
            if (result.startsWith("error", ignoreCase = true) ||
                result.startsWith("Ошибка", ignoreCase = true) ||
                result.startsWith("Нужна", ignoreCase = true)
            ) {
                TunnelManager.addDeployErrorLog(result)
            } else {
                TunnelManager.addDeployLog(result)
            }
        }
        val session = activeSession
        activeSession = null
        try { session?.disconnect() } catch (_: Exception) {}
    }

    /** Принудительный сброс — для восстановления из любого состояния */
    fun forceReset() {
        val session = activeSession
        activeSession = null
        try { session?.disconnect() } catch (_: Exception) {}
        isDeploying.value = false
        deployStartTime = 0L
        deployProgress.value = 0f
        currentStep.value = ""
    }

    fun updateProgress(progress: Float, step: String) {
        deployProgress.value = progress
        currentStep.value = step
        if (step.isNotBlank()) {
            TunnelManager.addDeployLog(step)
        }
    }
}
