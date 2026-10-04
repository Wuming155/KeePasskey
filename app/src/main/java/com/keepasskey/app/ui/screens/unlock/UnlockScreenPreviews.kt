package com.keepasskey.app.ui.screens.unlock

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// （§392 自 UnlockScreen.kt 逐字搬移：该文件因 ISSUE-P3-427 新增一枚导航回调越入 400~500 档，
//  按 §391 同一范式把预览拆出降档，预览内容零改动）
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockContentPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockContent(
            uiState = UnlockUiState().copy(
                hasDatabase = true,
                databaseName = "Preview Vault.kdbx",
                databaseStatus = "Ready",
                unlockMode = UnlockMode.STANDARD,
                isQuickUnlockAvailable = true
            ),
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSelectKeyFile = {},
            onClearKeyFile = {},
            onToggleReadOnly = {},
            onSwitchMode = {},
            onUnlock = {},
            onBiometricUnlock = {},
            onDowngradeDecision = {},
            onNavigateToDatabasePicker = {},
            onOpenExistingVault = {}
        )
    }
}

// §438：极速解锁页此前**没有**页面级预览（只有 STANDARD 形态的 `UnlockContentPreview` 与卡片级预览），
// 而用户走查投诉的正是这一屏——动作卡落底锚定后，卡片与品牌区的相对位置在导出图上可直接查。
// 提示态取真机同形（截图为「上次会话未正常关闭」提示在场时拍的）。
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页（极速解锁）- 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页（极速解锁）- 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockContentQuickUnlockPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockContent(
            uiState = UnlockUiState().copy(
                hasDatabase = true,
                databaseName = "Preview Vault.kdbx",
                databaseStatus = "Ready",
                unlockMode = UnlockMode.QUICK_UNLOCK,
                lastSessionAbnormalCloseNotice = true
            ),
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSelectKeyFile = {},
            onClearKeyFile = {},
            onToggleReadOnly = {},
            onSwitchMode = {},
            onUnlock = {},
            onBiometricUnlock = {},
            onDowngradeDecision = {},
            onNavigateToDatabasePicker = {},
            onOpenExistingVault = {}
        )
    }
}

// ISSUE-P3-438：一次性轻提示态（上次会话未正常关闭）——预览画反向态，保证该态非真机不可见
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 会话未正常关闭提示 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 会话未正常关闭提示 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockContentAbnormalCloseNoticePreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockContent(
            uiState = UnlockUiState().copy(
                hasDatabase = true,
                databaseName = "Preview Vault.kdbx",
                databaseStatus = "Ready",
                unlockMode = UnlockMode.STANDARD,
                isQuickUnlockAvailable = true,
                lastSessionAbnormalCloseNotice = true
            ),
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSelectKeyFile = {},
            onClearKeyFile = {},
            onToggleReadOnly = {},
            onSwitchMode = {},
            onUnlock = {},
            onBiometricUnlock = {},
            onDowngradeDecision = {},
            onNavigateToDatabasePicker = {},
            onOpenExistingVault = {}
        )
    }
}

// ISSUE-P3-457：错误态 + 「已自动载入记住的密钥文件」info **同屏**——这正是真机走查拍到叠字的
// 组合（用户原话「自动加载密钥文件的提示文字和…取消重叠」；「取消」实为生物识别失败文案
// `sec_biometric_auth_failed` 的尾三字，非系统弹窗按钮）。该态此前**没有任何预览**，
// 于是「两行文案压在同一个 y 上」在编译期与预览导出图里全部隐形，只有真机肉眼能撞见。
// 补本态后，此槽的上下分离在预览图上直接可查（不变量由 check_box_slot_children.py 机检）。
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 错误与密钥文件 info 同屏 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 错误与密钥文件 info 同屏 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockContentErrorWithKeyFileInfoPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockContent(
            uiState = UnlockUiState().copy(
                hasDatabase = true,
                databaseName = "Preview Vault.kdbx",
                databaseStatus = "Ready",
                unlockMode = UnlockMode.STANDARD,
                isQuickUnlockAvailable = true,
                hasKeyFile = true,
                keyFileName = "usr.dat",
                // §433（ISSUE-P3-448 走查续）：新「来源」行只在确有来源时渲染——
                // 默认参数（null）的预览看不到它，故在此画真机同形的副本绝对路径（折叠态）
                keyFileSourcePath = PREVIEW_KEY_FILE_COPY_PATH,
                errorMessage = UiMessage(R.string.sec_biometric_auth_failed),
                infoMessage = UiMessage(R.string.keyfile_restored_from_memory, listOf("usr.dat"))
            ),
            onPasswordChange = {},
            onTogglePasswordVisibility = {},
            onSelectKeyFile = {},
            onClearKeyFile = {},
            onToggleReadOnly = {},
            onSwitchMode = {},
            onUnlock = {},
            onBiometricUnlock = {},
            onDowngradeDecision = {},
            onNavigateToDatabasePicker = {},
            onOpenExistingVault = {}
        )
    }
}

// §434 装机回执：密钥文件「来源」行的**展开态**此前没有任何预览——单行 + Ellipsis 在固定行宽下
// 永远截尾，「展开了仍看不全」于是只能靠真机撞见。补折叠 / 展开两态预览，使「换行铺满」在导出图上可直接查。
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 密钥文件来源（折叠） - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 密钥文件来源（折叠） - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun KeyFileSourceRowCollapsedPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        KeyFileSourceRowContent(path = PREVIEW_KEY_FILE_COPY_PATH, expanded = false, onToggle = {})
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 密钥文件来源（展开·换行铺满） - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁页 - 密钥文件来源（展开·换行铺满） - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun KeyFileSourceRowExpandedPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        KeyFileSourceRowContent(path = PREVIEW_KEY_FILE_COPY_PATH, expanded = true, onToggle = {})
    }
}

/** 真机同形的副本绝对路径（库 id 的 SHA-256 文件名，118 字符——单行必然截尾） */
private const val PREVIEW_KEY_FILE_COPY_PATH =
    "/data/user/0/com.keepasskey.debug/files/keyfiles/" +
        "08f8c3ef4ce4b5ea548d5bc5c445cc8a28501e93342268c96a00105ee84fd7c9.kfc"

// ===== §436 分组卡改版预览 =====

// ISSUE-P3-340 规则条文：折叠卡的 expanded 开关两态都要画——收起态下密钥文件 / 只读开关
// 全部不可见，若只画展开态，收起态的间距与头部徽章只有真机能撞见
@androidx.compose.ui.tooling.preview.Preview(name = "高级认证凭证卡（展开）- 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "高级认证凭证卡（展开）- 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(name = "高级认证凭证卡（收起）- 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "高级认证凭证卡（收起）- 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockAdvancedAuthCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
            UnlockAdvancedAuthCard(
                hasKeyFile = true,
                keyFileName = "hellisllords.keyx",
                keyFileSourcePath = PREVIEW_KEY_FILE_COPY_PATH,
                onSelectKeyFile = {},
                onClearKeyFile = {},
                openReadOnly = false,
                onToggleReadOnly = {},
                expanded = true,
                onToggleExpand = {}
            )
            // 收起态 + 无密钥文件（徽章不呈现的反向态）
            UnlockAdvancedAuthCard(
                hasKeyFile = false,
                keyFileName = "",
                keyFileSourcePath = null,
                onSelectKeyFile = {},
                onClearKeyFile = {},
                openReadOnly = true,
                onToggleReadOnly = {},
                expanded = false,
                onToggleExpand = {}
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "分组卡（数据库行 + 主密码行）- 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "分组卡（数据库行 + 主密码行）- 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockVaultGroupCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockVaultGroupCard(
            databaseRow = {
                UnlockDatabaseRow(
                    name = "phellords.kdbx",
                    status = "本地存储 · 42 个凭据",
                    onOpen = {},
                    onSwitchTap = {}
                )
            },
            passwordRow = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    com.keepasskey.app.ui.components.SecurePasswordField(
                        label = null,
                        placeholder = "输入主密码",
                        onPasswordChanged = {},
                        isPasswordVisible = false,
                        onToggleVisibility = {},
                        leadingIcon = Icons.Default.Lock,
                        embeddedFlat = true
                    )
                }
            }
        )
    }
}

/**
 * `ISSUE-P3-472` AC③：标准解锁页主密码行「未聚焦 / 聚焦 × 空值」两态预览（`ISSUE-P3-340` 口径）。
 *
 * 此前该行**只有** `UnlockVaultGroupCardPreview` 一态（未复刻生产的 `.height(48.dp)` 约束），
 * 而 §438 走查在**生产形态**（`UnlockStandardUnlockContent`）的导出图上扫到该行「零文案像素」——
 * 「48dp 高度上限」与「聚焦态」两个变量混在一起无法归因。故此处逐项复刻生产形态：
 * 同样的 `UnlockVaultGroupCard` 嵌套、同样的 `placeholder = unlock_master_password_hint`、
 * 同样的 `.height(48.dp)` 紧凑档（§436 走查回执①），只把「聚焦」做成两张图。
 *
 * 聚焦态经 `FocusRequester` + `LaunchedEffect` 强制取得（`SecurePasswordField` 不暴露焦点参数，
 * 焦点由 M3 内部 `InteractionSource` 驱动）；预览渲染器若不执行该效果，两张图会等价——
 * 这正是**必须真机先行**（`ISSUE-P3-472` AC①）的原因，不得据此推定为渲染器限界。
 */
@androidx.compose.ui.tooling.preview.Preview(name = "主密码行（未聚焦 + 空值）- 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "主密码行（未聚焦 + 空值）- 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockPasswordRowUnfocusedPreview() {
    UnlockPasswordRowStatePreview(focused = false)
}

@androidx.compose.ui.tooling.preview.Preview(name = "主密码行（聚焦 + 空值）- 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "主密码行（聚焦 + 空值）- 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockPasswordRowFocusedPreview() {
    UnlockPasswordRowStatePreview(focused = true)
}

@Composable
private fun UnlockPasswordRowStatePreview(focused: Boolean) {
    val focusRequester = remember { FocusRequester() }
    if (focused) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        UnlockVaultGroupCard(
            databaseRow = {
                UnlockDatabaseRow(
                    name = "phellords.kdbx",
                    status = "本地存储 · 42 个凭据",
                    onOpen = {},
                    onSwitchTap = {}
                )
            },
            passwordRow = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    com.keepasskey.app.ui.components.SecurePasswordField(
                        label = null,
                        placeholder = androidx.compose.ui.res.stringResource(R.string.unlock_master_password_hint),
                        onPasswordChanged = {},
                        isError = false,
                        supportingText = null,
                        isPasswordVisible = false,
                        onToggleVisibility = {},
                        onDone = {},
                        wipeToken = null,
                        leadingIcon = Icons.Default.Lock,
                        embeddedFlat = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            }
        )
    }
}
