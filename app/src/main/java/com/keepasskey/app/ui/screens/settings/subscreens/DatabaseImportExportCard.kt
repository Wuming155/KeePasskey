package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import kotlinx.coroutines.launch

/**
 * 4. 数据导入与导出卡片（KP2A 特性；ISSUE-P3-436 自 `DatabaseSettingsComponents.kt`
 * 整卡迁出，三行导出装配逐字未改）。
 *
 * **ISSUE-P3-436：第四行「导入密钥文件」**——为当前库换绑密钥文件第二因子的直达入口，
 * 与「导出密钥文件」对称（改主密钥对话框三态入口 §399 保留，本卡是浅埋直达路径）：
 *
 * 1. **SAF 选取** → 读取强制走全仓唯一通道 `KeyFileAccess`（经宿主透传的 [onReadKeyFile]，
 *    读取上限 / 流关闭 / 缓冲擦除 / 持久化授权全部由该通道承担），`Empty` / `Unreadable`
 *    显式弹窗反馈，绝不静默当成功（fail-closed，ISSUE-P3-04 口径）；
 * 2. **显式二次确认**：改绑 ＝ 全库 Argon2 重派生并保存、旧密钥文件此后不再能独立开库，
 *    未读到文件不渲染确认弹窗（禁提交）；
 * 3. **提交**复用既有仅改绑分支：[onKeyFileImport] →
 *    `SettingsMasterKeyChangeController.submit(空密码, Use(...))` → `changeKeyFileOnly`——
 *    忙守卫、字节 `finally` 清零、记忆位置同步（§434）与会话快照重封印（§430）全部既有；
 * 4. **敏感数据铁律**：待确认字节仅以 `ByteArray` 驻留 [PendingKeyFileImport]
 *    （不进任何 String / UiState），确认＝所有权随意图移交控制器，取消 / 换选＝就地清零；
 * 5. 改绑任务进行中（[keyFileImportBusy]）导入行禁用——Argon2 重派生是临界写，
 *    忙守卫在控制器内也兜底（并发提交被拒并擦除入参）。
 */
@Composable
internal fun DatabaseImportExportCard(
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onKeyFileExportClick: () -> Unit,
    // ISSUE-P3-436：导入密钥文件（读取走全仓唯一 SAF 通道；提交复用改主密钥任务的仅改绑分支）
    onReadKeyFile: suspend (String) -> KeyFileReadResult,
    onKeyFileImport: (ChangeKeyFileIntent) -> Unit,
    keyFileImportBusy: Boolean,
    keyFileImportFeedback: UiMessage? = null,
    onClearKeyFileImportFeedback: () -> Unit = {}
) {
    var pendingKeyFileImport by remember { mutableStateOf<PendingKeyFileImport?>(null) }
    var showKeyFileReadFailed by remember { mutableStateOf(false) }
    // ISSUE-P3-436：离场兜底——卡片离开组合（返回 / 切页）时未消费的待确认字节就地清零
    DisposableEffect(Unit) {
        onDispose {
            pendingKeyFileImport?.erase()
            pendingKeyFileImport = null
        }
    }
    val keyFileImportScope = rememberCoroutineScope()
    val importKeyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keyFileImportScope.launch {
            when (val outcome = onReadKeyFile(uri.toString())) {
                is KeyFileReadResult.Success -> {
                    pendingKeyFileImport?.erase()
                    pendingKeyFileImport = PendingKeyFileImport(
                        bytes = outcome.bytes,
                        sourceUri = uri.toString(),
                        displayName = outcome.displayName
                    )
                }
                // 「读不到」分型一律显式反馈，绝不静默当成功（fail-closed，ISSUE-P3-04 口径）
                KeyFileReadResult.Empty, KeyFileReadResult.Unreadable -> showKeyFileReadFailed = true
            }
        }
    }

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DatabaseActionRow(
                icon = Icons.Default.Download,
                title = stringResource(R.string.dbset_import_title),
                subtitle = stringResource(R.string.dbset_import_sub),
                onClick = onImportClick
            )

            DatabaseActionRow(
                icon = Icons.Default.Upload,
                title = stringResource(R.string.dbset_export_title),
                subtitle = stringResource(R.string.dbset_export_sub),
                onClick = onExportClick
            )

            DatabaseActionRow(
                icon = Icons.Default.VpnKey,
                title = stringResource(R.string.dbset_keyfile_export_title),
                subtitle = stringResource(R.string.dbset_keyfile_export_sub),
                onClick = onKeyFileExportClick
            )

            // ISSUE-P3-436：第四行——导入密钥文件（改绑进行中禁用）
            DatabaseActionRow(
                icon = Icons.Default.Key,
                title = stringResource(R.string.dbset_keyfile_import_title),
                subtitle = stringResource(R.string.dbset_keyfile_import_sub),
                onClick = { importKeyFileLauncher.launch(arrayOf("*/*")) },
                enabled = !keyFileImportBusy
            )

            keyFileImportFeedback?.let { feedback ->
                DatabaseFeedbackItem(
                    message = feedback,
                    isError = feedback.isError,
                    onClick = onClearKeyFileImportFeedback
                )
            }
        }
    }

    // ISSUE-P3-436：导入二次确认——未读到文件时本态为 null，弹窗不渲染（禁提交）
    pendingKeyFileImport?.let { picked ->
        KeyFileImportConfirmDialog(
            picked = picked,
            onConfirm = { intent ->
                pendingKeyFileImport = null
                onKeyFileImport(intent)
            },
            onDismiss = {
                picked.erase()
                pendingKeyFileImport = null
            }
        )
    }

    // ISSUE-P3-436：读取失败显式弹窗（复用解锁页文案；关闭即复位，无字节遗留）
    if (showKeyFileReadFailed) {
        AlertDialog(
            onDismissRequest = { showKeyFileReadFailed = false },
            title = { Text(stringResource(R.string.dbset_keyfile_import_title)) },
            text = { Text(stringResource(R.string.unlock_keyfile_read_failed)) },
            confirmButton = {
                TextButton(onClick = { showKeyFileReadFailed = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }
}

/**
 * 已读取、待确认的导入密钥文件（ISSUE-P3-436）。
 *
 * 字节仅以 `ByteArray` 驻留本类（不进任何 String / UiState / StateFlow）：
 * - **确认** = 字节所有权随意图移交控制器（[ChangeKeyFileIntent.Use] 借用语义，
 *   控制器 `finally` 统一清零），本态随即被调用方弃持（丢引用）；
 * - **取消 / 换选** = [erase] 就地清零后再弃持。
 */
internal class PendingKeyFileImport(
    val bytes: ByteArray,
    /** 来源 SAF Uri（非密钥元数据）：改绑成功后同步「记住的密钥文件位置」（§434）用 */
    val sourceUri: String,
    val displayName: String
) {
    /** 字节所有权随意图移交（借用语义：清零责任移交至控制器 `finally`） */
    fun toIntent(): ChangeKeyFileIntent = ChangeKeyFileIntent.Use(bytes, sourceUri, displayName)

    /** 就地清零密钥文件字节（取消 / 换选路径） */
    fun erase() {
        bytes.fill(0)
    }
}

/** ISSUE-P3-436：导入密钥文件二次确认弹窗（改绑＝重派生临界写 + 旧因子失效，必须显式知情） */
@Composable
private fun KeyFileImportConfirmDialog(
    picked: PendingKeyFileImport,
    onConfirm: (ChangeKeyFileIntent) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dbset_keyfile_import_warn_title)) },
        text = {
            Column {
                Text(
                    text = picked.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dbset_keyfile_import_warn_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked.toIntent()) }) {
                Text(stringResource(R.string.dbset_keyfile_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
