package com.wdtt.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Один сегмент переключателя режима. */
internal data class ConnectionModeOption(
    val mode: String,
    val label: String,
)

/**
 * Переключатель режима подключения на главном экране: одна капсула,
 * выбранный сегмент подсвечен плашкой, которая плавно переезжает.
 * enabled=false (идёт подключение) — переключатель приглушён.
 */
@Composable
internal fun ConnectionModeSwitch(
    options: List<ConnectionModeOption>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val selectedIndex = options.indexOfFirst { it.mode == selected }.coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .clip(shape)
            .background(colors.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, colors.outline.copy(alpha = 0.25f), shape)
            .padding(4.dp),
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "modeIndicator",
        )

        // Плашка выбранного сегмента.
        Surface(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight(),
            shape = RoundedCornerShape(12.dp),
            color = colors.secondaryContainer,
            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.35f)),
            shadowElevation = 1.dp,
        ) {}

        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            horizontalArrangement = Arrangement.Start,
        ) {
            options.forEach { option ->
                val isSelected = option.mode == selected
                val content by animateColorAsState(
                    targetValue = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    label = "modeContent",
                )
                Box(
                    modifier = Modifier
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { if (!isSelected) onSelect(option.mode) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = content,
                    )
                }
            }
        }
    }
}
