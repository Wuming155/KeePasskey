package com.keepasskey.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.apps.InstalledAppOption
import com.keepasskey.app.apps.InstalledAppsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用选择器（TASK-139）：从系统枚举的已安装应用里按**应用名**指认目标应用，
 * 一次给出应用名 + 图标 + 包名，替代此前「手工键入包名」的输入方式
 * （拼错包名会得到「假屏蔽 / 假绑定」且无从察觉）。
 *
 * 数据源与可见性口径见 [InstalledAppsCatalog] 与清单 `<queries>` 注释：
 * 列出的是**带桌面入口**的应用；未列出的包名仍可经调用方的手工输入兜底。
 */
@Composable
fun AppPickerDialog(
    onPick: (InstalledAppOption) -> Unit,
    onDismiss: () -> Unit,
    /** 已在名单 / 已绑定的包名：置勾选并禁用点击，避免「点一下才知道已添加」 */
    alreadySelected: Set<String> = emptySet()
) {
    val context = LocalContext.current
    // null = 枚举中（Binder IPC + 图标解码，必须在 IO 线程）
    val apps by produceState<Result<List<InstalledAppOption>>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { InstalledAppsCatalog.launchableApps(context) }
        }
    }

    AppPickerDialogContent(
        apps = apps?.getOrNull(),
        loadFailed = apps?.isFailure == true,
        onPick = onPick,
        onDismiss = onDismiss,
        alreadySelected = alreadySelected
    )
}

/**
 * 选择器的无状态内容层：预览与截图用例可注入假数据独立渲染，不触碰 PackageManager。
 *
 * @param apps null = 枚举中；空列表 = 枚举成功但无可用应用
 * @param loadFailed 枚举抛异常（与「枚举为空」区分，如实告知而不是笼统说「没有应用」）
 */
@Composable
internal fun AppPickerDialogContent(
    apps: List<InstalledAppOption>?,
    loadFailed: Boolean,
    onPick: (InstalledAppOption) -> Unit,
    onDismiss: () -> Unit,
    alreadySelected: Set<String> = emptySet()
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(apps, query) {
        apps?.let { InstalledAppsCatalog.filter(it, query) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.app_picker_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.app_picker_search_hint)) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                when {
                    loadFailed -> PickerHint(stringResource(R.string.app_picker_load_failed))

                    visible == null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }

                    visible.isEmpty() -> PickerHint(
                        if (query.isBlank()) {
                            stringResource(R.string.app_picker_empty)
                        } else {
                            stringResource(R.string.app_picker_no_match)
                        }
                    )

                    else -> LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            InstalledAppRow(
                                app = app,
                                selected = app.packageName in alreadySelected,
                                onClick = { onPick(app) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        }
    )
}

@Composable
private fun PickerHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun InstalledAppRow(
    app: InstalledAppOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !selected, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconSlot(app = app, size = 36.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            // 应用名不可读时回落为包名：此时不再重复渲染同一串，避免"同一串出现两遍"的噪声
            if (app.label != app.packageName) {
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.app_picker_added_cd),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 应用图标绘制槽：图标载荷为 null（未安装 / 图标不可读）时回退系统语义图标，
 * **不伪造**任何第三方应用图标。载荷解码已在 [InstalledAppsCatalog] 的 IO 段完成，此处只绘制。
 */
@Composable
internal fun AppIconSlot(app: InstalledAppOption, size: Dp) {
    val icon = app.icon
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    } else {
        Icon(
            imageVector = Icons.Default.Android,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size)
        )
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "应用选择器 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "应用选择器 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun AppPickerDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        AppPickerDialogContent(
            // 明显虚构的包名占位，不涉及任何真实应用或凭据；图标载荷为空 → 走系统语义图标
            apps = listOf(
                InstalledAppOption("com.example.previewapp", "预览应用甲"),
                InstalledAppOption("com.example.previewapp.two", "预览应用乙"),
                InstalledAppOption("com.example.previewapp.three", "预览应用丙")
            ),
            loadFailed = false,
            onPick = {},
            onDismiss = {},
            alreadySelected = setOf("com.example.previewapp.two")
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "应用选择器 - 枚举失败", showBackground = true)
@Composable
internal fun AppPickerDialogLoadFailedPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        AppPickerDialogContent(
            apps = null,
            loadFailed = true,
            onPick = {},
            onDismiss = {}
        )
    }
}
