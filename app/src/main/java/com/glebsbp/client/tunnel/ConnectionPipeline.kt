package com.glebsbp.client

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ConnectionStep(val order: Int, val label: String, val detail: String) {
    DNS(0, "DNS", "Проверка DNS до VK"),
    VK(1, "VK", "Получение TURN-кредов"),
    CAPTCHA(2, "Капча", "Решение Smart Captcha"),
    WRAP(3, "WRAP", "RTP-маскировка трафика"),
    TURN(4, "TURN", "Relay через VK"),
    DTLS(5, "DTLS", "Handshake с сервером"),
    WORKERS(6, "Потоки", "Активация воркеров"),
    VPN(7, "VPN", "Запуск WireGuard"),
    RAW(7, "Raw", "Настройка raw-IP туннеля"),
    SOCKS(7, "SOCKS", "Локальный SOCKS5"),
    DONE(8, "OK", "Туннель работает"),
}

enum class ConnectionTransportKind { VPN, RAW, SOCKS }

data class ConnectionPipelineState(
    val current: ConnectionStep? = null,
    val completed: Set<ConnectionStep> = emptySet(),
    val failed: ConnectionStep? = null,
    val visible: Boolean = false,
    val captchaRequired: Boolean = false,
    val timedOut: Boolean = false,
    val timeoutSec: Int = 10,
    val transportKind: ConnectionTransportKind = ConnectionTransportKind.VPN,
    /**
     * DTLS-хендшейк реально выполняется только в классическом VPN-режиме без
     * -notls — в Raw и в Direct(noDtls) его нет вообще (RTP-obfs AEAD
     * напрямую), но шаг DTLS раньше всё равно показывался в схеме и просто
     * мгновенно "проскакивал", что вводило в заблуждение.
     */
    val dtlsUsed: Boolean = true,
) {
    fun finalTransportStep(): ConnectionStep = when (transportKind) {
        ConnectionTransportKind.VPN -> ConnectionStep.VPN
        ConnectionTransportKind.RAW -> ConnectionStep.RAW
        ConnectionTransportKind.SOCKS -> ConnectionStep.SOCKS
    }

    fun stepsToShow(): List<ConnectionStep> = buildList {
        add(ConnectionStep.DNS)
        add(ConnectionStep.VK)
        if (captchaRequired) add(ConnectionStep.CAPTCHA)
        add(ConnectionStep.WRAP)
        add(ConnectionStep.TURN)
        if (dtlsUsed) add(ConnectionStep.DTLS)
        add(ConnectionStep.WORKERS)
        add(finalTransportStep())
    }

}

private enum class StepVisual { Done, Current, Pending, Failed }

@Composable
fun ConnectionPipelineCard(
    state: ConnectionPipelineState,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return

    val steps = state.stepsToShow()
    val doneColor = GlebColors.terminalGreen
    val currentColor = GlebColors.terminalBlue
    val pendingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val failedColor = GlebColors.terminalRed
    val lineDone = doneColor.copy(alpha = 0.7f)
    val linePending = pendingColor

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GlebColors.terminalBlue.copy(alpha = if (isDark) 0.10f else 0.08f),
        shape = RoundedCornerShape(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                steps.forEachIndexed { index, step ->
                    val visual = when {
                        state.failed == step -> StepVisual.Failed
                        state.completed.contains(step) || state.current == ConnectionStep.DONE -> StepVisual.Done
                        state.current == step -> StepVisual.Current
                        else -> StepVisual.Pending
                    }

                    PipelineNode(
                        label = step.label,
                        visual = visual,
                        doneColor = doneColor,
                        currentColor = currentColor,
                        pendingColor = pendingColor,
                        failedColor = failedColor,
                    )

                    if (index < steps.lastIndex) {
                        val leftDone = visual == StepVisual.Done
                        val rightStep = steps[index + 1]
                        val rightDone = state.completed.contains(rightStep) ||
                            state.current == ConnectionStep.DONE ||
                            (state.current?.order ?: -1) > rightStep.order
                        Box(modifier = Modifier.weight(1f).height(22.dp)) {
                            PipelineConnector(
                                done = leftDone && (rightDone || state.current?.order == rightStep.order),
                                doneColor = lineDone,
                                pendingColor = linePending,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineNode(
    label: String,
    visual: StepVisual,
    doneColor: Color,
    currentColor: Color,
    pendingColor: Color,
    failedColor: Color,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pipeline_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val bg = when (visual) {
                StepVisual.Done -> doneColor.copy(alpha = 0.22f)
                StepVisual.Current -> currentColor.copy(alpha = 0.22f)
                StepVisual.Failed -> failedColor.copy(alpha = 0.22f)
                StepVisual.Pending -> pendingColor.copy(alpha = 0.35f)
            }
            val border = when (visual) {
                StepVisual.Done -> doneColor
                StepVisual.Current -> currentColor
                StepVisual.Failed -> failedColor
                StepVisual.Pending -> pendingColor
            }
            Surface(
                modifier = Modifier
                    .size(if (visual == StepVisual.Current) (22.dp * pulse) else 22.dp),
                shape = RoundedCornerShape(50),
                color = bg,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, border),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (visual) {
                        StepVisual.Done -> Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = doneColor,
                            modifier = Modifier.size(12.dp),
                        )
                        StepVisual.Failed -> Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = failedColor,
                            modifier = Modifier.size(12.dp),
                        )
                        StepVisual.Current -> Box(
                            modifier = Modifier
                                .size(8.dp)
                                .padding(0.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(8.dp),
                                shape = RoundedCornerShape(50),
                                color = currentColor,
                            ) {}
                        }
                        StepVisual.Pending -> {}
                    }
                }
            }
        }
        Text(
            text = label,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (visual == StepVisual.Current) FontWeight.Bold else FontWeight.Normal,
            color = when (visual) {
                StepVisual.Done -> doneColor
                StepVisual.Current -> currentColor
                StepVisual.Failed -> failedColor
                StepVisual.Pending -> pendingColor
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun PipelineConnector(
    done: Boolean,
    doneColor: Color,
    pendingColor: Color,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val y = size.height / 2f
        drawLine(
            color = if (done) doneColor else pendingColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2f,
        )
    }
}
