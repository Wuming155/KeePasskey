package com.keepasskey.app.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

/**
 * 安全密码输入组件（String 边界最小化封装）。
 *
 * Compose 官方 TextField API 以 String 承载输入内容，属于框架层边界妥协（无法在框架内部消除）。
 * 本组件将该妥协收敛到**唯一的、生命周期最短的**封装点：
 * 1. 显示用 String 仅存活于组件内部（不进入 UiState / StateFlow，杜绝状态层长期驻留明文）；
 * 2. 每次输入变更第一时间转换为 [CharArray] 经 [onPasswordChanged] 上行（收件方如需长期持有必须自行复制并负责清零）；
 * 3. 组件离开组合（DisposableEffect onDispose）或内容变更时，立即对桥接 CharArray 显式清零并释放 String 引用；
 * 4. 密码键盘、圆点遮罩、可见性切换、等宽字形等安全输入惯例内聚于此，调用方零配置复用；
 * 5. [initialPassword] 支持既有密码一次性预填（编辑场景）：按 [initialKey] 消费一次，
 *    仅注入组件内部显示态，不回写 [onPasswordChanged]（预填非用户编辑，不触发脏标记；
 *    长期持有由数据层自行管理，加解密审查 2026-09 M1 整改）。
 */
@Composable
fun SecurePasswordField(
    label: String,
    onPasswordChanged: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    isPasswordVisible: Boolean = false,
    onToggleVisibility: (() -> Unit)? = null,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    initialPassword: CharArray? = null,
    initialKey: Any? = null,
    onDone: () -> Unit = {}
) {
    var displayText by remember { mutableStateOf("") }
    var charBridge by remember { mutableStateOf(CharArray(0)) }
    // 预填消费闩：同一 initialKey 只消费一次，避免输入过程中被重复回写覆盖
    var consumedInitialKey by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(initialKey, initialPassword) {
        val initial = initialPassword
        if (initial != null && initialKey != null && consumedInitialKey != initialKey) {
            consumedInitialKey = initialKey
            charBridge.fill('0')
            charBridge = initial.copyOf()
            // 显示用 String：仅存活于组件内部（框架边界），离开组合即释放；
            // 预填不回写 onPasswordChanged——预填不是用户编辑，不产生脏标记
            displayText = String(initial)
        }
    }

    fun wipeSecret() {
        charBridge.fill('0')
        charBridge = CharArray(0)
        displayText = ""
    }

    DisposableEffect(Unit) {
        onDispose { wipeSecret() }
    }

    OutlinedTextField(
        value = displayText,
        onValueChange = { newValue ->
            charBridge.fill('0')
            charBridge = newValue.toCharArray()
            displayText = newValue
            // 桥接数组仅供本次回调消费；收件方长期持有须自行复制
            onPasswordChanged(charBridge)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = isError,
        supportingText = supportingText,
        singleLine = true,
        visualTransformation = if (isPasswordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation('●')
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = trailingIcon ?: onToggleVisibility?.let { toggle ->
            {
                IconButton(onClick = toggle) {
                    Icon(
                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isPasswordVisible) {
                            stringResource(R.string.cd_hide_password)
                        } else {
                            stringResource(R.string.cd_show_password)
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        leadingIcon = leadingIcon?.let { icon ->
            {
                Icon(imageVector = icon, contentDescription = null)
            }
        },
        textStyle = MonospacePasswordStyle.copy(
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        enabled = enabled,
        modifier = modifier
    )
}
