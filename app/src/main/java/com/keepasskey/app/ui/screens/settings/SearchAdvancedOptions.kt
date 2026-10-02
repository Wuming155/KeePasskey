package com.keepasskey.app.ui.screens.settings

import androidx.annotation.StringRes
import com.keepasskey.app.R

/**
 * ISSUE-P3-439 AC①：高级搜索的字段范围档。
 *
 * 勾选集决定参与子串命中的候选文本类别；**默认全选＝现行为零变化**（ISSUE-P3-439 AC①）。
 * 受保护自定义字段即使勾选 `CUSTOM_FIELDS` 仍只出键不出值（防侧信道红线，见
 * `VaultSearchMatching` KDoc——本枚举只扩范围，不改保护口径）。
 */
enum class SearchField(@StringRes val labelRes: Int) {
    TITLE(R.string.search_field_title),
    USERNAME(R.string.search_field_username),
    URL(R.string.search_field_url),
    NOTES(R.string.search_field_notes),
    TAGS(R.string.search_field_tags),
    CUSTOM_FIELDS(R.string.search_field_custom)
}

/**
 * ISSUE-P3-439：应用内搜索的高级选项（字段范围 / 排除已过期 / 大小写档）。
 *
 * - [fields] 默认全选＝现行为零变化；
 * - [excludeExpired] 仅影响应用内搜索展示，与 PD-61 填充链排除过期的口径**独立**
 *   （互不读写、互不联动）；分组名命中不受本开关影响（分组无过期概念）；
 * - [caseSensitive] 默认不敏感＝现状；
 * - **正则档不在范围内**（ISSUE-P3-439 AC③：如后续评估须单独立项，注入面与性能预算）。
 *
 * 持久化于进阶偏好（`ExtendedSettingsStore`，SharedPreferences，不入库数据）。
 */
data class SearchAdvancedOptions(
    val fields: Set<SearchField> = SearchField.entries.toSet(),
    val excludeExpired: Boolean = false,
    val caseSensitive: Boolean = false
) {
    /** 全字段是否都在范围内（true = 字段范围档零收窄，等价现行为） */
    val isAllFields: Boolean get() = fields == SearchField.entries.toSet()
}
