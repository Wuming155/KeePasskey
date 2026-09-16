package com.keepasskey.app.autofill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.core.model.KdbxEntry

/**
 * ISSUE-P2-70（审计 E1）：选择器页强制展示的**请求方身份**。
 *
 * 缺陷形态：自动匹配路径有严格边界（域 / 包名匹配 + 指纹白名单 / DAL 校验），而**手动兜底选择器**
 * 可把**任意条目**的凭据交给请求方，且页面上不显示「谁在请求」——用户在无任何归属信息的情况下
 * 完成一次填充授权。
 *
 * 字段的可信度分层（与 `AutofillCallerAttribution` 一致，展示文案必须如实区分）：
 * - [packageName] / [certSha256Hex]：**不可伪造锚点**（系统结构树提供 + 本应用经 PackageManager 读取）；
 * - [appLabel]：**可被应用自声明**，仅作辅助识别，不构成归属依据；
 * - [reportedDomain]：**表单自报且未经归属校验**（本页只是把所有权的判断交还用户），
 *   与确认页展示的「已经归属校验」的域**语义不同**，故文案单独区分。
 */
data class AutofillPickerRequester(
    val packageName: String,
    val appLabel: String?,
    val certSha256Hex: String?,
    val reportedDomain: String?
)

/**
 * 构造请求方展示模型（纯函数，便于单测）。
 *
 * @return 包名为空（异常启动路径 / extra 缺失）时返回 null——此时无归属可展示，
 *   调用方应保持既有流程（不展示归属块，也不伪造「未知应用」这类占位锚点）。
 */
fun buildAutofillPickerRequester(
    packageName: String?,
    appLabel: String?,
    certSha256Hex: String?,
    reportedDomain: String?
): AutofillPickerRequester? {
    val pkg = packageName?.trim().orEmpty()
    if (pkg.isEmpty()) return null
    return AutofillPickerRequester(
        packageName = pkg,
        appLabel = appLabel?.trim()?.takeIf { it.isNotEmpty() },
        certSha256Hex = certSha256Hex?.trim()?.takeIf { it.isNotEmpty() },
        reportedDomain = reportedDomain?.trim()?.takeIf { it.isNotEmpty() }
    )
}

/**
 * 自动填充「手动选择器」界面（ISSUE-P3-40）。
 *
 * 自动匹配无候选或候选不含目标条目时的兜底入口：用户可搜索全库条目并选择填充。
 *
 * 零秘密热路径：列表只渲染标题 / 用户名 / 网址等**非敏感元数据**，
 * 密码在用户点选某一行后才由 [AutofillPickerViewModel.resolveCredentials] 按需解密。
 *
 * ISSUE-P2-70：顶部**强制**展示 [requester]（请求方包名 + 应用名 + 签名摘要 + 表单自报域）——
 * 兜底路径下用户至少能看到「谁在要凭据」再决定是否放行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillPickerScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<KdbxEntry>,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
    // ISSUE-P2-70：请求方身份（null = 无法识别归属，不展示归属块）
    requester: AutofillPickerRequester? = null,
    // ISSUE-P3-43 ②：字段签名级屏蔽入口。仅在本次请求识别到对应框时可用，
    // 否则不呈现（杜绝无对象的假按钮）
    canBlockUsername: Boolean = false,
    canBlockPassword: Boolean = false,
    onBlockField: (AutofillFieldRole) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pendingBlockRole by remember { mutableStateOf<AutofillFieldRole?>(null) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.autofill_picker_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )

            // ISSUE-P2-70：请求方身份**强制展示**（置于搜索框之前，无需滚动即可见）
            requester?.let { AutofillPickerRequesterBlock(it) }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.autofill_picker_query_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ISSUE-P3-43 ②：字段级屏蔽入口（点击后先经确认弹窗，避免误触导致后续不再填充）
            if (canBlockUsername || canBlockPassword) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    if (canBlockUsername) {
                        TextButton(onClick = { pendingBlockRole = AutofillFieldRole.USERNAME }) {
                            Text(stringResource(R.string.autofill_picker_block_username))
                        }
                    }
                    if (canBlockPassword) {
                        TextButton(onClick = { pendingBlockRole = AutofillFieldRole.PASSWORD }) {
                            Text(stringResource(R.string.autofill_picker_block_password))
                        }
                    }
                }
            }

            if (results.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.autofill_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = results, key = { it.id.toHexString() }) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry.id.toHexString()) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = entry.title.ifBlank { entry.userName },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val subtitle = entry.userName.ifBlank { entry.url }
                            if (subtitle.isNotBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 屏蔽确认弹窗：如实说明「同一应用 + 同一网址 + 同一角色」不再填充，且可在设置中整体清除
    pendingBlockRole?.let { role ->
        AlertDialog(
            onDismissRequest = { pendingBlockRole = null },
            title = { Text(stringResource(R.string.autofill_picker_block_confirm_title)) },
            text = { Text(stringResource(R.string.autofill_picker_block_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingBlockRole = null
                    onBlockField(role)
                }) {
                    Text(stringResource(R.string.autofill_picker_block_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBlockRole = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/**
 * ISSUE-P2-70：请求方身份展示块（不可伪造锚点优先，非权威信息显式降级标注）。
 *
 * 行序即可信度序：包名（系统背书）→ 应用名（应用可自声明）→ 签名证书 SHA-256（不可读时如实标注）
 * → 表单自报域（**未**通过归属校验，故文案不沿用确认页的「已经归属校验」措辞）。
 */
@Composable
private fun AutofillPickerRequesterBlock(requester: AutofillPickerRequester) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.autofill_picker_requester_title),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = stringResource(R.string.autofill_confirm_caller_package, requester.packageName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            requester.appLabel?.let {
                Text(
                    text = stringResource(R.string.autofill_picker_requester_label, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = requester.certSha256Hex
                    ?.let { stringResource(R.string.autofill_confirm_caller_cert, it) }
                    ?: stringResource(R.string.autofill_confirm_cert_unreadable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = requester.reportedDomain
                    ?.let { stringResource(R.string.autofill_picker_requester_domain, it) }
                    ?: stringResource(R.string.autofill_picker_requester_domain_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "自动填充手动选择器 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "自动填充手动选择器 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
internal fun AutofillPickerScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        AutofillPickerScreen(
            query = "预览",
            onQueryChange = {},
            // 预览夹具就地构造：仅标题 / 用户名等非敏感元数据，凭据字段一律不构造
            results = listOf(
                com.keepasskey.core.model.KdbxEntry()
                    .withField(com.keepasskey.core.model.KdbxConstants.Fields.TITLE, "预览登录条目")
                    .withField(
                        com.keepasskey.core.model.KdbxConstants.Fields.USER_NAME,
                        "demo@example.com"
                    ),
                com.keepasskey.core.model.KdbxEntry()
                    .withField(com.keepasskey.core.model.KdbxConstants.Fields.TITLE, "预览便签条目")
            ),
            onPick = {},
            onCancel = {},
            requester = AutofillPickerRequester(
                packageName = "com.example.preview",
                appLabel = "预览请求方应用",
                certSha256Hex = "预览签名摘要（占位，非真实证书）",
                reportedDomain = "example.com"
            ),
            canBlockUsername = true,
            canBlockPassword = true,
            onBlockField = {}
        )
    }
}
