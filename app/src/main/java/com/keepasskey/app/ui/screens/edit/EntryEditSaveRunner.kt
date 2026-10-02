package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import com.keepasskey.app.ui.model.textArg

/**
 * 条目保存执行体（`ISSUE-P2-354 AC①②` 自 `EntryEditViewModel.saveEntry` 拆出；
 * 行数分档闸门 tier1≤500 倒逼同批下沉，行为与守卫语义逐字未改）。
 *
 * 防重复三件套（AC②「双击不产生重复条目」的实现现场）：
 * 1. [request] 入口先查 [EntryEditUiState.isSaving]，并发第二次**直接 return**；
 * 2. 新条目 UUID 在守卫之后、协程之外一次性生成——旧实现把 `UUID.randomUUID()` 放在
 *    协程体内、成功后才回写 state，快速双击会读到两次 `entryId == null` 落两条重复条目；
 * 3. [EntryEditEvent.SaveSuccess] 只随唯一那次成功落库发出，导航因此只发生一次。
 * 保存期间 `isSaving = true`（两个保存按钮禁用并内嵌进度），成功/失败路径都回落。
 *
 * 明文纪律：口令 / TOTP 种子 / 受保护字段经借用 lambda 在**协程体内**取 ViewModel 私有
 * 驻留的副本提交（与拆分前的取值时机一致），提交副本在 `finally` 兜底清零。
 */
internal class EntryEditSaveRunner(
    private val scope: CoroutineScope,
    private val repository: VaultRepository,
    private val strings: StringsProvider,
    private val uiState: MutableStateFlow<EntryEditUiState>,
    private val events: MutableSharedFlow<EntryEditEvent>,
    /** 借用 ViewModel 私有驻留（调用点即时读取，本类不长期持有明文引用） */
    private val passwordChars: () -> CharArray,
    private val totpSecretChars: () -> CharArray,
    private val protectedFieldChars: () -> Map<String, CharArray>
) {

    fun request() {
        val state = uiState.value
        // ISSUE-P3-359 AC⑤：载入完成前表单数据不可信，保存请求直接忽略（顶栏保存键在遮罩期的入口）
        if (state.isSaving || state.isLoading) return
        entrySaveRejectionRes(state)?.let { rejectionRes ->
            // ISSUE-P3-359 AC②：标题必填被拒 → 置字段级错误位（标题框 inline 常驻，
            // 输入即清除）；只读拦截只弹消息，不把错误位错安到标题上
            uiState.update {
                it.copy(
                    userMessage = UiMessage(rejectionRes),
                    titleError = rejectionRes == R.string.edit_title_required
                )
            }
            return
        }

        val entryId = state.entryId ?: UUID.randomUUID().toString()
        uiState.update { it.copy(isSaving = true) }
        scope.launch {
            val entry = buildEntrySaveSnapshot(state, entryId, strings.get(R.string.time_just_now))
            // M1 整改：密码以独立参数显式提交，不再随条目投影携带；提交副本归仓库擦除
            // （契约：仓库任何结果路径用毕清零），ViewModel 自有副本保留以支持失败后继续编辑
            // 断点4 整改 + TASK-10：TOTP 种子与受保护自定义字段明文以 CharArray 副本随保存显式提交
            // H3 整改：保存失败必须显式反馈，禁止磁盘写失败时谎报成功
            val passwordCopy = passwordChars().copyOf()
            val totpCopy = totpSecretChars().copyOf()
            val protectedCopy = protectedFieldChars().mapValues { (_, v) -> v.copyOf() }
            val result = try {
                repository.saveEntry(
                    entry,
                    passwordChars = passwordCopy,
                    totpSecretChars = totpCopy,
                    protectedFieldChars = protectedCopy
                )
            } finally {
                // 兜底擦除：若仓库实现未按契约清零（如旧版本 Fake），此处保证副本不残留明文
                passwordCopy.fill('0')
                totpCopy.fill('0')
                protectedCopy.values.forEach { it.fill('0') }
            }
            if (result is KdbxResult.Success) {
                // ISSUE-P3-342 不变量：**保存成功必须清脏位并记下条目 id**。
                // 理由不是体验：通行密钥导入闸门以 `hasUnsavedEdits()`（＝ isDirty）作前置拒绝
                // （见 EntryEditPasskeyImport）。今天不出事**仅因** SaveSuccess 随即出页；
                // 一旦改成"保存后留在本页"，用户刚保存完就会被提示「请先保存」而永久导不进去。
                uiState.update { it.copy(isDirty = false, entryId = entryId, isSaving = false) }
                events.emit(EntryEditEvent.SaveSuccess)
            } else {
                val failure = result as KdbxResult.Failure
                uiState.update {
                    it.copy(
                        userMessage = UiMessage(R.string.edit_save_failed, listOf(failure.textArg(strings))),
                        isSaving = false
                    )
                }
            }
        }
    }
}
