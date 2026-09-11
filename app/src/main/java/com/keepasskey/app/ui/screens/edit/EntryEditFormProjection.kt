package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import java.security.SecureRandom

/**
 * 编辑页**表单侧纯投影与工厂**（纯结构性拆分：自 `EntryEditViewModel.kt` 拆出）。
 *
 * 覆盖「条目加载 → UI 状态」「自定义字段 / 附件构造」「口令生成」三类无状态协作。
 * 仅承担投影与数据装配，不持有可变状态、不触发 IO，亦不负责任何敏感副本的清零
 * （清零仍由 ViewModel 的私有链路在保存 / 销毁路径显式完成）。原内联体逐字迁移，行为零变更。
 */

/** 口令字符池的命名常量（原 `generatePassword` 内联字面量，去混淆字符集逐字保留）。 */
private const val UPPER_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
private const val LOWER_CHARS = "abcdefghijkmnopqrstuvwxyz"
private const val DIGIT_CHARS = "23456789"
private const val SYMBOL_CHARS = "!@#\$%^&*()_+-=[]{}|;:,.<>?"

/** 依据条目投影与密码长度重建编辑态（不含密码明文，仅下发长度用于强度条渲染）。 */
internal fun applyLoadedEntry(
    state: EntryEditUiState,
    entry: UiVaultEntry,
    passwordLength: Int
): EntryEditUiState = state.copy(
    entryId = entry.id,
    groupId = entry.groupId,
    iconName = entry.iconName,
    customIconId = entry.customIconId,
    title = entry.title,
    username = entry.username,
    passwordLength = passwordLength,
    url = entry.url,
    notes = entry.notes,
    isPasskey = entry.isPasskey,
    customFields = entry.customFields,
    attachments = entry.attachments,
    tagsInput = entry.tags.joinToString(", "),
    autoTypeSequence = entry.autoTypeSequence,
    overrideUrl = entry.overrideUrl.orEmpty(),
    isDirty = false
)

/** 在自定义字段列表中就地替换指定 id 的键名 / 值 / 保护标记（未命中则原样返回）。 */
internal fun withUpdatedCustomField(
    fields: List<UiCustomField>,
    id: String,
    key: String,
    value: String,
    isProtected: Boolean
): List<UiCustomField> = fields.map { f ->
    if (f.id == id) f.copy(key = key, value = value, isProtected = isProtected) else f
}

/** 移除指定 id 的自定义字段（未命中则原样返回）。 */
internal fun withoutCustomField(fields: List<UiCustomField>, id: String): List<UiCustomField> =
    fields.filter { it.id != id }

/** 移除指定 id 的附件（未命中则原样返回）。 */
internal fun withoutAttachment(attachments: List<UiAttachment>, id: String): List<UiAttachment> =
    attachments.filter { it.id != id }

/** 新建空的自定义字段（键、值、保护标记均取默认初值）。 */
internal fun buildNewCustomField(id: String): UiCustomField =
    UiCustomField(id = id, key = "", value = "", isProtected = false)

/** 由 SAF 选中文件的元数据与字节构造待添加附件（MIME 沿用通用二进制流）。 */
internal fun buildNewAttachment(
    id: String,
    fileName: String,
    fileSizeFormatted: String,
    addedAt: String,
    data: ByteArray
): UiAttachment = UiAttachment(
    id = id,
    fileName = fileName,
    fileSizeFormatted = fileSizeFormatted,
    mimeType = "application/octet-stream",
    addedAt = addedAt,
    data = data
)

/**
 * 按当前选项组装字符池并生成随机口令，结果直达 [CharArray]（不经 [String] 中转）。
 * 调用方负责用毕对该数组显式清零（清零点保留在 ViewModel 内）。
 */
internal fun generatePasswordChars(state: EntryEditUiState, random: SecureRandom): CharArray {
    var pool = ""
    if (state.useUpper) pool += UPPER_CHARS
    if (state.useLower) pool += LOWER_CHARS
    if (state.useDigits) pool += DIGIT_CHARS
    if (state.useSymbols) pool += SYMBOL_CHARS
    if (pool.isEmpty()) pool = LOWER_CHARS
    return CharArray(state.passLength.toInt()) { pool[random.nextInt(pool.length)] }
}
