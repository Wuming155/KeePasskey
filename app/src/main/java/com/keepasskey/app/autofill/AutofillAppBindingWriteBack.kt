package com.keepasskey.app.autofill

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R

/**
 * 「记住这个应用与该条目的关联？」询问载荷（`ISSUE-P3-571` 方案 A，Kp2a 式询问）。
 *
 * 三者均为**非敏感标识**：`entryTitle` / `proposedUrl`（`android://<包名>`）是用户在
 * 询问对话框上**本就会看到**的内容，`entryId` 仅为回调主键——不落凭据值，无须脱敏 `toString`。
 * 可见性为 public：作为 [AutofillPickerViewModel.bindingWriteBackAsk]（public StateFlow）与
 * `AutofillPickerScreen`（public Composable）的参数类型，不得低半级暴露。
 */
data class AppBindingWriteBackAsk(
    val entryId: String,
    val entryTitle: String,
    val proposedUrl: String
)

/**
 * 「把调用方应用关联写进条目 URL」的**纯判定内核**（`ISSUE-P3-571` 方案 A；无 Android 依赖，JVM 可直测）。
 *
 * 竞品依据（`docs/references/自动填充关联记忆与字段识别的参考项目对照.md` §2 / §6）：
 * Kp2a 在自动填充流内询问「Remember search text?」并把 `androidapp://<包名>` 写进条目 URL；
 * KeePassDX 同语义地写条目本体（`AndroidApp` 自定义字段）。本仓在**手动选择器交付链内**询问，
 * 显式同意才写回 `android://<包名>`——关联随 `.kdbx` 走，换设备 / 重装不失效
 * （与 `ISSUE-P3-528`（§468）落地的本地互动记忆 [AutofillCallerEntryMemory] 并存：后者是
 * 本机快速通道，本写回是随库持久通道）。
 *
 * ## 四否决与「仅空 URL」口径
 *
 * 与应用内搜索侧 `SearchWriteBackPolicy.shouldOffer` 的四否决（只读 / 回收站 / `{REF}` / 已覆盖）
 * **同形且不弱于**：
 * - **只读**：`sessionReadOnly` 一票否决（回收站否决由候选面天然成立——选择器列出的条目来自
 *   `getUsableKdbxEntries()`（P2-341，回收站子树排除），手选交付的条目不可能在回收站内）；
 * - **`{REF}` 与「已覆盖」被「仅空 URL」严格蕴含**：`isAndroidPackageMatch` 语义是
 *   「URL 整段＝`android://<包名>`」的**单值绑定**（[com.keepasskey.app.passkey.DomainMatcher.isAndroidPackageMatch]），
 *   写回走 [com.keepasskey.app.data.repository.VaultEntryUrlWriter.updateUrl] 整段替换且**不产生历史修订**——
 *   任何非空 URL（`https://…` 的 Web 绑定、其它应用的 `android://` 绑定）被替换即静默撤销原绑定且不可回溯，
 *   故仅在**当前 URL 为空**的条目上提供写回。空 URL 不可能含 `{REF}`，也不可能已绑定 ⇒ 两否决蕴含成立；
 * - **包名非法 / 签名摘要不可读**：不询问也不产生降级写入（fail-closed，与 `AndroidPackageBindingPolicy`
 *   的「摘要不可读即不放行」同口径——「只认包名」正是 P2-46 要消灭的形态）；
 * - **浏览器表单（`webDomain` 非空）不在本条**：其域维度由归属校验通道与 CM 保存链承载，
 *   方案 A 询问的「应用关联」仅指纯 App 表单（用户 2026-10-11 点名的 QQ 形态）。
 *
 * 写入形态唯一来自 [AutofillPackageNames.boundUrl]（写入侧与 `isAndroidPackageMatch` 判据侧
 * 逐字同源，杜绝「写进去却匹配不上」），消费侧零改动。
 */
internal object AutofillAppBindingWriteBackPolicy {

    /**
     * 将要写入的绑定 URL（`android://<归一化包名>`）。
     * 包名非法（[AutofillPackageNames.normalize] 为 null）返回 null——调用方据此跳过询问。
     */
    fun proposedBindingUrl(callingPackage: String): String? =
        AutofillPackageNames.normalize(callingPackage)?.let(AutofillPackageNames::boundUrl)

    /**
     * 是否向用户提出「记住这个应用与该条目的关联？」。
     *
     * @param callingPackage 系统背书的调用方包名（来自交付链 `EXTRA_CALLING_PACKAGE`）
     * @param callerDigestsReadable 调用方签名摘要快照是否可读（`!CallerCertDigests.isEmpty`）
     * @param webDomain 表单自报域（空 / null ＝纯 App 表单）
     * @param entryUrl 条目当前 URL（交付链按 id 现读）
     * @param sessionReadOnly 只读会话一票否决（回收站否决由候选面 P2-341 天然成立）
     */
    fun shouldOffer(
        callingPackage: String,
        callerDigestsReadable: Boolean,
        webDomain: String?,
        entryUrl: String,
        sessionReadOnly: Boolean
    ): Boolean {
        if (sessionReadOnly) return false
        if (proposedBindingUrl(callingPackage) == null) return false
        if (!callerDigestsReadable) return false
        if (!webDomain.isNullOrBlank()) return false
        if (entryUrl.isNotBlank()) return false
        return true
    }
}

/**
 * 「记住这个应用？」询问对话框（`ISSUE-P3-571` 方案 A）。
 *
 * 状态与等待桥在 [AutofillPickerViewModel]（交付链挂起等待用户处置）；本组合体只负责渲染——
 * 任何路径离开对话框（取消按钮 / 点外部 / 返回键）一律按**拒绝**处置（不写回、照常交付）；
 * 拒绝不持久化为「永久拒绝」（与应用内搜索侧 `VaultSearchWriteBackCoordinator` 的会话内抑制不同，
 * 本询问每次交付至多出现一次，重复频率由关联记忆的命中自然收敛）。
 *
 * 按钮文案复用应用内搜索写回对话框的既有词条（「写入」/「取消」）——两通道同一动作语义。
 */
@Composable
internal fun AppBindingWriteBackDialog(
    ask: AppBindingWriteBackAsk,
    onResult: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text(stringResource(R.string.autofill_app_binding_write_back_title)) },
        text = {
            Text(
                stringResource(
                    R.string.autofill_app_binding_write_back_message,
                    ask.proposedUrl,
                    ask.entryTitle
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onResult(true) }) {
                Text(stringResource(R.string.search_write_back_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(false) }) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
