package com.keepasskey.app.ui.screens.edit

import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxTags

/**
 * 编辑页**保存侧纯投影**（纯结构性拆分：自 `EntryEditViewModel.kt` 拆出）。
 *
 * 只做「状态快照 → 待落库条目 / 校验结论」的纯函数装配：不持有可变状态、不触发 IO，
 * 亦不触碰任何敏感 [CharArray] 链路（密码等明文仍由 ViewModel 以独立参数显式提交）。
 * 原 `EntryEditViewModel` 内联体逐字迁移，行为零变更。
 */

/**
 * 校验当前状态是否允许提交保存：返回首个拦截原因的字符串资源 id，允许保存时为 null。
 * 顺序与原实现一致——只读拦截优先于标题必填。
 */
@StringRes
internal fun entrySaveRejectionRes(state: EntryEditUiState): Int? = when {
    state.isReadOnly -> R.string.readonly_save_rejected
    state.title.isBlank() -> R.string.edit_title_required
    else -> null
}

/**
 * 标签输入解析（`ISSUE-P2-282`：与读 / 写侧共用 [KdbxTags] 单一词汇表——
 * 分隔符集合 `,` / `;`、归一化（trim + 分隔符替换为 `.`）、去空、去重、自然排序，
 * 口径对齐官方 `StringToTags`；不再按空格 / 全角逗号切分（官方同口径，空格是合法标签字符）。
 */
internal fun parseEntryTags(input: String): List<String> = KdbxTags.parse(input)

/**
 * 由编辑状态与时间戳组装待落库的条目投影。
 * 不含密码 / TOTP / 受保护字段明文——其经仓库的独立 [CharArray] 参数提交。
 */
internal fun buildEntrySaveSnapshot(
    state: EntryEditUiState,
    entryId: String,
    updatedAt: String
): UiVaultEntry = UiVaultEntry(
    id = entryId,
    title = state.title.trim(),
    username = state.username.trim(),
    url = state.url.trim(),
    notes = state.notes.trim(),
    isPasskey = state.isPasskey,
    category = if (state.isPasskey) EntryCategory.PASSKEY else EntryCategory.LOGIN,
    updatedAt = updatedAt,
    groupId = state.groupId,
    iconName = state.iconName,
    customIconId = state.customIconId,
    customFields = state.customFields.filter { it.key.isNotBlank() },
    attachments = state.attachments,
    tags = parseEntryTags(state.tagsInput),
    autoTypeSequence = state.autoTypeSequence,
    overrideUrl = state.overrideUrl.trim().takeIf { it.isNotEmpty() }
)
