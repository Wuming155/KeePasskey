package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.PasskeyData
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
    // ISSUE-P3-310：既有过期值回填编辑表单（Instant → 本地日期）
    expiresEnabled = entry.expiresAt != null,
    expiryDate = entry.expiresAt?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate(),
    // ISSUE-P3-359 AC⑤②：载入完成结束加载态；重载后的标题来自库内，旧校验位一并复位
    isLoading = false,
    titleError = false,
    isDirty = false
)

/**
 * ISSUE-P3-51：以库内模板条目预填**新建**表单。
 *
 * 与 [applyLoadedEntry] 的关键差异：**保持 [EntryEditUiState.entryId] 为空**——
 * 保存即新建一条新条目，而非覆盖模板本身。
 *
 * 仅复制结构性字段：标题 / 用户名 / URL / 备注 / 图标（标准与自定义）/ 标签 /
 * AutoType 序列 / Override URL / 自定义字段（键与保护标记，受保护值仍为空串）。
 * **不复制**密码、TOTP、附件与历史——模板用于字段骨架，不携带任何机密或大对象。
 * 落点分组取路由带入的 [targetGroupId]，缺省沿用模板所属分组。
 */
internal fun applyTemplateEntry(
    state: EntryEditUiState,
    template: UiVaultEntry,
    targetGroupId: String?
): EntryEditUiState = state.copy(
    entryId = null,
    groupId = targetGroupId ?: template.groupId,
    iconName = template.iconName,
    customIconId = template.customIconId,
    title = template.title,
    username = template.username,
    url = template.url,
    notes = template.notes,
    isPasskey = false,
    customFields = template.customFields,
    tagsInput = template.tags.joinToString(", "),
    autoTypeSequence = template.autoTypeSequence,
    overrideUrl = template.overrideUrl.orEmpty(),
    // ISSUE-P3-359 AC⑤：模板预填完成同样结束加载态（见 applyLoadedEntry 同行注释）
    isLoading = false,
    isDirty = false
)

/**
 * 通行密钥字段在编辑页的**只读锁**判据（`ISSUE-P3-337` AC⑪①，规范 CXF §3.3.12.1 原文：
 * `Passkey` 字典除 `username` / `userDisplayName` 外的成员「MUST NOT be user editable」）。
 *
 * 锁的是**凭据材料本身**：`rpId` / `credentialId` / `userHandle` / 私钥 PEM / 算法 / 两个
 * PRF 种子 / 签名计数器 / 两个 BE·BS 标志 / 创建时间——手改任意一项都会让条目与真实凭据
 * 脱钩（断言失败或更糟：把改过的值交给 RP）。v1 旧键同锁（库里仍可能有历史条目）。
 *
 * 两个展示字段按规范明文**豁免**：[PasskeyData.FIELD_USER_NAME]（含 v1 同义键）与
 * [PasskeyData.FIELD_USER_DISPLAY_NAME] 不参与仪式，用户事后修正显示名是正当需求。
 */
internal fun isLockedPasskeyFieldKey(key: String): Boolean =
    PasskeyData.isPasskeyFieldKey(key) && key !in EDITABLE_PASSKEY_DISPLAY_KEYS

/** 规范 §3.3.12.1 明文豁免的两个展示字段键。 */
private val EDITABLE_PASSKEY_DISPLAY_KEYS: Set<String> = setOf(
    PasskeyData.FIELD_USER_NAME,
    PasskeyData.LEGACY_FIELD_USER_NAME,
    PasskeyData.FIELD_USER_DISPLAY_NAME
)

/** 按编辑态 id 判锁（[UiCustomField.id] = `条目id_字段键`，故 id 命中即该字段被锁）。 */
internal fun isLockedCustomField(fields: List<UiCustomField>, id: String): Boolean =
    fields.any { it.id == id && isLockedPasskeyFieldKey(it.key) }

/**
 * 在自定义字段列表中就地替换指定 id 的键名 / 值 / 保护标记（未命中则原样返回）。
 *
 * AC⑪①：被锁的通行密钥字段**就地拒绝替换**（返回原列表元素），编辑页因此不存在任何一条
 * 「改得动凭据材料」的通路——UI 只是不提供入口，本函数才是那道锁。
 */
internal fun withUpdatedCustomField(
    fields: List<UiCustomField>,
    id: String,
    key: String,
    value: String,
    isProtected: Boolean
): List<UiCustomField> = fields.map { f ->
    if (f.id == id && !isLockedPasskeyFieldKey(f.key)) f.copy(key = key, value = value, isProtected = isProtected) else f
}

/**
 * 移除指定 id 的自定义字段（未命中则原样返回）。
 *
 * AC⑪①：删除同样是「手改」——被锁字段删不掉（删掉即凭据材料缺失，条目直接不可断言）。
 */
internal fun withoutCustomField(fields: List<UiCustomField>, id: String): List<UiCustomField> =
    fields.filter { it.id != id || isLockedPasskeyFieldKey(it.key) }

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
