package com.keepasskey.app.ui.screens.settings

import androidx.annotation.StringRes
import com.keepasskey.app.R

/**
 * 全文搜索匹配档（ISSUE-P3-309）。
 *
 * - [CONTAINS]：既有口径——查询串整体对候选文本做大小写不敏感子串匹配（默认）；
 * - [ALL_TERMS]：分词档——查询串按空白切词，**每个词**都须在候选文本中命中（子串口径、
 *   大小写不敏感），用于收紧「短查询误报多」（如 `in` 命中一切含该子串的字段）的场景。
 *   分词只切空白、不做词干化，CJK 连续文本按整段参与匹配，语义与子串档一致。
 */
// ISSUE-P3-330：原 descRes（各档位说明）声明后从未被渲染，已连同字符串一并移除；
// 界面说明统一由 section 级 theme_search_mode_desc 承载，不得再加回死声明。
enum class SearchMatchMode(
    @StringRes val labelRes: Int
) {
    CONTAINS(R.string.theme_search_mode_contains),
    ALL_TERMS(R.string.theme_search_mode_all_terms)
}
