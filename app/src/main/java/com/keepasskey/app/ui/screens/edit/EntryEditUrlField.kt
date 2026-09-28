package com.keepasskey.app.ui.screens.edit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillPackageNames
import com.keepasskey.app.passkey.DomainMatcher
import com.keepasskey.app.ui.components.AppIconSlot
import com.keepasskey.app.ui.components.AppPickerDialog

/**
 * 网址 / 应用绑定字段。
 *
 * `ISSUE-P3-359` 批次自 `EntryEditFormSections.kt` 按纯结构性拆出（行数分档闸门倒逼下沉，
 * 组合输出与行为不变），并同批补 IME「下一项」接线（见 [entryEditNextKeyboardOptions]）。
 *
 * TASK-139：条目「关联具体应用填充」的绑定形式是 `android://<包名>`（见 `DomainMatcher`
 * 的严格包名维度判据），此前只能靠让用户手打 URL 猜格式。本字段右侧提供**应用选择器**入口：
 * 按应用名选定后直接写入绑定串，并把该应用图标回显为前置图标、应用名回显为辅助文案——
 * 用户不必知道、也不必拼写包名。
 *
 * 边界（如实声明）：选择器只列**有桌面入口**的应用；字段本身仍可手工编辑，
 * 因此无桌面入口的包名与 Web URL 的既有写法均不受影响。
 */
@Composable
internal fun EntryUrlField(
    url: String,
    onUrlChange: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val boundPackage = remember(url) { DomainMatcher.extractAndroidBoundPackage(url) }
    val boundApp = rememberBoundAppOption(boundPackage)

    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.edit_url_hint)) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        // ISSUE-P3-359 AC①：Next → 焦点移交下一字段（与 title/username 同链）
        keyboardOptions = entryEditNextKeyboardOptions,
        keyboardActions = rememberEntryEditNextKeyboardActions(),
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = boundApp?.let { app -> { AppIconSlot(app = app, size = 24.dp) } },
        trailingIcon = {
            IconButton(onClick = { showPicker = true }) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = stringResource(R.string.edit_app_binding_pick_cd)
                )
            }
        }
    )

    if (boundApp != null) {
        // 应用名可读 → 名称 + 包名；不可读（未安装 / 受包可见性限制）→ 只报包名并**如实**
        // 说明名称不可读，避免退化成「同一串包名显示两遍」的噪声
        val readable = boundApp.label != boundApp.packageName
        Text(
            text = if (readable) {
                stringResource(R.string.edit_app_binding_bound, boundApp.label, boundApp.packageName)
            } else {
                stringResource(R.string.edit_app_binding_bound_unreadable, boundApp.packageName)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }

    if (showPicker) {
        AppPickerDialog(
            onPick = { app ->
                showPicker = false
                // 与凭据写入链记录的绑定形式一字不差（同一条判据消费，构造收敛于
                // AutofillPackageNames.boundUrl）
                onUrlChange(AutofillPackageNames.boundUrl(app.packageName))
            },
            onDismiss = { showPicker = false },
            alreadySelected = boundPackage?.let { setOf(it) } ?: emptySet()
        )
    }
}
