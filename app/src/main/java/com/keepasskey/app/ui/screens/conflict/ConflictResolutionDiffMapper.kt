package com.keepasskey.app.ui.screens.conflict

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.core.security.readCharsForDisplayOrNull
import com.keepasskey.core.security.readStringForDisplayOrEmpty
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.ConflictedGroupPair
import com.keepasskey.sync.merge.KdbxGroupConflictKeys
import com.keepasskey.sync.merge.KdbxMetaConflictKeys
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.MetaFieldConflict

/**
 * `ISSUE-P2-660` / `ISSUE-P2-659`：冲突差异键 → 界面行的映射（自 [ConflictResolutionViewModel] 下沉）。
 *
 * 为什么独立：差异键的词汇表有三套（条目 `KdbxMerger` 常量 / 分组 `KdbxGroupConflictKeys` /
 * 库级 Meta `KdbxMetaConflictKeys`），每套都要映射成「字段名 + 两侧展示值」，且敏感字段的
 * 掩码规则只允许存在于一处。UI 状态编排（代次、提交流程）与这种纯映射是两件事。
 */
internal class ConflictResolutionDiffMapper(private val strings: StringsProvider) {

    /**
     * `ISSUE-P2-659`：库级 Meta 差异键 → 界面行。
     *
     * 值均为公开元数据（库名 / 描述 / 默认用户名 / 颜色 / 历史上限 / 库级自定义数据），
     * 不做敏感掩码；`historyMaxSize` 以字节数呈现，由用户对照两侧具体数值。
     */
    fun metaField(conflict: MetaFieldConflict): ConflictedField {
        val label = when {
            conflict.fieldKey == KdbxMetaConflictKeys.NAME -> strings.get(R.string.conflict_meta_field_name)
            conflict.fieldKey == KdbxMetaConflictKeys.DESCRIPTION ->
                strings.get(R.string.conflict_meta_field_description)
            conflict.fieldKey == KdbxMetaConflictKeys.DEFAULT_USER_NAME ->
                strings.get(R.string.conflict_meta_field_default_user)
            conflict.fieldKey == KdbxMetaConflictKeys.COLOR -> strings.get(R.string.conflict_meta_field_color)
            conflict.fieldKey == KdbxMetaConflictKeys.HISTORY_MAX_ITEMS ->
                strings.get(R.string.conflict_meta_field_history_items)
            conflict.fieldKey == KdbxMetaConflictKeys.HISTORY_MAX_SIZE ->
                strings.get(R.string.conflict_meta_field_history_size)
            conflict.fieldKey == KdbxMetaConflictKeys.MAINTENANCE_HISTORY_DAYS ->
                strings.get(R.string.conflict_meta_field_maintenance_days)
            conflict.fieldKey.startsWith(KdbxMetaConflictKeys.CUSTOM_DATA_PREFIX) -> strings.get(
                R.string.conflict_meta_field_custom_data,
                conflict.fieldKey.removePrefix(KdbxMetaConflictKeys.CUSTOM_DATA_PREFIX)
            )
            else -> strings.get(R.string.conflict_meta_field_unknown, conflict.fieldKey)
        }
        return ConflictedField(
            fieldKey = conflict.fieldKey,
            fieldName = label,
            localValue = conflict.localValue.ifEmpty { strings.get(R.string.conflict_value_empty) },
            remoteValue = conflict.remoteValue.ifEmpty { strings.get(R.string.conflict_value_empty) }
        )
    }

    /**
     * `ISSUE-P2-660`：把分组的差异键（`KdbxGroupConflictKeys` 词汇）映射为只读差异行。
     *
     * 分组字段不含秘密（组名 / 备注 / 图标 / 父组 / 组级 customData 均为公开元数据），
     * 故不需要条目侧的敏感性掩码逻辑；缺失值统一显示「（无）」而不是空串，避免与「值为空」混淆。
     */
    fun groupField(
        pair: ConflictedGroupPair,
        key: String
    ): ConflictedField {
        val local = pair.localGroup
        val remote = pair.remoteGroup
        val none = strings.get(R.string.conflict_value_none)
        return when {
            key == KdbxGroupConflictKeys.NAME -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_group_field_name),
                localValue = local.name,
                remoteValue = remote.name
            )
            key == KdbxGroupConflictKeys.NOTES -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_group_field_notes),
                localValue = local.notes,
                remoteValue = remote.notes
            )
            key == KdbxGroupConflictKeys.ICON_ID -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_group_field_icon),
                localValue = local.iconId.toString(),
                remoteValue = remote.iconId.toString()
            )
            key == KdbxGroupConflictKeys.CUSTOM_ICON_ID -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_group_field_custom_icon),
                localValue = local.customIconId?.toHexString() ?: none,
                remoteValue = remote.customIconId?.toHexString() ?: none
            )
            key == KdbxGroupConflictKeys.PARENT_GROUP_ID -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_group_field_parent),
                localValue = local.parentGroupId?.toHexString() ?: none,
                remoteValue = remote.parentGroupId?.toHexString() ?: none
            )
            key.startsWith(KdbxGroupConflictKeys.CUSTOM_DATA_PREFIX) -> {
                val customKey = key.removePrefix(KdbxGroupConflictKeys.CUSTOM_DATA_PREFIX)
                ConflictedField(
                    fieldKey = key,
                    fieldName = strings.get(R.string.conflict_group_field_custom_data, customKey),
                    localValue = local.customData[customKey] ?: none,
                    remoteValue = remote.customData[customKey] ?: none
                )
            }
            // 词汇外的键可见呈现（禁静默丢弃差异）；落树侧对未知键为安全 no-op
            else -> ConflictedField(
                fieldKey = key,
                fieldName = strings.get(R.string.conflict_group_field_unknown, key),
                localValue = strings.get(R.string.conflict_value_changed),
                remoteValue = strings.get(R.string.conflict_value_changed)
            )
        }
    }

    /**
     * ISSUE-P3-360 AC①：敏感字段的安全差异线索——静态掩码两侧完全相同、无法区分差异，
     * 改为「掩码 + 长度 + 字符形态（大写/小写/数字/符号）」。
     *
     * 安全裁决：**不做「揭示」开关**——冲突裁决无需明文即可完成，明文进 UiState 违反
     * 敏感数据铁律（能用 Char 的地方绝不落到 String）。长度与形态统计经
     * [readCharsForDisplayOrNull] 在 CharArray 通道完成，不物化明文、不入日志。
     *
     * `ISSUE-P2-534`：本页在**同步刚结束**时渲染，与会话整树替换的就地擦除极易并发。
     * 整改前是「先查 `cleared` 再 `useChars`」的两步式——两步之间不是原子的，撞上即抛
     * `IllegalStateException` 且逃逸到 `viewModelScope` ⇒ 闪退；现统一走展示面读口，
     * 已清零 / null 一律降级为既有回退掩码。
     *
     * @param fallbackMask [value] 缺失 / 已清零时的回退掩码（保持既有降级形态）
     */
    private fun sensitiveValueHint(value: ProtectedString?, fallbackMaskRes: Int): String {
        // 返回值是**独占副本**，用毕在 finally 清零（与 `ProtectedString.readChars` 同契约）
        val chars = value.readCharsForDisplayOrNull() ?: return strings.get(fallbackMaskRes)
        return try {
            if (chars.isEmpty()) {
                strings.get(R.string.conflict_sensitive_hint_length_only, 0)
            } else {
                val categories = buildList {
                    if (chars.any { it in 'A'..'Z' }) add(strings.get(R.string.conflict_sensitive_cat_upper))
                    if (chars.any { it in 'a'..'z' }) add(strings.get(R.string.conflict_sensitive_cat_lower))
                    if (chars.any { it in '0'..'9' }) add(strings.get(R.string.conflict_sensitive_cat_digit))
                    if (chars.any { !it.isLetterOrDigit() }) add(strings.get(R.string.conflict_sensitive_cat_symbol))
                }
                val separator = strings.get(R.string.conflict_sensitive_cat_separator)
                if (categories.isEmpty()) {
                    strings.get(R.string.conflict_sensitive_hint_length_only, chars.size)
                } else {
                    strings.get(R.string.conflict_sensitive_hint, chars.size, categories.joinToString(separator))
                }
            }
        } finally {
            chars.fill('0')
        }
    }

    /**
     * ISSUE-P2-281：把 `modifiedFields` 的一个差异键映射为可裁决的界面行
     * （键的词汇与合并器同表——标准字段键 / `custom:` 前缀自定义字段键 / 标量 / 联合到期 / AutoType / 颜色键）。
     * 敏感值（密码 / `isProtected` 的自定义字段）一律掩码，不物化明文。
     */
    fun entryField(
        pair: ConflictedEntryPair,
        key: String
    ): ConflictedField {
        val local = pair.localEntry
        val remote = pair.remoteEntry
        return when {
            key == KdbxConstants.Fields.PASSWORD || key == KdbxConstants.Fields.OTP -> {
                val labelRes = if (key == KdbxConstants.Fields.PASSWORD) {
                    R.string.conflict_field_password
                } else {
                    R.string.conflict_field_otp
                }
                ConflictedField(
                    fieldKey = key,
                    fieldName = strings.get(labelRes),
                    localValue = local.fields[key]?.let {
                        sensitiveValueHint(it, R.string.conflict_mask_local)
                    } ?: strings.get(R.string.conflict_value_field_missing),
                    remoteValue = remote.fields[key]?.let {
                        sensitiveValueHint(it, R.string.conflict_mask_remote)
                    } ?: strings.get(R.string.conflict_value_field_missing),
                    isSensitive = true,
                    localMissing = key !in local.fields,
                    remoteMissing = key !in remote.fields
                )
            }
            key == KdbxConstants.Fields.TITLE || key == KdbxConstants.Fields.USER_NAME ||
                key == KdbxConstants.Fields.URL || key == KdbxConstants.Fields.NOTES -> {
                val labelRes = when (key) {
                    KdbxConstants.Fields.TITLE -> R.string.conflict_field_title
                    KdbxConstants.Fields.USER_NAME -> R.string.conflict_field_username
                    KdbxConstants.Fields.URL -> R.string.conflict_field_url
                    else -> R.string.conflict_field_notes
                }
                ConflictedField(
                    fieldKey = key,
                    fieldName = strings.get(labelRes),
                    // ISSUE-P2-534：冲突差异展示面——本页在同步刚结束时渲染，与会话整树替换的
                    // 就地擦除极易并发；裸 readString 撞上已擦实例会崩进程（§473 同型面）
                    localValue = local.fields[key]?.readStringForDisplayOrEmpty()
                        ?.ifEmpty { strings.get(R.string.conflict_value_empty) }
                        ?: strings.get(R.string.conflict_value_field_missing),
                    remoteValue = remote.fields[key]?.readStringForDisplayOrEmpty()
                        ?.ifEmpty { strings.get(R.string.conflict_value_empty) }
                        ?: strings.get(R.string.conflict_value_field_missing),
                    localMissing = key !in local.fields,
                    remoteMissing = key !in remote.fields
                )
            }
            key.startsWith(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX) -> {
                val customKey = key.removePrefix(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX)
                val lv = local.customFields.firstOrNull { it.key == customKey }?.value
                val rv = remote.customFields.firstOrNull { it.key == customKey }?.value
                val sensitive = lv?.isProtected == true || rv?.isProtected == true
                ConflictedField(
                    fieldKey = key,
                    fieldName = customKey,
                    localValue = when {
                        lv == null -> strings.get(R.string.conflict_value_field_missing)
                        sensitive -> sensitiveValueHint(lv, R.string.conflict_mask_local)
                        else -> lv.readStringForDisplayOrEmpty().ifEmpty {
                            strings.get(R.string.conflict_value_empty)
                        }
                    },
                    remoteValue = when {
                        rv == null -> strings.get(R.string.conflict_value_field_missing)
                        sensitive -> sensitiveValueHint(rv, R.string.conflict_mask_remote)
                        else -> rv.readStringForDisplayOrEmpty().ifEmpty {
                            strings.get(R.string.conflict_value_empty)
                        }
                    },
                    isSensitive = sensitive,
                    localMissing = lv == null,
                    remoteMissing = rv == null
                )
            }
            // 标量 / 联合到期 / AutoType / 颜色键经专用映射；词汇外的键可见呈现（禁静默丢弃差异），
            // resolveConflictByFields 对未知键为安全 no-op
            else -> scalarField(key, local, remote) ?: ConflictedField(
                fieldKey = key,
                fieldName = key,
                localValue = strings.get(R.string.conflict_value_changed),
                remoteValue = strings.get(R.string.conflict_value_changed)
            )
        }
    }

    /**
     * 标量 / 联合到期 / AutoType / 颜色差异键的界面行映射（ISSUE-P2-279 / P2-597）。
     * 未知键返回 null，由调用方回落到「可见呈现」兜底。
     */
    private fun scalarField(key: String, local: KdbxEntry, remote: KdbxEntry): ConflictedField? = when (key) {
        KdbxMerger.CONFLICT_KEY_ICON_ID -> ConflictedField(
            fieldKey = key,
            fieldName = strings.get(R.string.conflict_field_icon),
            localValue = local.iconId.toString(),
            remoteValue = remote.iconId.toString()
        )
        KdbxMerger.CONFLICT_KEY_CUSTOM_ICON_ID -> ConflictedField(
            fieldKey = key,
            fieldName = strings.get(R.string.conflict_field_custom_icon),
            localValue = local.customIconId?.toHexString() ?: strings.get(R.string.conflict_value_none),
            remoteValue = remote.customIconId?.toHexString() ?: strings.get(R.string.conflict_value_none)
        )
        KdbxMerger.CONFLICT_KEY_OVERRIDE_URL -> ConflictedField(
            fieldKey = key,
            fieldName = strings.get(R.string.conflict_field_override_url),
            localValue = local.overrideUrl ?: strings.get(R.string.conflict_value_none),
            remoteValue = remote.overrideUrl ?: strings.get(R.string.conflict_value_none)
        )
        KdbxMerger.CONFLICT_KEY_QUALITY_CHECK -> ConflictedField(
            fieldKey = key,
            fieldName = strings.get(R.string.conflict_field_quality_check),
            localValue = strings.get(if (local.qualityCheck) R.string.conflict_value_true else R.string.conflict_value_false),
            remoteValue = strings.get(if (remote.qualityCheck) R.string.conflict_value_true else R.string.conflict_value_false)
        )
        KdbxMerger.CONFLICT_KEY_AUTO_TYPE -> ConflictedField(
            fieldKey = key,
            fieldName = strings.get(R.string.conflict_field_auto_type),
            localValue = autoTypeHint(local.autoType),
            remoteValue = autoTypeHint(remote.autoType)
        )
        KdbxMerger.CONFLICT_KEY_EXPIRES -> ConflictedField(
            fieldKey = key,
            fieldName = strings.get(R.string.conflict_field_expires),
            localValue = expiryHint(local),
            remoteValue = expiryHint(remote)
        )
        KdbxMerger.CONFLICT_KEY_BACKGROUND_COLOR -> ConflictedField(
            fieldKey = key,
            fieldName = strings.get(R.string.conflict_field_background_color),
            localValue = local.backgroundColor ?: strings.get(R.string.conflict_value_none),
            remoteValue = remote.backgroundColor ?: strings.get(R.string.conflict_value_none)
        )
        KdbxMerger.CONFLICT_KEY_FOREGROUND_COLOR -> ConflictedField(
            fieldKey = key,
            fieldName = strings.get(R.string.conflict_field_foreground_color),
            localValue = local.foregroundColor ?: strings.get(R.string.conflict_value_none),
            remoteValue = remote.foregroundColor ?: strings.get(R.string.conflict_value_none)
        )
        else -> null
    }

    /** AutoType 配置提示（ISSUE-P2-597）：仅呈现配置启用状态，不新增自动输入执行功能。 */
    private fun autoTypeHint(autoType: KdbxAutoType?): String =
        strings.get(if (autoType?.enabled == true) R.string.conflict_value_true else R.string.conflict_value_false)

    /** 到期提示（ISSUE-P2-597）：未启用到期显示「（无）」，启用则显示到期时刻。 */
    private fun expiryHint(entry: KdbxEntry): String =
        if (entry.times.expires) entry.times.expiryTime.toString() else strings.get(R.string.conflict_value_none)
}
