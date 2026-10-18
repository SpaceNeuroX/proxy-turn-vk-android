package com.glebsbp.client

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.glebsbp.client.ui.AppUpdateDialog
import com.glebsbp.client.ui.SupportNoticeDialog
import com.glebsbp.client.ui.ProfilesTab
import com.glebsbp.client.ui.LogsTab
import com.glebsbp.client.ui.SettingsTab
import com.glebsbp.client.ui.ServersTab
import com.glebsbp.client.ui.ExceptionsTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Диалог оптимизации батареи закрыт — VPN-разрешение запрашиваем только при подключении.
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkAndRequestBattery()
    }

    /**
     * VPN consent живёт на Activity, а не во вкладке Settings:
     * иначе при auto-switch на «Логи» launcher снимается и после «Разрешить» старт зависает.
     */
    @Volatile
    private var pendingAfterVpnGranted: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val cont = pendingAfterVpnGranted
        pendingAfterVpnGranted = null
        if (cont == null) return@registerForActivityResult
        if (android.net.VpnService.prepare(this) == null) {
            cont()
        } else {
            TunnelManager.cancelConnectingIfNeeded()
            Toast.makeText(this, "VPN-разрешение не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    fun prepareVpnThen(onGranted: () -> Unit) {
        val vpnIntent = android.net.VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingAfterVpnGranted = onGranted
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            onGranted()
        }
    }

    companion object {
        var activeActivities = 0
        var isForeground: Boolean
            get() = activeActivities > 0
            set(value) {}

        // Статическая ссылка на текущую Activity
        var currentActivity: MainActivity? = null

        // URI файла .glebsbp, ожидающего импорта
        val pendingFileUri = mutableStateOf<Uri?>(null)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            AppShortcuts.ACTION_START_TUNNEL -> startTunnelFromShortcut()
            AppShortcuts.ACTION_STOP_TUNNEL -> TunnelControl.stop(applicationContext)
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    pendingFileUri.value = uri
                }
            }
        }
    }

    private fun startTunnelFromShortcut() {
        if (TunnelManager.running.value || TunnelManager.isConnecting.value) return
        prepareVpnThen { TunnelControl.startFromSavedSettings(applicationContext) }
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        currentActivity = this
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
        VkAuthWebViewManager.checkAndShowPendingAuth(this)
    }

    override fun onStop() {
        super.onStop()
        activeActivities--
        if (currentActivity == this) {
            currentActivity = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAndRequestNotifications()

        handleIncomingIntent(intent)

        setContent {
            val settingsStore = remember { SettingsStore(this) }
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val isDynamicColor by settingsStore.isDynamicColor.collectAsStateWithLifecycle(initialValue = false)
            val themePalette by settingsStore.themePalette.collectAsStateWithLifecycle(initialValue = "indigo")
            val scope = rememberCoroutineScope()

            GlebTheme(themeMode = themeMode, dynamicColor = isDynamicColor, themePalette = themePalette) {
                MainScreen(
                    settingsStore = settingsStore,
                    themeMode = themeMode,
                    onThemeChange = { mode ->
                        scope.launch {
                            settingsStore.saveThemeMode(mode)
                        }
                    },
                    isDynamicColor = isDynamicColor,
                    onDynamicColorChange = { enabled ->
                        scope.launch { settingsStore.saveDynamicColor(enabled) }
                    },
                    currentPalette = themePalette,
                    onPaletteChange = { palette ->
                        scope.launch { settingsStore.saveThemePalette(palette) }
                    }
                )
            }
        }
    }

    private fun checkAndRequestNotifications() {
        NotificationHelper.ensureTunnelChannel(this)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkAndRequestBattery()
            }
        } else {
            checkAndRequestBattery()
        }
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun openNotificationSettings() {
        NotificationHelper.openAppNotificationSettings(this)
    }

    private fun checkAndRequestBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryLauncher.launch(intent)
            } catch (_: Exception) {
                // Не удалось показать диалог — пропускаем.
            }
        }
    }
}

// ═══ Навигация ═══

private data class NavItem(
    val id: Int,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navItems = listOf(
    NavItem(0, "Туннель", Icons.Filled.VpnKey, Icons.Outlined.VpnKey),
    NavItem(1, "Серверы", Icons.Filled.Cloud, Icons.Outlined.Cloud),
    NavItem(2, "Профили", Icons.Filled.FolderOpen, Icons.Outlined.Folder),
    NavItem(3, "Обход", Icons.Filled.FilterList, Icons.Outlined.FilterList),
    NavItem(4, "Логи", Icons.Filled.Terminal, Icons.Outlined.Terminal),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsStore: SettingsStore,
    themeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
    isDynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {},
    currentPalette: String = "indigo",
    onPaletteChange: (String) -> Unit = {}
) {
    val unreadErrors by TunnelManager.unreadErrorCount.collectAsStateWithLifecycle()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val openAppSettingsRequest by TunnelManager.openAppSettingsRequest.collectAsStateWithLifecycle()
    val hasSeenWelcomeDialog by settingsStore.hasSeenWelcomeDialog.collectAsStateWithLifecycle(initialValue = true)
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
    )
    val includeBetaUpdates by settingsStore.includeBetaUpdates.collectAsStateWithLifecycle(initialValue = false)
    val interfaceRole by settingsStore.interfaceRole.collectAsStateWithLifecycle(initialValue = "admin")
    val isAdminInterface = interfaceRole == "admin"
    val autoSwitchToLogs by settingsStore.autoSwitchToLogs.collectAsStateWithLifecycle(initialValue = true)
    var pendingSwitchToLogs by remember { mutableStateOf(false) }
    val activeNavItems = remember(isAdminInterface) {
        navItems.filter { isAdminInterface || it.id != 1 }
    }
    var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var showSupportNotice by remember { mutableStateOf(false) }
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val navOverlayReserve = safeBottomInset + 96.dp

    LaunchedEffect(selectedTab) {
        if (selectedTab == 4) TunnelManager.clearUnreadErrors()
    }

    LaunchedEffect(pendingSwitchToLogs, autoSwitchToLogs) {
        if (pendingSwitchToLogs && autoSwitchToLogs) {
            pendingSwitchToLogs = false
            selectedTab = 4
        }
    }

    LaunchedEffect(activeNavItems, selectedTab) {
        if (activeNavItems.none { it.id == selectedTab }) {
            selectedTab = activeNavItems.firstOrNull()?.id ?: 0
        }
    }

    LaunchedEffect(openAppSettingsRequest) {
        if (openAppSettingsRequest > 0L) {
            selectedTab = 0
        }
    }

    val pendingFileUri = MainActivity.pendingFileUri.value
    LaunchedEffect(pendingFileUri) {
        if (pendingFileUri != null) {
            selectedTab = 2
        }
    }

    var requestCreateProfile by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val supportShownFor = settingsStore.supportNoticeShownVersionCode.first()
        val currentCode = BuildConfig.VERSION_CODE
        if (currentCode >= SettingsStore.SUPPORT_NOTICE_VERSION_CODE &&
            supportShownFor < SettingsStore.SUPPORT_NOTICE_VERSION_CODE
        ) {
            showSupportNotice = true
        }
    }

    // Тихое автообновление подписок при открытии (не во время туннеля).
    LaunchedEffect(Unit) {
        val intervalHours = settingsStore.subscriptionAutoRefreshHours.first()
        if (intervalHours == SettingsStore.SUB_AUTO_REFRESH_NEVER) return@LaunchedEffect
        if (TunnelManager.running.value) return@LaunchedEffect

        val profilesStore = ProfilesStore(context)
        val result = runCatching {
            profilesStore.autoRefreshSubscriptionsIfDue(intervalHours)
        }.getOrElse {
            Log.w("GLEBSBP", "Subscription auto-refresh failed: ${it.message}")
            null
        } ?: return@LaunchedEffect

        if (result.refreshedOk == 0 && result.failed == 0) return@LaunchedEffect

        Log.i(
            "GLEBSBP",
            "Subscription auto-refresh: ok=${result.refreshedOk} fail=${result.failed} skipped=${result.skippedFresh}"
        )
        when {
            result.failed > 0 && result.refreshedOk == 0 -> {
                Toast.makeText(
                    context,
                    "Не удалось обновить подписки",
                    Toast.LENGTH_SHORT
                ).show()
            }
            result.failed > 0 -> {
                Toast.makeText(
                    context,
                    "Подписки: обновлено ${result.refreshedOk}, ошибок ${result.failed}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            result.refreshedOk > 0 -> {
                Toast.makeText(
                    context,
                    "Подписки обновлены (${result.refreshedOk})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun dismissSupportNotice() {
        showSupportNotice = false
        scope.launch {
            settingsStore.saveSupportNoticeShownVersionCode(BuildConfig.VERSION_CODE)
        }
    }

    LaunchedEffect(updateCheckIntervalHours, includeBetaUpdates) {
        if (updateCheckIntervalHours == UPDATE_CHECK_NEVER) return@LaunchedEffect

        val intervalMillis = updateIntervalHoursToMillis(updateCheckIntervalHours)
            ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
            ?: 12L * 60L * 60L * 1000L

        suspend fun runUpdateCheck(reason: String) {
            val checkedAt = System.currentTimeMillis()
            val includeBeta = settingsStore.includeBetaUpdates.first()
            val release = fetchLatestReleaseInfo(currentVersion, includeBeta)
            settingsStore.saveUpdateState(
                lastCheckAt = checkedAt,
                latestVersion = release?.versionTag ?: "",
                error = if (release == null) "Не удалось проверить" else ""
            )

            if (release == null) {
                Log.w("GLEBSBP", "[WARN] Update check: no release info, local=$currentVersion reason=$reason")
                return
            }

            val hasUpdate = isNewerVersion(currentVersion, release.versionTag, includeBeta)
            val postponeVer = settingsStore.updatePostponeVersion.first()
            val postponeUntil = settingsStore.updatePostponeUntil.first()
            val isPostponed = postponeVer == release.versionTag && checkedAt < postponeUntil
            Log.i(
                "GLEBSBP",
                "Update check: local=$currentVersion remote=${release.versionTag} newer=$hasUpdate postponed=$isPostponed reason=$reason"
            )

            if (hasUpdate && !isPostponed) {
                pendingRelease = release
            }
        }

        runUpdateCheck("startup")

        while (isActive) {
            val now = System.currentTimeMillis()
            val lastCheck = settingsStore.updateLastCheckAt.first()
            val nextCheckAt = lastCheck + intervalMillis
            val waitMs = (nextCheckAt - now).coerceAtLeast(intervalMillis)
            delay(waitMs)
            if (isActive) {
                runUpdateCheck("periodic")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.matchParentSize())

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            containerColor = Color.Transparent,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .pointerInput(selectedTab, activeNavItems) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                dragTargetIndex = -1
                                dragProgress = 0f
                            },
                            onDragCancel = {
                                dragTargetIndex = -1
                                dragProgress = 0f
                            },
                            onDragEnd = {
                                if (dragTargetIndex in activeNavItems.indices && dragProgress >= 0.5f) {
                                    selectedTab = activeNavItems[dragTargetIndex].id
                                    if (selectedTab == 4) TunnelManager.clearUnreadErrors()
                                }
                                dragTargetIndex = -1
                                dragProgress = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                            if (abs(totalDrag) < 12f) {
                                dragTargetIndex = -1
                                dragProgress = 0f
                                return@detectHorizontalDragGestures
                            }

                            val currentActiveIndex = activeNavItems.indexOfFirst { it.id == selectedTab }
                            val candidate = if (totalDrag < 0f) currentActiveIndex + 1 else currentActiveIndex - 1
                            if (candidate !in activeNavItems.indices) {
                                dragTargetIndex = -1
                                dragProgress = 0f
                                return@detectHorizontalDragGestures
                            }

                            dragTargetIndex = candidate
                            dragProgress = (abs(totalDrag) / 180f).coerceIn(0f, 1f)
                        }
                    }
            ) {
                androidx.compose.animation.Crossfade(
                    targetState = selectedTab,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = navOverlayReserve),
                    label = "tab_content"
                ) { tab ->
                    when (tab) {
                        0 -> SettingsTab(
                            themeMode = themeMode,
                            onThemeChange = onThemeChange,
                            isDynamicColor = isDynamicColor,
                            onDynamicColorChange = onDynamicColorChange,
                            currentPalette = currentPalette,
                            onPaletteChange = onPaletteChange,
                            onConnectRequested = { pendingSwitchToLogs = true },
                            onOpenProfiles = { selectedTab = 2 },
                        )
                        1 -> ServersTab()
                        2 -> ProfilesTab(
                            onProfileApplied = { selectedTab = 0 },
                            importFileUri = MainActivity.pendingFileUri.value,
                            onImportHandled = { MainActivity.pendingFileUri.value = null },
                            requestCreateProfile = requestCreateProfile,
                            onCreateProfileHandled = { requestCreateProfile = false }
                        )
                        3 -> ExceptionsTab()
                        4 -> LogsTab()
                    }
                }

                ProxyNavigationBar(
                    navItems = activeNavItems,
                    selectedTab = selectedTab,
                    dragTargetIndex = dragTargetIndex,
                    dragProgress = dragProgress,
                    unreadErrors = unreadErrors,
                    tunnelRunning = tunnelRunning,
                    onTabSelected = { visualIndex ->
                        val tabId = activeNavItems.getOrNull(visualIndex)?.id ?: return@ProxyNavigationBar
                        if (selectedTab != tabId) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            selectedTab = tabId
                            if (tabId == 4) TunnelManager.clearUnreadErrors()
                        }
                        dragTargetIndex = -1
                        dragProgress = 0f
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

    }

    if (!hasSeenWelcomeDialog) {
        AlertDialog(
            onDismissRequest = { 
                scope.launch { settingsStore.saveHasSeenWelcomeDialog(true) }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Готовые профили",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Вы можете получить готовые конфиги напрямую в этих Telegram-ботах:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/glebsbp_bot"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text(
                                "🤖 @glebsbp_bot",
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/glebsbp_support"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Следите за обновлениями",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Все дальнейшие обновления и новости мы будем публиковать в нашем канале:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.TELEGRAM_CHANNEL))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text("📢 @GLEBSBP", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        "Просто скопируйте текст профиля или конфигурационный файл и импортируйте его на вкладке «Профили». Эта памятка также доступна в настройках.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { scope.launch { settingsStore.saveHasSeenWelcomeDialog(true) } }
                ) {
                    Text("Понятно")
                }
            }
        )
    }

    pendingRelease?.let { release ->
        AppUpdateDialog(
            release = release,
            onPostpone = {
                pendingRelease = null
                Toast.makeText(context, "Обновление отложено на 24 часа.", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val now = System.currentTimeMillis()
                    settingsStore.saveUpdatePostpone(
                        version = release.versionTag,
                        until = now + 24L * 60L * 60L * 1000L
                    )
                }
            },
            onUpdate = {
                pendingRelease = null
                scope.launch {
                    openReleaseUrl(context, release.releaseUrl)
                }
            }
        )
    }

    if (showSupportNotice) {
        SupportNoticeDialog(
            versionName = BuildConfig.VERSION_NAME,
            onDismiss = { dismissSupportNotice() },
        )
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun ProxyNavigationBar(
    navItems: List<NavItem>,
    selectedTab: Int,
    dragTargetIndex: Int,
    dragProgress: Float,
    unreadErrors: Int,
    tunnelRunning: Boolean,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val selectedColor = colors.primary
    val unselectedColor = colors.onSurfaceVariant.copy(alpha = 0.55f)
    val shellColor = if (isDark) {
        colors.surface.copy(alpha = 0.78f)
    } else {
        lerp(colors.surface, colors.surfaceVariant, 0.48f).copy(alpha = 0.95f)
    }
    val shellBorder = if (isDark) {
        colors.outlineVariant.copy(alpha = 0.42f)
    } else {
        colors.outline.copy(alpha = 0.16f)
    }
    val indicatorColor = if (isDark) {
        colors.primary.copy(alpha = 0.18f)
    } else {
        lerp(colors.primaryContainer, colors.surface, 0.18f).copy(alpha = 0.94f)
    }
    val indicatorIndex = remember { Animatable(0f) }
    val selectedVisualIndex = navItems.indexOfFirst { it.id == selectedTab }.coerceAtLeast(0)
    val dragVisualIndex = indicatorIndex.value

    LaunchedEffect(selectedVisualIndex, navItems) {
        if (dragTargetIndex !in navItems.indices) {
            indicatorIndex.animateTo(
                targetValue = selectedVisualIndex.toFloat(),
                animationSpec = tween(
                    durationMillis = 720,
                    easing = CubicBezierEasing(0.2f, 0.9f, 0.24f, 1f)
                )
            )
        }
    }

    LaunchedEffect(selectedVisualIndex, dragTargetIndex, dragProgress, navItems) {
        if (dragTargetIndex in navItems.indices) {
            val target = selectedVisualIndex.toFloat() + (dragTargetIndex - selectedVisualIndex) * dragProgress
            indicatorIndex.snapTo(target)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        val trackPadding = 8.dp
        val itemWidth = (maxWidth - trackPadding * 2) / navItems.size
        val indicatorOffset = trackPadding + itemWidth * dragVisualIndex

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = shellColor,
            border = BorderStroke(1.dp, shellBorder),
            tonalElevation = 0.dp,
            shadowElevation = if (isDark) 10.dp else 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = indicatorColor,
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(x = indicatorOffset.roundToPx(), y = 0) }
                        .padding(vertical = 8.dp)
                        .width(itemWidth)
                        .fillMaxHeight()
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = trackPadding, vertical = 6.dp)
                ) {
                    navItems.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - dragVisualIndex)).coerceIn(0f, 1f)
                        val iconColor = lerp(unselectedColor, selectedColor, emphasis)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { onTabSelected(index) },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    // Плоские outlined-иконки; активная вкладка — через цветной контейнер.
                                    imageVector = item.unselectedIcon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(22.dp),
                                    tint = iconColor
                                )
                                if (item.id == 4 && unreadErrors > 0) {
                                    Badge(
                                        containerColor = if (tunnelRunning) colors.primary else GlebColors.warning,
                                        contentColor = colors.onPrimary,
                                        modifier = Modifier.offset(x = 12.dp, y = (-8).dp)
                                    ) {
                                        Text("$unreadErrors")
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.5.sp,
                                    letterSpacing = 0.1.sp
                                ),
                                fontWeight = if (emphasis > 0.55f) FontWeight.SemiBold else FontWeight.Normal,
                                color = iconColor.copy(alpha = if (emphasis > 0.4f) 1f else 0.5f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openReleaseUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
    }
}

private fun android16OrbShape(points: Int, innerRatio: Float): Shape = GenericShape { size, _ ->
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val outerRadius = min(size.width, size.height) / 2f
    val innerRadius = outerRadius * innerRatio

    for (i in 0 until points * 2) {
        val angle = (-PI / 2.0) + (i * PI / points)
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val x = centerX + (radius * cos(angle)).toFloat()
        val y = centerY + (radius * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val Android16OrbLarge: Shape = android16OrbShape(points = 18, innerRatio = 0.90f)
private val Android16OrbMedium: Shape = android16OrbShape(points = 20, innerRatio = 0.92f)
private val Android16OrbSmall: Shape = android16OrbShape(points = 16, innerRatio = 0.88f)

@Composable
private fun AppBackdrop(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val baseBrush = remember(colors.background, colors.surface, colors.surfaceVariant) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    lerp(colors.background, colors.surface, 0.18f),
                    colors.background,
                    lerp(colors.surfaceVariant, colors.background, 0.72f)
                )
            } else {
                listOf(
                    lerp(colors.background, colors.surface, 0.78f),
                    colors.background,
                    lerp(colors.surfaceVariant, colors.background, 0.30f)
                )
            }
        )
    }
    val topGlow = colors.primary.copy(alpha = if (isDark) 0.04f else 0.065f)
    val leftGlow = if (isDark) {
        colors.tertiary.copy(alpha = 0.03f)
    } else {
        lerp(colors.tertiary, colors.secondaryContainer, 0.74f).copy(alpha = 0.16f)
    }
    val bottomGlow = if (isDark) {
        colors.primary.copy(alpha = 0.028f)
    } else {
        lerp(colors.secondary, colors.primaryContainer, 0.70f).copy(alpha = 0.14f)
    }
    val lightOrbOutline = colors.outlineVariant.copy(alpha = 0.18f)
    val topOrbGlow = if (isDark) {
        topGlow
    } else {
        lerp(colors.primary, colors.primaryContainer, 0.72f).copy(alpha = 0.22f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBrush)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-86).dp, y = (-126).dp)
                .size(258.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(topOrbGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline, androidx.compose.foundation.shape.CircleShape)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-44).dp, y = 28.dp)
                .size(146.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(leftGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline.copy(alpha = 0.22f), androidx.compose.foundation.shape.CircleShape)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 62.dp, y = (-208).dp)
                .size(198.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(bottomGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline.copy(alpha = 0.20f), androidx.compose.foundation.shape.CircleShape)
                )
        )
    }
}
