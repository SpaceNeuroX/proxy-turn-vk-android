package com.wdtt.client.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.wdtt.client.TunnelService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.wdtt.client.DeployManager
import com.wdtt.client.ManagedServer
import com.wdtt.client.ServersStore
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.WDTTColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Properties

private const val CMD_TIMEOUT = 900000L // 15 minutes

private data class DeployResult(
    val success: Boolean,
    val adminApiToken: String = "",
    val adminCertPin: String = "",
)

private fun generateAdminApiToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Экран деплоя для ОДНОГО сервера — встраивается в ServersTab как
 * ServerDeploy(serverId). initialServerId == null означает "новый сервер":
 * форма стартует пустой (либо с легаси-полями SettingsStore, пока список
 * ServersStore пуст), а после первого успешного "Сохранить как сервер"
 * получает свой id. initialServerId != null сразу подгружает поля этого
 * сервера в форму (см. LaunchedEffect(initialServerId, savedServers) ниже).
 * Мульти-деплой на несколько серверов сюда не входит — он живёт на
 * ServerListScreen (ServersTab.kt) через отдельный performMultiDeploy().
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeployScreen(initialServerId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val serversStore = remember { ServersStore(context) }

    LaunchedEffect(Unit) { DeployManager.init(context) }
    LaunchedEffect(Unit) { serversStore.migrateLegacyServerIfNeeded() }

    val savedServers by serversStore.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    // Текущий редактируемый сервер из списка (null = «новый сервер», данные
    // формы ниже берутся из старых одиночных SettingsStore.deploy* полей —
    // сохраняет обратную совместимость, пока список пуст/ещё не мигрировал).
    var selectedServerId by rememberSaveable { mutableStateOf(initialServerId) }
    var loadedInitialServer by rememberSaveable { mutableStateOf(false) }
    var showServerNameDialog by remember { mutableStateOf(false) }

    val savedIp by settingsStore.deployIp.collectAsStateWithLifecycle(initialValue = "")
    val savedLogin by settingsStore.deployLogin.collectAsStateWithLifecycle(initialValue = "")
    val savedPassword by settingsStore.deployPassword.collectAsStateWithLifecycle(initialValue = "")
    val savedSshUseKey by settingsStore.deploySshUseKey.collectAsStateWithLifecycle(initialValue = false)
    val savedSshPrivateKey by settingsStore.deploySshPrivateKey.collectAsStateWithLifecycle(initialValue = "")
    val savedSshKeyPassphrase by settingsStore.deploySshKeyPassphrase.collectAsStateWithLifecycle(initialValue = "")
    val savedSshKeyName by settingsStore.deploySshKeyName.collectAsStateWithLifecycle(initialValue = "")
    val savedDns1 by settingsStore.deployDns1.collectAsStateWithLifecycle(initialValue = "1.1.1.1")
    val savedDns2 by settingsStore.deployDns2.collectAsStateWithLifecycle(initialValue = "1.0.0.1")

    var ip by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var sshUseKey by rememberSaveable { mutableStateOf(false) }
    var sshKeyPassphrase by remember { mutableStateOf("") }
    var dns1 by remember { mutableStateOf("1.1.1.1") }
    var dns2 by remember { mutableStateOf("1.0.0.1") }

    val flowMainPass by settingsStore.deployMainPassword.collectAsStateWithLifecycle(initialValue = "")
    val flowAdminId by settingsStore.deployAdminId.collectAsStateWithLifecycle(initialValue = "")
    val flowBotToken by settingsStore.deployBotToken.collectAsStateWithLifecycle(initialValue = "")
    val flowSshPort by settingsStore.deploySshPort.collectAsStateWithLifecycle(initialValue = "22")
    val flowManualPorts by settingsStore.manualPortsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val flowServerDtlsPort by settingsStore.serverDtlsPort.collectAsStateWithLifecycle(initialValue = 56000)
    val flowServerWgPort by settingsStore.serverWgPort.collectAsStateWithLifecycle(initialValue = 56001)
    val savedServerDirectPort by settingsStore.serverDirectPort.collectAsStateWithLifecycle(initialValue = 56002)
    val savedServerRawPort by settingsStore.serverRawPort.collectAsStateWithLifecycle(initialValue = 56003)

    // Локальный (не Flow) state для полей "секретов" формы — как ip/login/
    // password выше. Раньше currentFormAsServer/автосохранение читали эти
    // значения напрямую из settingsStore.*.collectAsStateWithLifecycle(...)
    // (savedMainPass и т.п.), а loadServerIntoForm() пишет их в SettingsStore
    // АСИНХРОННО (внутри scope.launch) — из-за этого при открытии деплоя уже
    // существующего сервера возникало окно, где ip/login уже обновились
    // синхронно, а savedMainPass ещё содержал значение ПРЕДЫДУЩЕГО открытого
    // сервера (или пустое) — и автосохранение успевало записать это неверное
    // значение обратно в ManagedServer, затирая настоящий adminPassword.
    // Теперь эти поля — обычный component state, обновляемый синхронно в
    // loadServerIntoForm(), как и остальные поля формы.
    var mainPass by remember { mutableStateOf("") }
    var adminId by remember { mutableStateOf("") }
    var botToken by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var manualPorts by remember { mutableStateOf(false) }
    var serverDtlsPort by remember { mutableIntStateOf(56000) }
    var serverWgPort by remember { mutableIntStateOf(56001) }
    var adminApiToken by remember { mutableStateOf("") }
    var adminCertPin by remember { mutableStateOf("") }

    // Легаси-фолбэк: пока список серверов пуст и форма ещё ни разу не
    // загружала конкретный ManagedServer (новый сервер с нуля) — подхватываем
    // значения из старых одиночных SettingsStore.deploy* полей, как раньше.
    LaunchedEffect(flowMainPass) { if (selectedServerId == null && flowMainPass.isNotEmpty()) mainPass = flowMainPass }
    LaunchedEffect(flowAdminId) { if (selectedServerId == null && flowAdminId.isNotEmpty()) adminId = flowAdminId }
    LaunchedEffect(flowBotToken) { if (selectedServerId == null && flowBotToken.isNotEmpty()) botToken = flowBotToken }
    LaunchedEffect(flowSshPort) { if (selectedServerId == null) sshPort = flowSshPort }
    LaunchedEffect(flowManualPorts) { if (selectedServerId == null) manualPorts = flowManualPorts }
    LaunchedEffect(flowServerDtlsPort) { if (selectedServerId == null) serverDtlsPort = flowServerDtlsPort }
    LaunchedEffect(flowServerWgPort) { if (selectedServerId == null) serverWgPort = flowServerWgPort }

    var showSecretsDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }
    var showSuccessBanner by rememberSaveable { mutableStateOf(false) }
    var successCountdown by rememberSaveable { mutableIntStateOf(5) }

    LaunchedEffect(showSuccessBanner) {
        if (showSuccessBanner) {
            while (successCountdown > 0) {
                kotlinx.coroutines.delay(1000)
                successCountdown--
            }
            showSuccessBanner = false
        }
    }

    val isDeploying by DeployManager.isDeploying.collectAsStateWithLifecycle()
    val deployProgress by DeployManager.deployProgress.collectAsStateWithLifecycle()
    val currentStep by DeployManager.currentStep.collectAsStateWithLifecycle()

    LaunchedEffect(savedIp) { if (savedIp.isNotEmpty()) ip = savedIp }
    LaunchedEffect(savedLogin) { if (savedLogin.isNotEmpty()) login = savedLogin }
    LaunchedEffect(savedPassword) { if (savedPassword.isNotEmpty()) password = savedPassword }
    LaunchedEffect(savedSshUseKey) { sshUseKey = savedSshUseKey }
    LaunchedEffect(savedSshKeyPassphrase) { if (savedSshKeyPassphrase.isNotEmpty()) sshKeyPassphrase = savedSshKeyPassphrase }
    LaunchedEffect(savedDns1) { if (savedDns1.isNotEmpty()) dns1 = savedDns1 }
    LaunchedEffect(savedDns2) { if (savedDns2.isNotEmpty()) dns2 = savedDns2 }
    val animatedProgress by animateFloatAsState(
        targetValue = deployProgress,
        animationSpec = tween(durationMillis = 1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "progress"
    )

    fun persistDeployFields() {
        scope.launch {
            settingsStore.saveDeploy(
                ip = ip,
                login = login,
                pass = password,
                sshPort = sshPort,
                dns1 = dns1,
                dns2 = dns2,
                useSshKey = sshUseKey,
                keyPassphrase = sshKeyPassphrase,
            )
        }
    }

    // Собрать текущее состояние формы в ManagedServer (для сохранения в список
    // серверов) — использует то же имя, что уже есть у сервера, либо IP как
    // имя по умолчанию для нового.
    fun currentFormAsServer(id: String?, name: String): ManagedServer = ManagedServer(
        id = id ?: "",
        name = name,
        ip = ip,
        sshLogin = login,
        sshPassword = password,
        sshUseKey = sshUseKey,
        sshPrivateKey = savedSshPrivateKey,
        sshKeyPassphrase = sshKeyPassphrase,
        sshKeyName = savedSshKeyName,
        sshPort = sshPort,
        dns1 = dns1,
        dns2 = dns2,
        adminPassword = mainPass,
        adminApiToken = adminApiToken,
        adminCertPin = adminCertPin,
        dtlsPort = serverDtlsPort,
        wgPort = serverWgPort,
        manualPortsEnabled = manualPorts,
    )

    fun loadServerIntoForm(server: ManagedServer) {
        // Всё синхронно — включая "секретные" поля (adminPassword/порты),
        // которые раньше уходили только в SettingsStore асинхронно и на кадр
        // отставали от ip/login, вызывая гонку с автосохранением (см.
        // комментарий у объявления mainPass/adminId/... выше).
        selectedServerId = server.id
        ip = server.ip
        login = server.sshLogin
        password = server.sshPassword
        sshUseKey = server.sshUseKey
        sshKeyPassphrase = server.sshKeyPassphrase
        dns1 = server.dns1
        dns2 = server.dns2
        mainPass = server.adminPassword
        adminApiToken = server.adminApiToken
        adminCertPin = server.adminCertPin
        sshPort = server.sshPort
        manualPorts = server.manualPortsEnabled
        serverDtlsPort = server.dtlsPort
        serverWgPort = server.wgPort
        scope.launch {
            settingsStore.saveDeploy(
                ip = server.ip,
                login = server.sshLogin,
                pass = server.sshPassword,
                sshPort = server.sshPort,
                dns1 = server.dns1,
                dns2 = server.dns2,
                useSshKey = server.sshUseKey,
                keyPassphrase = server.sshKeyPassphrase,
            )
            if (server.sshPrivateKey.isNotBlank()) {
                settingsStore.saveDeploySshPrivateKey(server.sshPrivateKey, server.sshKeyName)
            }
            settingsStore.saveDeploySecrets(server.adminPassword, adminId, botToken, server.sshPort)
            settingsStore.saveManualPortsEnabled(server.manualPortsEnabled)
            settingsStore.savePorts(server.dtlsPort, server.wgPort, settingsStore.listenPort.first())
        }
    }

    val sshKeyPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || isDeploying) return@rememberLauncherForActivityResult
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                Toast.makeText(context, "Не удалось прочитать файл ключа", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val normalized = content.trim()
            val looksLikePrivateKey =
                normalized.contains("BEGIN OPENSSH PRIVATE KEY") ||
                    normalized.contains("BEGIN RSA PRIVATE KEY") ||
                    normalized.contains("BEGIN EC PRIVATE KEY") ||
                    normalized.contains("BEGIN PRIVATE KEY") ||
                    (normalized.contains("PRIVATE KEY") && normalized.contains("BEGIN"))
            if (!looksLikePrivateKey) {
                Toast.makeText(context, "Нужен приватный ключ (не .pub). OpenSSH или PEM.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "ssh-key"
            val keyName = rawName
                .substringAfterLast(':')
                .substringAfterLast('%')
                .replace("%2F", "/", ignoreCase = true)
                .substringAfterLast('/')
                .ifBlank { "ssh-key" }
            runCatching {
                settingsStore.saveDeploySshPrivateKey(normalized, keyName)
            }.onFailure {
                Toast.makeText(context, "Не удалось сохранить ключ: ${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            sshUseKey = true
            Toast.makeText(context, "SSH-ключ сохранён: $keyName", Toast.LENGTH_SHORT).show()
        }
    }

    val hasSshCredentials = if (sshUseKey) {
        savedSshPrivateKey.isNotBlank()
    } else {
        password.isNotBlank()
    }

    val scrollState = rememberScrollState()

    // Автозагрузка полей формы для указанного сервера при первом появлении
    // экрана (переход из ServerListScreen с конкретным serverId). Ждём, пока
    // savedServers действительно содержит нужный сервер (Flow из DataStore
    // может дойти на кадр позже первого композирования), и грузим только один
    // раз, чтобы не затирать правки пользователя при последующих рекомпозициях.
    LaunchedEffect(initialServerId, savedServers) {
        if (!loadedInitialServer && initialServerId != null) {
            val match = savedServers.find { it.id == initialServerId }
            if (match != null) {
                loadServerIntoForm(match)
                loadedInitialServer = true
            }
        }
    }

    // Автосохранение изменений формы обратно в ServersStore — но только для
    // УЖЕ существующего сервера (selectedServerId != null), и только после
    // того как loadServerIntoForm() отработал (иначе загрузка сама вызвала
    // бы немедленную перезапись тем же значением — не проблема сама по себе,
    // но лишний DataStore.edit на каждое поле при открытии экрана). Раньше
    // поля вроде "Пароль владельца" (DeploySecretsDialog) писались только в
    // legacy SettingsStore.deploy* и никогда не попадали в ManagedServer,
    // если пользователь не нажимал отдельную кнопку "Сохранить как сервер" —
    // из-за этого сервер выглядел заполненным в списке (по ip/имени), но
    // "Доступы" считали adminPassword пустым. Теперь любое изменение любого
    // поля формы для существующего сервера сразу отражается в списке.
    LaunchedEffect(
        selectedServerId, loadedInitialServer,
        ip, login, password, sshUseKey, sshKeyPassphrase, dns1, dns2,
        mainPass, manualPorts, serverDtlsPort, serverWgPort, sshPort,
    ) {
        val id = selectedServerId
        if (id != null && loadedInitialServer) {
            val existingName = savedServers.find { it.id == id }?.name ?: ip
            serversStore.updateServer(currentFormAsServer(id, existingName))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = !isDeploying) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Управление сервером",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { showServerNameDialog = true },
                enabled = !isDeploying && ip.isNotBlank()
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = "Сохранить",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isDeploying) {
            AppSectionCard(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentStep,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${(animatedProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }

        if (showSuccessBanner) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = WDTTColors.connected.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, WDTTColors.connected.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = WDTTColors.connected)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Деплой успешно завершен ($successCountdown)",
                        color = WDTTColors.connected,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        val deploySecretsMissing = mainPass.isBlank()
        OutlinedButton(
            onClick = { showSecretsDialog = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (deploySecretsMissing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (deploySecretsMissing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            ),
            border = BorderStroke(
                1.dp,
                if (deploySecretsMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
        ) {
            Icon(Icons.Default.Key, null, Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                when {
                    deploySecretsMissing && manualPorts -> "Секреты — укажите пароль WDTT, порты"
                    deploySecretsMissing -> "Секреты — нужен пароль WDTT"
                    manualPorts -> "Секреты (BOT, Пароли, Порты)"
                    else -> "Секреты (BOT, Пароли)"
                },
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (ip.isBlank() || !hasSshCredentials || mainPass.isBlank()) return@Button
                    val effectiveLogin = if (login.isBlank()) "root" else login
                    val effectiveDtlsPort = if (manualPorts) serverDtlsPort.coerceIn(1, 65535) else 56000
                    val effectiveWgPort = if (manualPorts) serverWgPort.coerceIn(1, 65535) else 56001
                    // Direct/Raw включаются на сервере ВСЕГДА, а не только когда совпадают с
                    // режимом, выбранным сейчас на этом телефоне — иначе один деплой гасит
                    // порт другого транспорта, и переключение клиента между режимами требует
                    // повторного деплоя. DTLS/WG уже так работают (см. effectiveDtlsPort/
                    // effectiveWgPort выше — они не завязаны на connectionMode).
                    val effectiveDirectPort = savedServerDirectPort.coerceIn(1, 65535)
                    val effectiveRawPort = savedServerRawPort.coerceIn(1, 65535)
                    val appContext = context.applicationContext
                    val sshAuth = buildSshAuth(
                        useKey = sshUseKey,
                        password = password,
                        privateKey = savedSshPrivateKey,
                        keyPassphrase = sshKeyPassphrase,
                    )
                    DeployManager.scope.launch {
                        try {
                            DeployManager.startDeploy()
                            val intent = Intent(appContext, TunnelService::class.java).apply { action = "DEPLOY_START" }
                            if (Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(intent)
                            else appContext.startService(intent)

                            val deployToken = adminApiToken.ifBlank { generateAdminApiToken() }
                            val result = performDeploy(
                                context = appContext,
                                host = ip,
                                user = effectiveLogin,
                                port = sshPort.toIntOrNull() ?: 22,
                                sshAuth = sshAuth,
                                mainPass = mainPass,
                                adminId = adminId,
                                botToken = botToken,
                                adminApiToken = deployToken,
                                dtlsPort = effectiveDtlsPort,
                                wgPort = effectiveWgPort,
                                directPort = effectiveDirectPort,
                                rawPort = effectiveRawPort,
                                dns1 = dns1,
                                dns2 = dns2,
                                onProgress = { p, s -> DeployManager.updateProgress(p, s) }
                            )
                            if (result.success) {
                                adminApiToken = result.adminApiToken
                                adminCertPin = result.adminCertPin
                                val savedId = selectedServerId
                                if (savedId != null) {
                                    val existing = serversStore.getServerOnce(savedId)
                                    if (existing != null) {
                                        serversStore.updateServer(
                                            existing.copy(
                                                adminApiToken = result.adminApiToken,
                                                adminCertPin = result.adminCertPin,
                                            )
                                        )
                                    }
                                } else {
                                    val saved = serversStore.addServer(
                                        currentFormAsServer(null, ip).copy(
                                            adminApiToken = result.adminApiToken,
                                            adminCertPin = result.adminCertPin,
                                        )
                                    )
                                    selectedServerId = saved.id
                                }
                                successCountdown = 5
                                showSuccessBanner = true
                            }
                        } finally {
                            try { appContext.startService(Intent(appContext, TunnelService::class.java).apply { action = "DEPLOY_STOP" }) } catch (_: Exception) {}
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                enabled = !isDeploying && ip.isNotBlank() && hasSshCredentials && mainPass.isNotBlank()
            ) {
                if (isDeploying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isDeploying) "Установка..." else "Установить", fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    if (ip.isBlank() || !hasSshCredentials) return@Button
                    showUninstallDialog = true
                },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                enabled = !isDeploying && ip.isNotBlank() && hasSshCredentials
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Удалить", fontWeight = FontWeight.Bold)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // ═══ Поля ввода в Card ═══
        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = {
                        ip = it.filter { c -> !c.isWhitespace() }
                        persistDeployFields()
                    },
                    label = { Text("IP / домен") },
                    placeholder = { Text("1.2.3.4") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isDeploying,
                )
                OutlinedTextField(
                    value = login,
                    onValueChange = {
                        login = it.filter { c -> !c.isWhitespace() }
                        persistDeployFields()
                    },
                    label = { Text("Логин") },
                    placeholder = { Text("root") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isDeploying,
                )
            }

            Text(
                "Вход на сервер",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (sshUseKey && !isDeploying) {
                            sshUseKey = false
                            persistDeployFields()
                        }
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isDeploying,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (!sshUseKey) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        contentColor = if (!sshUseKey) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (!sshUseKey) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                    )
                ) {
                    Text("Пароль", fontWeight = if (!sshUseKey) FontWeight.Bold else FontWeight.Medium)
                }
                OutlinedButton(
                    onClick = {
                        if (!sshUseKey && !isDeploying) {
                            sshUseKey = true
                            persistDeployFields()
                        }
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isDeploying,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (sshUseKey) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        contentColor = if (sshUseKey) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (sshUseKey) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                    )
                ) {
                    Text("SSH-ключ", fontWeight = if (sshUseKey) FontWeight.Bold else FontWeight.Medium)
                }
            }

            if (sshUseKey) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { sshKeyPickerLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isDeploying,
                    ) {
                        Icon(Icons.Default.Key, null, Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (savedSshPrivateKey.isNotBlank()) "Сменить ключ" else "Выбрать ключ",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (savedSshPrivateKey.isNotBlank()) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    settingsStore.clearDeploySshPrivateKey()
                                    Toast.makeText(context, "SSH-ключ удалён", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isDeploying,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить ключ")
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (savedSshPrivateKey.isNotBlank() || savedSshKeyName.isNotBlank()) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (savedSshPrivateKey.isNotBlank()) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Key
                            },
                            contentDescription = null,
                            tint = if (savedSshPrivateKey.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                savedSshPrivateKey.isNotBlank() ->
                                    "Ключ загружен: ${savedSshKeyName.ifBlank { "private key" }}"
                                savedSshKeyName.isNotBlank() ->
                                    "Ключ «$savedSshKeyName» сохранён с ошибкой — выберите файл снова"
                                else ->
                                    "Ключ ещё не выбран (нужен приватный файл, не .pub)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (savedSshPrivateKey.isNotBlank()) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = sshKeyPassphrase,
                    onValueChange = {
                        sshKeyPassphrase = it
                        persistDeployFields()
                    },
                    label = { Text("Пароль от ключа") },
                    placeholder = { Text("если ключ защищён паролем") },
                    supportingText = {
                        Text("Нужен только если при создании SSH-ключа вы задавали пароль")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isDeploying,
                )
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it.filter { c -> !c.isWhitespace() }
                        persistDeployFields()
                    },
                    label = { Text("Пароль SSH") },
                    placeholder = { Text("password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isDeploying,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = dns1,
                    onValueChange = {
                        dns1 = it.filter { c -> !c.isWhitespace() }
                        persistDeployFields()
                    },
                    label = { Text("DNS 1") },
                    placeholder = { Text("1.1.1.1") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isDeploying,
                )
                OutlinedTextField(
                    value = dns2,
                    onValueChange = {
                        dns2 = it.filter { c -> !c.isWhitespace() }
                        persistDeployFields()
                    },
                    label = { Text("DNS 2") },
                    placeholder = { Text("1.0.0.1") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isDeploying,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Ручное управление портами",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = manualPorts,
                    enabled = !isDeploying,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsStore.saveManualPortsEnabled(enabled) }
                    }
                )
            }
        }

        }

        if (showSecretsDialog) {
            DeploySecretsDialog(
                settingsStore = settingsStore,
                initialMainPass = mainPass,
                initialAdminId = adminId,
                initialBotToken = botToken,
                initialSshPort = sshPort,
                manualPortsEnabled = manualPorts,
                initialServerDtlsPort = serverDtlsPort.toString(),
                initialServerWgPort = serverWgPort.toString(),
                onSaved = { newMainPass, newAdminId, newBotToken, newSshPort, newDtlsPort, newWgPort ->
                    mainPass = newMainPass
                    adminId = newAdminId
                    botToken = newBotToken
                    sshPort = newSshPort
                    serverDtlsPort = newDtlsPort.toIntOrNull() ?: serverDtlsPort
                    serverWgPort = newWgPort.toIntOrNull() ?: serverWgPort
                },
                onDismiss = { showSecretsDialog = false }
            )
        }

        if (showUninstallDialog) {
            UninstallConfirmDialog(
                onDismiss = { showUninstallDialog = false },
                onConfirm = {
                    showUninstallDialog = false
                    val effectiveLogin = if (login.isBlank()) "root" else login
                    val effectiveDtlsPort = if (manualPorts) serverDtlsPort.coerceIn(1, 65535) else 56000
                    val effectiveWgPort = if (manualPorts) serverWgPort.coerceIn(1, 65535) else 56001
                    val sshAuth = buildSshAuth(
                        useKey = sshUseKey,
                        password = password,
                        privateKey = savedSshPrivateKey,
                        keyPassphrase = sshKeyPassphrase,
                    )
                    DeployManager.scope.launch {
                        try {
                            DeployManager.startDeploy()
                            performUninstall(
                                host = ip,
                                user = effectiveLogin,
                                port = sshPort.toIntOrNull() ?: 22,
                                sshAuth = sshAuth,
                                dtlsPort = effectiveDtlsPort,
                                wgPort = effectiveWgPort,
                                onProgress = { p, s -> DeployManager.updateProgress(p, s) }
                            )
                        } catch (_: Exception) {}
                    }
                }
            )
        }

        if (showServerNameDialog) {
            SaveServerNameDialog(
                initialName = savedServers.find { it.id == selectedServerId }?.name ?: ip,
                onDismiss = { showServerNameDialog = false },
                onConfirm = { name ->
                    showServerNameDialog = false
                    scope.launch {
                        val idToSave = selectedServerId
                        val saved = if (idToSave != null) {
                            serversStore.updateServer(currentFormAsServer(idToSave, name))
                            currentFormAsServer(idToSave, name)
                        } else {
                            serversStore.addServer(currentFormAsServer(null, name))
                        }
                        selectedServerId = saved.id
                        Toast.makeText(context, "Сервер «$name» сохранён", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

    }
}

internal sealed class DeployOutcome {
    object InProgress : DeployOutcome()
    object Success : DeployOutcome()
    data class Failed(val message: String) : DeployOutcome()
}

/**
 * showLiveProgress — true для сервера, который прямо сейчас разворачивается
 * (см. activeDeployingServerId в ServersTab.kt): вместо голого спиннера
 * показывает тот же детальный прогресс (шаг + % + полоса), что и на экране
 * одиночного деплоя, читая общий DeployManager.deployProgress/currentStep —
 * они относятся к текущему серверу, потому что мультидеплой строго
 * последовательный (см. performMultiDeploy).
 */
@Composable
internal fun MultiDeployStatusRow(name: String, outcome: DeployOutcome, showLiveProgress: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (outcome) {
                is DeployOutcome.InProgress -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                is DeployOutcome.Success -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = WDTTColors.connected, modifier = Modifier.size(16.dp))
                is DeployOutcome.Failed -> Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            }
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (outcome is DeployOutcome.Failed) {
                Text(
                    outcome.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (outcome is DeployOutcome.InProgress && showLiveProgress) {
                val progress by DeployManager.deployProgress.collectAsStateWithLifecycle()
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (outcome is DeployOutcome.InProgress && showLiveProgress) {
            val progress by DeployManager.deployProgress.collectAsStateWithLifecycle()
            val step by DeployManager.currentStep.collectAsStateWithLifecycle()
            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(durationMillis = 400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "multi_deploy_progress",
            )
            Text(
                step,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveServerNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Название сервера", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Например, Frankfurt #1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = { onConfirm(name.trim().ifBlank { initialName }) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                    ) { Text("Сохранить") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MultiDeployConfirmDialog(
    servers: List<ManagedServer>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Деплой на несколько серверов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Установка будет выполнена по очереди на:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    servers.forEach { server ->
                        Text(
                            "• ${server.name.ifBlank { server.ip }} (${server.ip})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
                    ) {
                        Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Начать")
                    }
                }
            }
        }
    }
}

// ==================== SSH ====================

private data class SshAuth(
    val password: String,
    val privateKey: String,
    val keyPassphrase: String,
) {
    val usesKey: Boolean get() = privateKey.isNotBlank()
    val sudoPassword: String get() = password
}

private fun buildSshAuth(
    useKey: Boolean,
    password: String,
    privateKey: String,
    keyPassphrase: String,
): SshAuth {
    return if (useKey) {
        SshAuth(password = password, privateKey = privateKey, keyPassphrase = keyPassphrase)
    } else {
        SshAuth(password = password, privateKey = "", keyPassphrase = "")
    }
}

private class SSHClient(private val session: Session, private val pass: String) {

    fun exec(command: String, timeout: Long = CMD_TIMEOUT): String {
        if (!session.isConnected) {
            DeployManager.writeError("SSH exec: сессия разорвана перед командой: ${command.take(80)}")
            return "error: session is down"
        }

        var channel: ChannelExec? = null
        val result = StringBuilder()

        return try {
            channel = session.openChannel("exec") as ChannelExec
            val cmd = if (command.contains("sudo") && !command.contains("sudo -S")) {
                command.replace("sudo ", "sudo -S ")
            } else command

            channel.setCommand(cmd)
            val outStream = channel.outputStream
            val input = channel.inputStream
            val err = channel.errStream
            channel.connect(15000)

            if (cmd.contains("sudo -S")) {
                outStream.write("$pass\n".toByteArray())
                outStream.flush()
            }

            val reader = input.bufferedReader()
            val errReader = err.bufferedReader()
            val startTime = System.currentTimeMillis()
            val progressRegex = Regex("^WDTT_PROGRESS\\|(\\d+\\.?\\d*)\\|(.+)$")

            while (!channel.isClosed || reader.ready() || errReader.ready()) {
                if (System.currentTimeMillis() - startTime > timeout) {
                    DeployManager.writeError("SSH timeout (${timeout/1000}s): ${command.take(80)}")
                    try { channel.disconnect() } catch (_: Exception) {}
                    return "error: timeout"
                }

                if (reader.ready()) {
                    val line = reader.readLine()
                    if (line != null) {
                        val match = progressRegex.find(line.trim())
                        if (match != null) {
                            val p = match.groupValues[1].toFloatOrNull() ?: 0f
                            DeployManager.updateProgress(p, match.groupValues[2])
                        } else if (!line.contains("WDTT_PROGRESS")) {
                            val clean = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
                            result.appendLine(clean)
                            if (clean.contains("[✗]") || clean.contains("FAIL") ||
                                (clean.contains("error", true) && !clean.contains("2>/dev/null"))) {
                                DeployManager.writeError("REMOTE: $clean")
                                TunnelManager.addDeployErrorLog("REMOTE: $clean")
                            }
                        }
                    }
                }
                if (errReader.ready()) {
                    val line = errReader.readLine()
                    if (line != null && !line.contains("password for")) {
                        val clean = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
                        result.appendLine(clean)
                        if (clean.isNotBlank() && !clean.startsWith("Warning:")) {
                            DeployManager.writeError("STDERR: $clean")
                            TunnelManager.addDeployErrorLog("STDERR: $clean")
                        }
                    }
                }
                if (!reader.ready() && !errReader.ready()) Thread.sleep(100)
            }

            result.toString()
        } catch (e: Exception) {
            DeployManager.writeError("SSH exec error: ${e.message} | cmd: ${command.take(80)}")
            TunnelManager.addDeployErrorLog("SSH exec error: ${e.message}")
            "error: ${e.message}"
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
        }
    }

    fun upload(localFile: File, remotePath: String) {
        if (!session.isConnected) {
            DeployManager.writeError("SSH upload: сессия разорвана")
            throw Exception("Session is down")
        }
        var sftp: ChannelSftp? = null
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15000)
            sftp.put(localFile.absolutePath, remotePath)
        } catch (e: Exception) {
            DeployManager.writeError("SFTP upload error: ${e.message} | file: ${localFile.name}")
            throw e
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) {}
        }
    }
}

private fun createSSHSession(host: String, user: String, port: Int, auth: SshAuth): Session {
    val authMode = if (auth.usesKey) "SSH-ключ" else "пароль"
    TunnelManager.addDeployLog("SSH $user@$host:$port ($authMode)…")
    try {
        val jsch = JSch()
        if (auth.usesKey) {
            val keyBytes = auth.privateKey.toByteArray(Charsets.UTF_8)
            val passphraseBytes = auth.keyPassphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity("deploy-key", keyBytes, null, passphraseBytes)
        }
        val session = jsch.getSession(user, host, port)
        if (!auth.usesKey) {
            session.setPassword(auth.password)
        }
        session.setConfig(Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("ServerAliveInterval", "10")
            put("ServerAliveCountMax", "6")
            put("ConnectTimeout", "15000")
            put(
                "PreferredAuthentications",
                if (auth.usesKey) "publickey" else "password,keyboard-interactive,publickey"
            )
        })
        session.connect(20000)
        TunnelManager.addDeployLog("SSH подключено")
        return session
    } catch (e: Exception) {
        val msg = e.message?.take(160) ?: e.javaClass.simpleName
        DeployManager.writeError("SSH не удалось: $msg")
        throw e
    }
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun rootCommand(command: String): String {
    val quoted = shellQuote(command)
    return "if command -v sudo >/dev/null 2>&1; then sudo bash -c $quoted; " +
        "elif [ \"\$(id -u)\" = \"0\" ]; then bash -c $quoted; " +
        "else echo 'error: root privileges required and sudo not found'; exit 1; fi"
}

private fun File.containsBinaryToken(token: String): Boolean {
    val data = readBytes()
    val needle = token.toByteArray()
    if (needle.isEmpty() || data.size < needle.size) return false
    for (i in 0..data.size - needle.size) {
        var matched = true
        for (j in needle.indices) {
            if (data[i + j] != needle[j]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}

private fun isUnsafeLegacyServerAsset(serverFile: File): Boolean {
    return serverFile.containsBinaryToken("/etc/wireguard") ||
        (serverFile.containsBinaryToken("wg0") && !serverFile.containsBinaryToken("wdtt0"))
}

// ==================== Deploy ====================

private suspend fun performDeploy(
    context: Context,
    host: String, user: String, port: Int,
    sshAuth: SshAuth,
    mainPass: String, adminId: String, botToken: String,
    adminApiToken: String,
    dtlsPort: Int, wgPort: Int, directPort: Int?, rawPort: Int?, dns1: String, dns2: String,
    onProgress: (Float, String) -> Unit
): DeployResult = withContext(Dispatchers.IO) {
    var session: Session? = null
    val adminTokenFile = File(context.cacheDir, "wdtt-admin.token")
    val mainPasswordFile = File(context.cacheDir, "wdtt-main.password")
    val botTokenFile = File(context.cacheDir, "wdtt-bot.token")
    try {
        onProgress(0.02f, "Подключение...")
        session = createSSHSession(host, user, port, sshAuth)
        DeployManager.activeSession = session
        val ssh = SSHClient(session, sshAuth.sudoPassword)

        onProgress(0.05f, "Подготовка файлов...")
        val dnsServers = "${if(dns1.isNotBlank()) dns1 else "1.1.1.1"}${if(dns2.isNotBlank()) ",$dns2" else ""}"

        val scriptFile = File(context.cacheDir, "deploy.sh")
        val serverFile = File(context.cacheDir, "server")
        try {
            context.assets.open("deploy.sh").use { inp -> FileOutputStream(scriptFile).use { out -> inp.copyTo(out) } }
            context.assets.open("server").use { inp -> FileOutputStream(serverFile).use { out -> inp.copyTo(out) } }
            FileOutputStream(adminTokenFile).use { it.write(adminApiToken.toByteArray(Charsets.UTF_8)) }
            FileOutputStream(mainPasswordFile).use { it.write(mainPass.toByteArray(Charsets.UTF_8)) }
            FileOutputStream(botTokenFile).use { it.write(botToken.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            DeployManager.writeError("Assets extraction failed: ${e.message}")
            DeployManager.stopDeploy("Ошибка: файлы не найдены в assets")
            return@withContext DeployResult(false)
        }
        if (isUnsafeLegacyServerAsset(serverFile)) {
            scriptFile.delete()
            serverFile.delete()
            DeployManager.writeError("Unsafe legacy server asset: найдено wg0 или /etc/wireguard. Нужна пересборка server под wdtt0 и /etc/wdtt.")
            DeployManager.stopDeploy("Нужна пересборка server asset")
            return@withContext DeployResult(false)
        }

        onProgress(0.06f, "Загрузка на сервер...")
        ssh.upload(scriptFile, "/tmp/deploy.sh")
        ssh.upload(serverFile, "/tmp/wdtt-server")
        ssh.upload(adminTokenFile, "/tmp/wdtt-admin.token")
        ssh.upload(mainPasswordFile, "/tmp/wdtt-main.password")
        ssh.upload(botTokenFile, "/tmp/wdtt-bot.token")
        scriptFile.delete()
        serverFile.delete()
        adminTokenFile.delete()
        mainPasswordFile.delete()
        botTokenFile.delete()

        onProgress(0.08f, "Установка...")
        val directPortEnv = if (directPort != null) "WDTT_DIRECT_PORT=$directPort " else ""
        val rawPortEnv = if (rawPort != null) "WDTT_RAW_PORT=$rawPort " else ""
        val output = ssh.exec(
            rootCommand("chmod 600 /tmp/wdtt-admin.token /tmp/wdtt-main.password /tmp/wdtt-bot.token && env WDTT_ADMIN_ID=${shellQuote(adminId)} WDTT_DNS_SERVERS=${shellQuote(dnsServers)} WDTT_DTLS_PORT=$dtlsPort WDTT_WG_PORT=$wgPort WDTT_SSH_PORT=$port ${directPortEnv}${rawPortEnv}bash /tmp/deploy.sh"),
            timeout = CMD_TIMEOUT
        )
        val certPin = Regex("WDTT_ADMIN_PIN\\|(sha256/[A-Za-z0-9+/=]+)")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        if (output.contains("WDTT_DEPLOY_OK") && certPin.isNotBlank()) {
            DeployManager.stopDeploy("success")
            TunnelManager.addDeploySuccessLog("Деплой успешно завершен. Сервис активен.")
            return@withContext DeployResult(true, adminApiToken, certPin)
        } else if (output.contains("CreateTUN") && output.contains("operation not permitted")) {
            DeployManager.writeError("Server cannot create TUN: operation not permitted")
            DeployManager.stopDeploy("Сервер не смог создать TUN-интерфейс")
            return@withContext DeployResult(false)
        } else if (output.contains("error:")) {
            DeployManager.writeError("Deploy script output contains error")
            DeployManager.stopDeploy("Ошибка выполнения скрипта (см. errors.log)")
            return@withContext DeployResult(false)
        } else if (certPin.isBlank()) {
            DeployManager.writeError("Admin TLS pin not found in deploy output")
            DeployManager.stopDeploy("Ошибка настройки защищённой админ-панели")
            return@withContext DeployResult(false)
        } else {
            DeployManager.writeError("WDTT service did not become active")
            DeployManager.stopDeploy("Сервис WDTT не запустился")
            return@withContext DeployResult(false)
        }

    } catch (e: Exception) {
        DeployManager.writeError("Deploy critical: ${e.message}\n${e.stackTraceToString().take(500)}")
        DeployManager.stopDeploy("Ошибка: ${e.message?.take(100)}")
        return@withContext DeployResult(false)
    } finally {
        adminTokenFile.delete()
        mainPasswordFile.delete()
        botTokenFile.delete()
        try { session?.disconnect() } catch (_: Exception) {}
        DeployManager.activeSession = null
    }
}


/**
 * Деплой по очереди на несколько ManagedServer сразу — та же механика, что
 * раньше жила прямо в DeployTab() (кнопка "Установить на выбранные"), но
 * вынесена сюда как самостоятельная suspend-функция, чтобы ServersTab.kt мог
 * вызвать её со своего экрана списка серверов (режим множественного выбора),
 * не завязываясь на весь UI DeployTab/DeployScreen. onServerStarted/
 * onServerFinished — колбэки для обновления статус-строк в вызывающем UI.
 */
internal suspend fun performMultiDeploy(
    context: Context,
    servers: List<ManagedServer>,
    onServerStarted: (String) -> Unit,
    onServerFinished: (String, DeployOutcome) -> Unit,
) {
    // direct/raw порты — общие глобальные настройки (SettingsStore), не
    // per-server поля ManagedServer, тот же источник, что читает одиночный
    // деплой (см. effectiveDirectPort/effectiveRawPort выше в этом файле).
    // Раньше здесь жёстко передавались null — это отключало -listen-raw/
    // -listen-direct на сервере при КАЖДОМ деплое через мультисервер-режим,
    // из-за чего Raw-режим переставал работать после такого деплоя (порт
    // просто не поднимался на сервере, хотя TURN/DTLS-уровень отрабатывал
    // нормально — воркеры регистрировались, но GETCONF_RAW слать было некуда).
    val settingsStore = SettingsStore(context.applicationContext)
    val effectiveDirectPort = settingsStore.serverDirectPort.first().coerceIn(1, 65535)
    val effectiveRawPort = settingsStore.serverRawPort.first().coerceIn(1, 65535)

    for (server in servers) {
        onServerStarted(server.id)
        try {
            DeployManager.startDeploy()
            val effectiveLogin = server.sshLogin.ifBlank { "root" }
            val effectiveDtlsPort = if (server.manualPortsEnabled) server.dtlsPort.coerceIn(1, 65535) else 56000
            val effectiveWgPort = if (server.manualPortsEnabled) server.wgPort.coerceIn(1, 65535) else 56001
            val sshAuth = buildSshAuth(
                useKey = server.sshUseKey,
                password = server.sshPassword,
                privateKey = server.sshPrivateKey,
                keyPassphrase = server.sshKeyPassphrase,
            )
            val deployToken = server.adminApiToken.ifBlank { generateAdminApiToken() }
            val result = performDeploy(
                context = context,
                host = server.ip,
                user = effectiveLogin,
                port = server.sshPort.toIntOrNull() ?: 22,
                sshAuth = sshAuth,
                mainPass = server.adminPassword,
                adminId = "",
                botToken = "",
                adminApiToken = deployToken,
                dtlsPort = effectiveDtlsPort,
                wgPort = effectiveWgPort,
                directPort = effectiveDirectPort,
                rawPort = effectiveRawPort,
                dns1 = server.dns1,
                dns2 = server.dns2,
                onProgress = { p, s -> DeployManager.updateProgress(p, s) }
            )
            if (result.success) {
                ServersStore(context).updateServer(
                    server.copy(
                        adminApiToken = result.adminApiToken,
                        adminCertPin = result.adminCertPin,
                    )
                )
            }
            onServerFinished(server.id, if (result.success) DeployOutcome.Success else DeployOutcome.Failed("Ошибка деплоя"))
        } catch (e: Exception) {
            onServerFinished(server.id, DeployOutcome.Failed(e.message ?: "Ошибка"))
        }
    }
}

// ==================== Uninstall ====================

private suspend fun performUninstall(
    host: String, user: String, port: Int,
    sshAuth: SshAuth,
    dtlsPort: Int, wgPort: Int,
    onProgress: (Float, String) -> Unit
) = withContext(Dispatchers.IO) {
    var session: Session? = null
    try {
        onProgress(0.05f, "Подключение...")
        session = createSSHSession(host, user, port, sshAuth)
        DeployManager.activeSession = session
        val ssh = SSHClient(session, sshAuth.sudoPassword)

        onProgress(0.15f, "Остановка сервиса...")
        ssh.exec(
            rootCommand(
                "systemctl unmask wdtt 2>/dev/null || true; " +
                    "systemctl stop wdtt 2>/dev/null || true; " +
                    "systemctl disable wdtt 2>/dev/null || true; " +
                    "rm -f /etc/systemd/system/wdtt.service; " +
                    "systemctl daemon-reload 2>/dev/null || true"
            ),
            timeout = 15000L
        )

        onProgress(0.30f, "Удаление через deploy.sh...")
        ssh.exec(rootCommand("[ -f /tmp/deploy.sh ] && env WDTT_DTLS_PORT=$dtlsPort WDTT_WG_PORT=$wgPort WDTT_SSH_PORT=$port bash /tmp/deploy.sh uninstall 2>/dev/null || true"), timeout = 30000L)

        onProgress(0.45f, "Удаление бинарника...")
        ssh.exec(rootCommand("pkill -x wdtt-server 2>/dev/null || true; rm -f /usr/local/bin/wdtt-server"), timeout = 10000L)

        onProgress(0.60f, "Очистка firewall...")
        ssh.exec(
            rootCommand(
                "if command -v iptables >/dev/null 2>&1; then " +
                    "for i in 1 2 3 4 5; do " +
                    "for iface in $(ls /sys/class/net 2>/dev/null || true); do " +
                    "iptables -t nat -D POSTROUTING -s 10.66.0.0/16 -o \"${'$'}iface\" -m comment --comment WDTT_MANAGED -j MASQUERADE 2>/dev/null || true; " +
                    "done; " +
                    "iptables -D INPUT -p udp --dport $dtlsPort -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
                    "iptables -D INPUT -p udp --dport $wgPort -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
                    "iptables -D INPUT -p udp --dport 56000 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
                    "iptables -D INPUT -p udp --dport 56001 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
                    "iptables -D INPUT -p tcp --dport 56002 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
                    "iptables -D INPUT -p tcp --dport $port -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
                    "iptables -D INPUT -p tcp --dport 22 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
                    "iptables -D FORWARD -i wdtt0 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
                    "iptables -D FORWARD -o wdtt0 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; " +
                    "done; fi; " +
                    "if command -v nft >/dev/null 2>&1; then " +
                    "nft delete table ip wdtt 2>/dev/null || true; " +
                    "nft delete table inet wdtt 2>/dev/null || true; " +
                    "nft delete table inet wdtt_mangle 2>/dev/null || true; " +
                    "fi"
            ),
            timeout = 15000L
        )

        onProgress(0.75f, "Удаление WDTT-интерфейса...")
        ssh.exec(
            rootCommand(
                "ip link show wdtt0 >/dev/null 2>&1 && ip link del wdtt0 2>/dev/null || true; " +
                    "[ -d /etc/wdtt ] && find /etc/wdtt -mindepth 1 -maxdepth 1 ! -name passwords.json -exec rm -rf {} + 2>/dev/null || true; " +
                    "[ -f /etc/wdtt/passwords.json ] && chmod 600 /etc/wdtt/passwords.json 2>/dev/null || true"
            ),
            timeout = 10000L
        )

        onProgress(0.90f, "Очистка sysctl...")
        ssh.exec(rootCommand("rm -f /etc/sysctl.d/99-wdtt.conf; sysctl --system >/dev/null 2>&1 || true"), timeout = 15000L)

        onProgress(1.0f, "Готово!")
        DeployManager.stopDeploy("success")

    } catch (e: Exception) {
        DeployManager.writeError("Uninstall error: ${e.message}")
        DeployManager.stopDeploy("Ошибка: ${e.message?.take(100)}")
    } finally {
        try { session?.disconnect() } catch (_: Exception) {}
        DeployManager.activeSession = null
    }
}

// ==================== Dialogs ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploySecretsDialog(
    settingsStore: SettingsStore,
    initialMainPass: String,
    initialAdminId: String,
    initialBotToken: String,
    initialSshPort: String,
    manualPortsEnabled: Boolean,
    initialServerDtlsPort: String,
    initialServerWgPort: String,
    onSaved: (mainPass: String, adminId: String, botToken: String, sshPort: String, dtlsPort: String, wgPort: String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var passInput by rememberSaveable { mutableStateOf(initialMainPass) }
    var adminIdInput by rememberSaveable { mutableStateOf(initialAdminId) }
    var botTokenInput by rememberSaveable { mutableStateOf(initialBotToken) }
    var sshPortInput by rememberSaveable { mutableStateOf(if (initialSshPort.isBlank()) "22" else initialSshPort) }
    var dtlsPortInput by rememberSaveable { mutableStateOf(initialServerDtlsPort.ifBlank { "56000" }) }
    var wgPortInput by rememberSaveable { mutableStateOf(initialServerWgPort.ifBlank { "56001" }) }

    fun normalizePort(value: String, fallback: String): String {
        return value.toIntOrNull()?.takeIf { it in 1..65535 }?.toString() ?: fallback
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.imePadding(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Секреты Деплоя", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = passInput,
                    onValueChange = { passInput = it },
                    label = { Text("Задайте пароль туннеля (любой)") },
                    placeholder = { Text("Придумайте надежный пароль") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Телеграм бот для управления", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = adminIdInput,
                    onValueChange = { adminIdInput = it },
                    label = { Text("ID Админа (Опционально)") },
                    placeholder = { Text("ID из @getmyid_bot") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = botTokenInput,
                    onValueChange = { botTokenInput = it },
                    label = { Text("Токен Бота (Опционально)") },
                    placeholder = { Text("Токен от BotFather") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("SSH Порт", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = sshPortInput,
                    onValueChange = { sshPortInput = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт для деплоя SSH") },
                    placeholder = { Text("22") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )

                if (manualPortsEnabled) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Порты сервера", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dtlsPortInput,
                        onValueChange = { dtlsPortInput = it.filter(Char::isDigit).take(5) },
                        label = { Text("Порт DTLS сервера") },
                        placeholder = { Text("56000") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = wgPortInput,
                        onValueChange = { wgPortInput = it.filter(Char::isDigit).take(5) },
                        label = { Text("Порт WireGuard сервера") },
                        placeholder = { Text("56001") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        val finalPort = if (sshPortInput.isBlank()) "22" else sshPortInput
                        val finalDtls = normalizePort(dtlsPortInput, "56000")
                        val finalWg = normalizePort(wgPortInput, "56001")
                        scope.launch {
                            settingsStore.saveDeploySecrets(passInput, adminIdInput, botTokenInput, finalPort)
                            settingsStore.savePorts(finalDtls.toInt(), finalWg.toInt(), settingsStore.listenPort.first())
                            onSaved(passInput, adminIdInput, botTokenInput, finalPort, finalDtls, finalWg)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = passInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UninstallConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var confirmText by remember { mutableStateOf("") }
    val isConfirmed = confirmText.trim().lowercase() == "да"

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Удаление WDTT с сервера",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "Будут удалены: бинарник, systemd-сервис, бот, конфигурация WDTT и только помеченные правила firewall/NAT для WDTT.\n\nЭто действие необратимо.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    label = { Text("Введите «да» для подтверждения") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.error,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) { Text("Отмена") }
                    Button(
                        onClick = onConfirm, modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp), enabled = isConfirmed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Удалить", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
