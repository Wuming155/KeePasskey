package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import java.security.SecureRandom

/**
 * 编辑页口令生成器动作（`ISSUE-P3-342` 期间自 `EntryEditViewModel` 按职责拆出，行为逐字不变）。
 *
 * 拆出的动机是行数分档闸门（`tier1(>500)` 恒 0），但拆完的边界本身是干净的：
 * 生成器只改 UI 状态与「把新生成的口令交给编辑页」，不碰仓库、不碰落盘。
 *
 * ⚠️ **清零契约不变**：[generate] 产出的数组在交出后由本类就地填零，
 * 编辑页侧 [emitPassword] 走的是既有 `onPasswordChangeSecure` 通道（**自行复制**私有副本），
 * 两侧各自负责自己的副本——与拆分前完全同形。
 */
internal class EntryEditPasswordGenerator(
    private val state: () -> EntryEditUiState,
    private val update: ((EntryEditUiState) -> EntryEditUiState) -> Unit,
    private val emitPassword: (CharArray) -> Unit,
    /** 生成结果回显（§409）：经编辑页一次性预填通道注入组件显示态——显示态内聚于组件，模型侧只管持有 */
    private val pushEcho: (CharArray) -> Unit = {}
) {

    fun togglePasswordVisibility() = update { it.copy(isPasswordVisible = !it.isPasswordVisible) }

    fun togglePanel() = update { it.copy(showGenerator = !it.showGenerator) }

    fun onPassLengthChange(length: Float) {
        update { it.copy(passLength = length) }
        generate()
    }

    fun toggleUpper() {
        update { it.copy(useUpper = !it.useUpper) }
        generate()
    }

    fun toggleLower() {
        update { it.copy(useLower = !it.useLower) }
        generate()
    }

    fun toggleDigits() {
        update { it.copy(useDigits = !it.useDigits) }
        generate()
    }

    fun toggleSymbols() {
        update { it.copy(useSymbols = !it.useSymbols) }
        generate()
    }

    /** M1 整改：生成结果直达 CharArray，不经 String 中转；清零点保留在本方法内。
     *  §409：`pushEcho` 在模型侧上行后触发（编辑页经预填通道回显组件显示态）。 */
    fun generate() {
        val newPassword = generatePasswordChars(state(), SecureRandom())
        emitPassword(newPassword)
        pushEcho(newPassword)
        newPassword.fill('0')
    }
}

/**
 * 编辑页附件草稿动作（同上拆出，行为逐字不变）。
 *
 * 附件在保存前只活在 UI 状态里（保存时随条目提交入库）；**同名附件视为替换**。
 * 空文件当场拒绝并如实提示（不静默收下 0 字节附件）。
 */
internal class EntryEditAttachmentDraft(
    private val strings: StringsProvider,
    private val update: ((EntryEditUiState) -> EntryEditUiState) -> Unit
) {

    fun add(fileName: String, fileSizeFormatted: String, data: ByteArray) {
        if (data.isEmpty()) {
            update { it.copy(userMessage = UiMessage(R.string.edit_attachment_empty)) }
            return
        }
        val newAtt = buildNewAttachment(
            id = "att_${System.currentTimeMillis()}",
            fileName = fileName,
            fileSizeFormatted = fileSizeFormatted,
            addedAt = strings.get(R.string.time_just_now),
            data = data
        )
        update { state ->
            state.copy(attachments = state.attachments.filterNot { it.fileName == fileName } + newAtt, isDirty = true)
        }
    }

    fun remove(id: String) {
        update { state -> state.copy(attachments = withoutAttachment(state.attachments, id), isDirty = true) }
    }
}
