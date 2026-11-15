package com.wdtt.client.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wdtt.client.ConnectionDiagnostics
import com.wdtt.client.DiagSeverity
import com.wdtt.client.DiagStep
import com.wdtt.client.DiagStepState
import com.wdtt.client.DiagnosticsState
import com.wdtt.client.WDTTColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Цвета и иконки по смыслу ───

@Composable
private fun severityColor(severity: DiagSeverity?): Color = when (severity) {
    DiagSeverity.OK -> WDTTColors.connected
    DiagSeverity.WARN -> WDTTColors.warning
    DiagSeverity.ERROR -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.primary
}

private fun severityIcon(severity: DiagSeverity?): ImageVector = when (severity) {
    DiagSeverity.OK -> Icons.Rounded.CheckCircle
    DiagSeverity.WARN -> Icons.Rounded.WarningAmber
    DiagSeverity.ERROR -> Icons.Rounded.ErrorOutline
    null -> Icons.Rounded.NetworkCheck
}

@Composable
private fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.22f

/** Мягкая подложка в цвет статуса: заметно, но не кричит. */
@Composable
private fun tint(color: Color, light: Float = 0.10f, dark: Float = 0.16f): Color =
    color.copy(alpha = if (isDarkTheme()) dark else light)

@Composable
private fun StatusBadge(icon: ImageVector, color: Color, size: Int = 40) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.32f).dp))
            .background(tint(color, 0.14f, 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size((size * 0.58f).dp))
    }
}

private fun currentStepText(state: DiagnosticsState): String {
    val running = state.steps.lastOrNull { it.state == DiagStepState.RUNNING }
    return when {
        running != null -> "${running.title}: ${running.text}"
        state.steps.isNotEmpty() -> "${state.steps.last().title}…"
        state.running -> "Подключаемся к TURN VK…"
        else -> "Запуск проверки…"
    }
}

/**
 * Показывать ли карточку на главном экране. Итог автопроверки прячется,
 * как только туннель заработал: проблема ушла. Итог ручной проверки
 * остаётся, пока его не закроют.
 */
fun shouldShowDiagnosticsCard(state: DiagnosticsState, activeWorkers: Int): Boolean = when {
    state.running || state.requested -> true
    state.verdict == null -> false
    state.trigger == "auto" && activeWorkers > 0 -> false
    else -> true
}

// ─── Карточка на главном экране ───

@Composable
fun DiagnosticsCard(
    state: DiagnosticsState,
    onOpen: () -> Unit,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val verdict = state.verdict
    val inProgress = state.running || state.requested || verdict == null
    val accent by animateColorAsState(
        targetValue = if (inProgress) MaterialTheme.colorScheme.primary else severityColor(verdict?.severity),
        animationSpec = tween(350),
        label = "diag_accent",
    )

    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = tint(accent, 0.06f, 0.09f),
        // Подложка полупрозрачная — без явного цвета Compose не знает, каким
        // делать текст, и в тёмной теме заголовок выходил тёмным на тёмном.
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
    ) {
        AnimatedContent(
            targetState = inProgress,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "diag_card",
        ) { progress ->
            if (progress) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = accent,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Проверяем соединение",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            currentStepText(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (verdict != null) {
                // Действия — только когда есть что чинить. Зелёный итог
                // просто сообщает; подробности — по нажатию на карточку.
                val primary = verdict.actions.firstOrNull()?.takeIf { verdict.severity != DiagSeverity.OK }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    StatusBadge(severityIcon(verdict.severity), accent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                verdict.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Скрыть",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Text(
                            verdict.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (primary != null) {
                                FilledTonalButton(
                                    onClick = { onAction(primary) },
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    modifier = Modifier.height(34.dp),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(
                                        ConnectionDiagnostics.actionLabel(primary),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                    )
                                }
                            }
                            TextButton(
                                onClick = onOpen,
                                contentPadding = PaddingValues(horizontal = if (primary != null) 10.dp else 0.dp),
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("Подробнее", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Нижний лист с подробностями ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    state: DiagnosticsState,
    tunnelActive: Boolean,
    onRun: () -> Unit,
    onAction: (String) -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    /** Проверка отдельного профиля: свой заголовок, без кнопок-действий
     *  (они меняют общие настройки), кнопка — просто «Проверить снова». */
    title: String = "Проверка соединения",
    standalone: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val busy = state.running || state.requested

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Заголовок
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isEmpty) {
                EmptyState(tunnelActive || standalone)
            } else {
                VerdictHero(state)
                if (state.steps.isNotEmpty()) {
                    StepsTimeline(state.steps)
                }
                FactsBlock(state)
                val actions = if (standalone) emptyList() else state.verdict?.actions.orEmpty()
                if (!busy && actions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEachIndexed { index, action ->
                            if (index == 0) {
                                Button(
                                    onClick = { onAction(action) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                ) { Text(ConnectionDiagnostics.actionLabel(action), fontWeight = FontWeight.SemiBold) }
                            } else {
                                OutlinedButton(
                                    onClick = { onAction(action) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                ) { Text(ConnectionDiagnostics.actionLabel(action)) }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!state.isEmpty) {
                    OutlinedButton(
                        onClick = onCopy,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Отчёт", maxLines = 1)
                    }
                }
                val runLabel = when {
                    busy -> "Проверяем…"
                    standalone -> if (state.isEmpty) "Проверить" else "Проверить снова"
                    !tunnelActive -> "Подключить и проверить"
                    state.isEmpty -> "Проверить"
                    else -> "Проверить снова"
                }
                FilledTonalButton(
                    onClick = onRun,
                    enabled = !busy,
                    modifier = Modifier.weight(if (state.isEmpty) 1f else 1.4f).height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(runLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun subtitle(state: DiagnosticsState): String {
    if (state.isEmpty) return "Через VK тем же путём, что и туннель"
    if (state.running || state.requested) return "Идёт проверка…"
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(Date(if (state.finishedAtMs > 0) state.finishedAtMs else state.startedAtMs))
    val how = if (state.trigger == "auto") "автоматически" else "по запросу"
    return "$how · $time"
}

@Composable
private fun EmptyState(tunnelActive: Boolean) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = tint(MaterialTheme.colorScheme.primary, 0.06f, 0.10f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusBadge(Icons.Rounded.NetworkCheck, MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Найдём, где рвётся связь",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Проверим TURN-сервер VK, ответ вашего сервера, пароль, порты и крупные пакеты. " +
                        "Займёт несколько секунд." +
                        if (tunnelActive) "" else " Проверка идёт внутри подключения, поэтому туннель запустится.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Если туннель не поднимается, проверка запускается сама.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VerdictHero(state: DiagnosticsState) {
    val verdict = state.verdict
    if (state.isInterrupted) {
        InterruptedHero()
        return
    }
    val busy = state.running || state.requested || verdict == null
    val color = if (busy) MaterialTheme.colorScheme.primary else severityColor(verdict?.severity)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = tint(color),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (busy) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp, color = color)
                }
            } else {
                StatusBadge(severityIcon(verdict?.severity), color, size = 44)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (busy) "Проверяем соединение" else verdict!!.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (busy) currentStepText(state) else verdict!!.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InterruptedHero() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDarkTheme()) 0.45f else 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(Icons.Rounded.Remove, color, size = 44)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Проверка прервана", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Подключение остановилось раньше, чем проверка закончилась. Запустите её снова.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepsTimeline(steps: List<DiagStep>) {
    Column {
        steps.forEachIndexed { index, step ->
            StepRow(step, isLast = index == steps.lastIndex)
        }
    }
}

@Composable
private fun StepRow(step: DiagStep, isLast: Boolean) {
    val color = when (step.state) {
        DiagStepState.OK -> WDTTColors.connected
        DiagStepState.FAIL -> MaterialTheme.colorScheme.error
        DiagStepState.WARN -> WDTTColors.warning
        DiagStepState.SKIP -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        DiagStepState.RUNNING -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Значок шага и линия к следующему
        Column(
            modifier = Modifier.width(26.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(tint(color, 0.14f, 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                when (step.state) {
                    DiagStepState.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = color,
                    )
                    else -> Icon(
                        imageVector = when (step.state) {
                            DiagStepState.OK -> Icons.Rounded.CheckCircle
                            DiagStepState.FAIL -> Icons.Rounded.Close
                            DiagStepState.WARN -> Icons.Rounded.WarningAmber
                            else -> Icons.Rounded.Remove
                        },
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 3.dp)
                        .width(2.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 3.dp, bottom = if (isLast) 0.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                step.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (step.state == DiagStepState.SKIP) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (step.text.isNotBlank()) {
                Text(
                    step.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FactsBlock(state: DiagnosticsState) {
    val rows = ConnectionDiagnostics.factRows(state.verdict, state.mode)
    if (rows.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDarkTheme()) 0.45f else 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.9f),
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1.1f),
                    )
                }
            }
        }
    }
}
