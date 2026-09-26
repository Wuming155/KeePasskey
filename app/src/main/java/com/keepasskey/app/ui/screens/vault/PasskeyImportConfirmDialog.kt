package com.keepasskey.app.ui.screens.vault

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.passkey.PasskeyCxfDisplayField
import com.keepasskey.app.passkey.PasskeyCxfDropped
import com.keepasskey.app.passkey.PasskeyCxfSourceShape
import com.keepasskey.app.passkey.PasskeyImportDraft
import com.keepasskey.app.security.SecureDialogWindowEffect

/**
 * 扫码导入通行密钥的**导入前确认**对话框（`ISSUE-P3-337` Q2 + 口径 4 / 4′，AC⑥⑩⑪）。
 *
 * ## 为什么必须存在而不是「导入后告知」
 *
 * 顶栏扫到的是**别人导出的私钥**：静默建条目等于让一张二维码决定库里多出什么。
 * 同时载荷可能带着本库不承载的扩展（`credBlob` / `largeBlob` / `payments`）、
 * 或文档里还有别的凭据——同类实现（fenris 的 `IncompatibleItem` → `ConfirmImportSheet`）
 * 的「先列清单、确认后才导」比「导入后告知」更强 ⇒ 两类信息**合并进同一个对话框**
 * （不新增第二个对话框，也不新增 `FLAG_SECURE` 账目类别）。
 *
 * ## 呈现纪律
 *
 * - 只呈现**非敏感元数据**：rpId、类型、拟用条目标题、不会导入的扩展、未导入把数、
 *   「来源未提供」标注；**不回显 `credentialId` / `userHandle` / 私钥**，也不显示载荷原文
 *   （[PasskeyImportDraft] 只活在内存里）；
 * - 文案口径（`§292`）：类型一律写「FIDO2 软件密钥（库内加密存储）」，**禁**「芯片 / 硬件」表述；
 * - `FLAG_SECURE` **跟随设置开关**（`PD-48` 裁决三，与 `PD-47` 的扫码取景同源），
 *   故它**不属于** `SecureDialogFlagPolicyTest` 清单里那 4 类无条件强制遮罩对话框；
 *   反 overlay 与点击劫持过滤两层由 [SecureDialogWindowEffect] 始终施加。
 */
@Composable
internal fun PasskeyImportConfirmDialog(
    draft: PasskeyImportDraft,
    flagSecureEnabled: Boolean,
    /**
     * 「替换当前条目」语义（`ISSUE-P3-337` Q1 的编辑页入口）：正文第三行改说替换、
     * 确认按钮改说「替换」。顶栏新建分支为默认的 false。
     */
    replacesEntry: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    SecureDialogWindowEffect(flagSecure = flagSecureEnabled)
    val labels = passkeyImportLabels()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passkey_import_confirm_title)) },
        text = { Text(passkeyImportSummary(draft, replacesEntry) { labels.getValue(it) }) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (replacesEntry) R.string.passkey_import_action_replace else R.string.passkey_import_action
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/**
 * 一次性解析确认对话框要用的全部文案。
 *
 * 为什么不用 `LocalContext.current.getString(...)`：lint 的 `LocalContextGetResourceValueCall`
 * 明令 Compose 里取字符串资源要走 `stringResource`（否则文案不随配置变更重组）——本批首版正是
 * 这么写的，被 `lint` 当场判为 error（读数见条目留痕）。集中解析后交给纯函数查表，
 * 既能机检正文结构，又不违反该规则。
 */
@Composable
private fun passkeyImportLabels(): Map<Int, String> = mapOf(
    R.string.passkey_import_site to stringResource(R.string.passkey_import_site),
    R.string.passkey_import_kind to stringResource(R.string.passkey_import_kind),
    R.string.passkey_import_entry_title to stringResource(R.string.passkey_import_entry_title),
    R.string.passkey_import_replace_target to stringResource(R.string.passkey_import_replace_target),
    R.string.passkey_import_dropped to stringResource(R.string.passkey_import_dropped),
    R.string.passkey_import_extra_credentials to stringResource(R.string.passkey_import_extra_credentials),
    R.string.passkey_import_source_missing to stringResource(R.string.passkey_import_source_missing),
    R.string.passkey_import_source_incomplete_prf to stringResource(R.string.passkey_import_source_incomplete_prf),
    R.string.passkey_import_drop_cred_blob to stringResource(R.string.passkey_import_drop_cred_blob),
    R.string.passkey_import_drop_large_blob to stringResource(R.string.passkey_import_drop_large_blob),
    R.string.passkey_import_drop_payments to stringResource(R.string.passkey_import_drop_payments),
    R.string.passkey_import_drop_hmac_unknown to stringResource(R.string.passkey_import_drop_hmac_unknown),
    R.string.passkey_import_missing_username to stringResource(R.string.passkey_import_missing_username),
    R.string.passkey_import_missing_display_name to stringResource(R.string.passkey_import_missing_display_name)
)

/**
 * 顶栏扫码导入的**确认环节宿主**（从 `VaultListScreen` 收拢过来，只为让那一页停在行数分档线内；
 * 职责仍是同一单元：草案呈现 → 确认 / 取消 → 一次性导航意图消费）。
 *
 * 一次性意图消费后即清空（[VaultListViewModel.consumeOpenEntryEditId]），否则返回该页会重复跳转。
 * 路由**只带条目 id**——私钥等凭据值一律不经路由承载（口径 4 / `P2-105`）。
 */
@Composable
internal fun PasskeyImportConfirmationHost(
    viewModel: VaultListViewModel,
    flagSecureEnabled: Boolean,
    onNavigateToEntryEdit: (String) -> Unit
) {
    val draft by viewModel.pendingPasskeyImport.collectAsStateWithLifecycle()
    draft?.let {
        PasskeyImportConfirmDialog(
            draft = it,
            flagSecureEnabled = flagSecureEnabled,
            onConfirm = viewModel::confirmPasskeyImport,
            onDismiss = viewModel::dismissPasskeyImport
        )
    }
    val entryIdToOpen by viewModel.openEntryEditId.collectAsStateWithLifecycle()
    LaunchedEffect(entryIdToOpen) {
        entryIdToOpen?.let {
            onNavigateToEntryEdit(it)
            viewModel.consumeOpenEntryEditId()
        }
    }
}

/**
 * 确认对话框正文的纯函数形态（[text] = 资源 → 文案）：拆出来是为了让**宿主单测**能逐条断言
 * 「点名了什么、没点名什么」——这类文案纪律在截图测试里看不见，必须可机检。
 *
 * ⚠️ 两行语义**不得混写**（`PD-48` 裁决二 + AC⑪②）：
 * - 「不会导入」行只列**本库不承载或按规范丢弃的扩展项**（`credBlob` / `largeBlob` /
 *   `payments(true)` / 算法值无法识别的 PRF 项）；
 * - `credWithoutUV` 是**全量存入** `Passkey.PrfNoUv` 的，**不得**出现在该行；
 *   「规范形缺一枚种子」属**来源缺陷**，与「用户名 / 显示名来源未提供」同归「来源」行。
 *
 * [replacesEntry] = 编辑页 Q1 的替换语义：第三行不说「拟用条目标题」（标题根本不会变，
 * 说了就是撒谎），改说「在本条目上替换通行密钥，其余内容不动」——与
 * [com.keepasskey.app.data.repository.PasskeyEntryCoordinator.replacePasskeyOnEntry]
 * 的实际行为（非 passkey 字段按引用保留）逐字对齐。
 */
internal fun passkeyImportSummary(
    draft: PasskeyImportDraft,
    replacesEntry: Boolean = false,
    text: (Int) -> String
): String {
    val credential = draft.credential
    val notes = draft.notes
    return buildString {
        line(text(R.string.passkey_import_site), credential.relyingPartyId)
        line(text(R.string.passkey_import_kind))
        if (replacesEntry) {
            line(text(R.string.passkey_import_replace_target))
        } else {
            line(text(R.string.passkey_import_entry_title), "${credential.userName}@${credential.relyingPartyId}")
        }
        if (notes.shape == PasskeyCxfSourceShape.KeePassXCPasskeyFile) {
            // 兼容形的字段集从未按 CXF 对齐 ⇒ 标注来源，不让用户以为「规范给了空值」
            append(" · KeePassXC")
        }
        val notImported = notes.dropped.filterNot { it == PasskeyCxfDropped.HmacIncomplete }
        if (notImported.isNotEmpty()) {
            line(text(R.string.passkey_import_dropped), notImported.joinToString("、") { text(it.resId()) })
        }
        if (notes.additionalPasskeyCount > 0) {
            line(text(R.string.passkey_import_extra_credentials), notes.additionalPasskeyCount)
        }
        val sourceGaps = buildList {
            notes.missingDisplayFields.forEach { add(text(it.resId())) }
            if (notes.dropped.contains(PasskeyCxfDropped.HmacIncomplete)) {
                add(text(R.string.passkey_import_source_incomplete_prf))
            }
        }
        if (sourceGaps.isNotEmpty()) {
            line(text(R.string.passkey_import_source_missing), sourceGaps.joinToString("、"))
        }
    }
}

/** 逐行拼装（行间以换行分隔）。 */
private fun StringBuilder.line(label: String, value: Any? = null) {
    if (isNotEmpty()) append('\n')
    append(if (value == null) label else label.formatPlaceholder(value))
}

/** 单占位符替换；测试侧可传不含占位符的裸标签，此时直接拼接（不为测试在生产串里做两套分支）。 */
private fun String.formatPlaceholder(value: Any): String =
    if (contains("%1")) format(value) else this + value

/**
 * 丢弃 / 缺陷项 → 文案。
 *
 * 这里**没有** `credWithoutUV` 对应项：第二枚种子是存下来的（`Passkey.PrfNoUv`）；
 * [PasskeyCxfDropped.HmacIncomplete] 由 [passkeyImportSummary] 归「来源」行，不进「不会导入」行。
 */
private fun PasskeyCxfDropped.resId(): Int = when (this) {
    PasskeyCxfDropped.CredBlob -> R.string.passkey_import_drop_cred_blob
    PasskeyCxfDropped.LargeBlob -> R.string.passkey_import_drop_large_blob
    PasskeyCxfDropped.Payments -> R.string.passkey_import_drop_payments
    PasskeyCxfDropped.HmacUnknownAlgorithm -> R.string.passkey_import_drop_hmac_unknown
    PasskeyCxfDropped.HmacIncomplete -> R.string.passkey_import_source_incomplete_prf
}

/** 「来源未提供」的展示字段 → 文案。 */
private fun PasskeyCxfDisplayField.resId(): Int = when (this) {
    PasskeyCxfDisplayField.UserName -> R.string.passkey_import_missing_username
    PasskeyCxfDisplayField.UserDisplayName -> R.string.passkey_import_missing_display_name
}
