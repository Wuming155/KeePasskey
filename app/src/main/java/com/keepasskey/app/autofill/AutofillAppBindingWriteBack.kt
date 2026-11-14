package com.keepasskey.app.autofill

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R
import com.keepasskey.app.security.TrackDialogInteractionEffect

/**
 * 「记住这个应用与该条目的关联？」询问载荷（KeePassDX 口径，随库持久通道）。
 *
 * 三者均为**非敏感标识**：`entryTitle` / `proposedPackage`（纯包名，不带 `android://`）是用户在
 * 询问对话框上**本就会看到**的内容，`entryId` 仅为回调主键——不落凭据值，无须脱敏 `toString`。
 * 可见性为 public：作为 [AutofillPickerViewModel.bindingWriteBackAsk]（public StateFlow）与
 * `AutofillPickerScreen`（public Composable）的参数类型，不得低半级暴露。
 */
data class AppBindingWriteBackAsk(
    val entryId: String,
    val entryTitle: String,
    val proposedPackage: String
)

/**
 * 是否提出「记住这个应用与该条目的关联？」的**纯判定内核**（无 Android 依赖，JVM 可直测）。
 *
 * ## 现行口径（PD-83 改写后）
 *
 * 关联写入的是条目的 `AndroidApp` / `AndroidApp Signature` **自定义字段**（KeePassDX 同款，
 * 见 [AndroidAppBindingFields]），**不再整段替换 `URL`**。因此条目**已有网址**、表单**带自报
 * webDomain** 都不再构成否决：网页表单同样可以声明「这个条目也属于这个应用」，两者互不覆盖。
 * `URL` 里的旧 `android://<包名>` 形态只做兼容读取，不迁移、不重写。
 *
 * 保留三条 fail-closed 否决（与 `AndroidPackageBindingPolicy`「摘要不可读即不放行」同口径）：
 * - **只读会话**：一票否决（写盘硬拒绝的会话不得提出写入询问）；
 * - **包名非法**：[AutofillPackageNames.normalize] 返回 null ⇒ 不询问、不产生降级写入；
 * - **签名摘要不可读**：不产生「仅包名」形态的库内绑定（P2-46 要消灭的形态）。
 *
 * 另有**幂等去重**而非安全否决：同编号序列里该包名已存在（[alreadyBound]）⇒ 不再重复询问。
 * 这是用户拍板的询问频率默认值，避免每次交付都弹窗。
 *
 * 本机互动记忆 [AutofillCallerEntryMemory] 与本写回并存：前者是本机「立刻能出候选」的快速通道，
 * 后者是随 `.kdbx` 同步、换设备 / 重装 / 清数据后仍有效的持久通道。
 */
internal object AutofillAppBindingWriteBackPolicy {

    /** 将要写入的归一化包名（纯包名，不含 scheme）；非法返回 null，调用方据此跳过询问。 */
    fun proposedBindingPackage(callingPackage: String): String? =
        AutofillPackageNames.normalize(callingPackage)

    /**
     * 是否向用户提出「记住这个应用与该条目的关联？」。
     *
     * @param callingPackage 系统背书的调用方包名（来自交付链 `EXTRA_CALLING_PACKAGE`）
     * @param callerDigestsReadable 调用方签名摘要快照是否可读（`!CallerCertDigests.isEmpty`）
     * @param webDomain 表单自报域。**不再参与判定**：写入的是独立自定义字段，网页表单同样可写包名；
     *   保留在签名中是为了让「已有自报域仍询问」这一行为可被用例锁定，防止日后误恢复否决。
     * @param entryUrl 条目当前 URL。**不再参与判定**：写入不触碰 URL，已有网址 / `{REF:}` /
     *   旧的 `android://` 绑定都不被覆盖；同上由用例锁定。
     * @param sessionReadOnly 只读会话一票否决
     * @param alreadyBound 该包名已在条目的 `AndroidApp*` 序列中（幂等去重，不再重复询问）
     */
    fun shouldOffer(
        callingPackage: String,
        callerDigestsReadable: Boolean,
        webDomain: String?,
        entryUrl: String,
        sessionReadOnly: Boolean,
        alreadyBound: Boolean = false
    ): Boolean {
        if (sessionReadOnly) return false
        if (proposedBindingPackage(callingPackage) == null) return false
        if (!callerDigestsReadable) return false
        if (alreadyBound) return false
        return true
    }
}

/**
 * 「记住这个应用？」询问对话框（KeePassDX 口径）。
 *
 * 状态与等待桥在 [AutofillPickerViewModel]（交付链挂起等待用户处置）；本组合体只负责渲染——
 * 任何路径离开对话框（取消按钮 / 点外部 / 返回键）一律按**拒绝**处置（不写回、照常交付）；
 * 拒绝不持久化为「永久拒绝」（与应用内搜索侧 `VaultSearchWriteBackCoordinator` 的会话内抑制不同，
 * 本询问每次交付至多出现一次，重复频率由「该包名已绑定」与关联记忆的命中自然收敛）。
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
            TrackDialogInteractionEffect()
            Text(
                stringResource(
                    R.string.autofill_app_binding_write_back_message,
                    ask.proposedPackage,
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
