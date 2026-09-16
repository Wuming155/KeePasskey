package com.keepasskey.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 整数参数滑块：连续宽轨 + 拖拽时浮动数值气泡 + 跨档触感反馈。
 *
 * 不使用 `steps`（密集圆点轨与周围圆润 M3 组件断层）；值在 onValueChange 内取整，
 * 调用方标题已展示当前值，气泡仅在拖拽中出现（高度占位避免布局跳动）。
 */
@Composable
fun ValueSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    var dragging by remember { mutableStateOf(false) }
    var lastHapticValue by remember { mutableIntStateOf(value) }
    val bubbleAlpha by animateFloatAsState(targetValue = if (dragging) 1f else 0f, label = "sliderBubble")

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> dragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
                else -> Unit
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bubbleAlpha > 0f) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.alpha(bubbleAlpha)
                ) {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val next = raw.roundToInt().coerceIn(valueRange)
                if (next != lastHapticValue) {
                    lastHapticValue = next
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onValueChange(next)
            },
            onValueChangeFinished = { dragging = false },
            interactionSource = interactionSource,
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}
