package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.ChildDatabaseUiState
import com.keepasskey.app.ui.screens.settings.KdfBenchmarkUiState
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 密码库属性与加密参数配置二级页面 (整合 KeePass2Android 与 KeePassDX 全部属性)
 *
 * ISSUE-P3-467：数据导入与导出（含密钥文件导入/导出与全部 SAF 导出链路）已**纯迁位**
 * 拆至 `ImportExportSettingsScreen`（路由 `settings/import_export`）——动作流与配置域分离；
 * 本页保留设库一次、长期不变的属性面：常规属性 / 加密与 KDF / 模板与子库 / 完整性。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onRecycleBinToggle: (Boolean) -> Unit,
    onEncryptionAlgorithmChange: (String) -> Unit = {},
    onKdfAlgorithmChange: (String) -> Unit = {},
    onArgon2ParametersChange: (iterations: Long, memoryMb: Long, parallelism: Int) -> Unit = { _, _, _ -> },
    // ISSUE-P3-385：库级 Meta（库名 / 描述 / 默认用户名）编辑回调
    onDatabaseMetaChange: (databaseName: String?, databaseDescription: String?, defaultUserName: String?) -> Unit = { _, _, _ -> },
    // M6 整改：真实 KDF 基准状态与触发（原按钮仅展示假完成消息）
    kdfBenchmarkState: KdfBenchmarkUiState? = null,
    onRunKdfBenchmark: () -> Unit = {},
    onInstallTemplates: () -> Unit = {},
    // ISSUE-P3-20：子库挂载（核心层 ChildDatabaseSessionManager 已落地，本屏为真实入口）。
    // 状态与动作全部上抬自 SettingsViewModel；本屏只维护 SAF 选择结果与表单开关，
    // 不含任何解密/挂载业务逻辑。
    childDatabaseState: ChildDatabaseUiState = ChildDatabaseUiState(),
    onMountChildDatabase: (
        alias: String,
        sourceUri: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) -> Unit = { _, _, _, _ -> },
    onUnlockChildDatabase: (
        mountId: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) -> Unit = { _, _, _ -> },
    onUnmountChildDatabase: (mountId: String) -> Unit = {},
    onChildDatabaseFeedbackDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCipherDialog by remember { mutableStateOf(false) }
    var showKdfDialog by remember { mutableStateOf(false) }
    var showArgon2Dialog by remember { mutableStateOf(false) }
    var showTemplatesDialog by remember { mutableStateOf(false) }
    var showChildDbDialog by remember { mutableStateOf(false) }
    // ISSUE-P3-385：库级 Meta 编辑对话框
    var showMetaDialog by remember { mutableStateOf(false) }

    SettingsSubscreenScaffold(
        titleRes = R.string.settings_database,
        onBackClick = onBackClick,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 常规与基础属性
            item { SectionHeader(title = stringResource(R.string.dbset_section_basic)) }
            item {
                DatabaseBasicCard(
                    uiState = uiState,
                    onRecycleBinToggle = onRecycleBinToggle,
                    // ISSUE-P3-385：库级 Meta 可编辑入口
                    onMetaEditClick = { showMetaDialog = true }
                )
            }

            // 2. 密码学与 KDF 派生
            item { SectionHeader(title = stringResource(R.string.dbset_section_crypto)) }
            item {
                DatabaseCryptoCard(
                    uiState = uiState,
                    onCipherClick = { showCipherDialog = true },
                    onKdfClick = { showKdfDialog = true },
                    onArgonClick = { showArgon2Dialog = true }
                )
            }

            // 3. 条目模板库与子数据库配置 (KP2A 特性)
            item { SectionHeader(title = stringResource(R.string.dbset_section_extensions)) }
            item {
                DatabaseExtensionsCard(
                    childDatabasesCount = uiState.childDatabasesCount,
                    onTemplatesClick = { showTemplatesDialog = true },
                    onChildDbClick = { showChildDbDialog = true }
                )
            }

            // 4. 完整性与高级规则 (KP2A 特性)
            item { SectionHeader(title = stringResource(R.string.dbset_section_integrity)) }
            item {
                DatabaseIntegrityCard()
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 对话框 1：加密算法选择
    if (showCipherDialog) {
        CipherAlgorithmDialog(
            currentAlgorithm = uiState.encryptionAlgorithm,
            onSelect = onEncryptionAlgorithmChange,
            onDismiss = { showCipherDialog = false }
        )
    }

    // ISSUE-P3-385：库级 Meta 编辑对话框（库名 / 描述 / 默认用户名，写 Meta + 立即落盘）
    if (showMetaDialog) {
        DatabaseMetaEditDialog(
            databaseName = uiState.databaseName,
            databaseDescription = uiState.databaseDescription,
            defaultUserName = uiState.databaseDefaultUsername,
            onSave = { name, desc, user ->
                onDatabaseMetaChange(name, desc, user)
                showMetaDialog = false
            },
            onDismiss = { showMetaDialog = false }
        )
    }

    // 对话框 2：KDF 密钥派生算法选择
    if (showKdfDialog) {
        KdfAlgorithmDialog(
            currentAlgorithm = uiState.kdfAlgorithm,
            onSelect = onKdfAlgorithmChange,
            onDismiss = { showKdfDialog = false }
        )
    }

    // 对话框 3：Argon2 参数详细调节
    if (showArgon2Dialog) {
        Argon2ParametersDialog(
            uiState = uiState,
            kdfBenchmarkState = kdfBenchmarkState,
            onRunKdfBenchmark = onRunKdfBenchmark,
            onApplyParameters = onArgon2ParametersChange,
            onDismiss = { showArgon2Dialog = false }
        )
    }

    // 对话框 4：模板库管理对话框
    if (showTemplatesDialog) {
        DatabaseTemplatesDialog(
            onInstallTemplates = onInstallTemplates,
            onDismiss = { showTemplatesDialog = false }
        )
    }

    // 对话框 5 / 5b：子库挂载与凭据补录（§159 下沉至 ChildDatabaseSection，四项状态由该段自持）
    ChildDatabaseSection(
        state = childDatabaseState,
        showDialog = showChildDbDialog,
        onDialogDismiss = { showChildDbDialog = false },
        onMount = onMountChildDatabase,
        onUnlock = onUnlockChildDatabase,
        onUnmount = onUnmountChildDatabase,
        onFeedbackDismiss = onChildDatabaseFeedbackDismiss
    )
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "密码库属性设置页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "密码库属性设置页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun DatabaseSettingsScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        DatabaseSettingsScreen(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState().copy(
                databaseName = "预览示例密码库",
                databasePath = "/预览目录/预览示例.kdbx",
                databaseDefaultUsername = "demo@example.com"
            ),
            onBackClick = {},
            onRecycleBinToggle = {}
        )
    }
}
