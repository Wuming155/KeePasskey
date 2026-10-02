package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.screens.settings.SearchAdvancedOptions
import com.keepasskey.app.ui.screens.settings.SearchField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ISSUE-P3-439 AC①②：高级搜索选项的状态与上行编排（§280 规模门禁同批从
 * [VaultListViewModel] 逐字迁出，结构性拆分，语义零变化）。
 *
 * - 会话流 [state] 即时生效（ViewModel 的 sessionStateFlow 以本流覆写
 *   `extended.searchAdvanced`，用户改动不等页面进入刷新）；
 * - 有偏好通道（生产 DI 注入 [ExtendedSettingsStore]）时经 `publish` + `save`
 *   同步持久化（与 SettingsExtendedPreferencesController.updateExtended
 *   同一原子写口径），不入库数据（AC④）；null（纯 JVM 单测）时会话内生效但不落盘。
 */
internal class VaultSearchAdvancedStore(
    initial: SearchAdvancedOptions,
    private val preferences: ExtendedSettingsStore? = null
) {

    private val flow = MutableStateFlow(initial)

    /** 高级搜索选项会话流（ViewModel 装配链以此为准） */
    val state: StateFlow<SearchAdvancedOptions> = flow.asStateFlow()

    /** 偏好快照刷新时同步（设置页与列表页同源，见 VaultListViewModel.onScreenEntered） */
    fun reload(options: SearchAdvancedOptions) {
        flow.value = options
    }

    /** ISSUE-P3-439 AC①：字段范围勾选上行 */
    fun setFields(fields: Set<SearchField>) = update { it.copy(fields = fields) }

    /** ISSUE-P3-439 AC①：单个字段勾选/取消的切换上行（搜索面板 FilterChip 的回调形态） */
    fun toggleField(field: SearchField) = update { options ->
        options.copy(
            fields = if (field in options.fields) options.fields - field else options.fields + field
        )
    }

    /** ISSUE-P3-439 AC①：「排除已过期」开关上行 */
    fun setExcludeExpired(enabled: Boolean) = update { it.copy(excludeExpired = enabled) }

    /** ISSUE-P3-439 AC②：大小写敏感档上行 */
    fun setCaseSensitive(enabled: Boolean) = update { it.copy(caseSensitive = enabled) }

    private fun update(transform: (SearchAdvancedOptions) -> SearchAdvancedOptions) {
        var next = transform(flow.value)
        // ISSUE-P3-439：字段全不选＝搜索恒空的损坏配置——就地回落全选（与存储层
        // decodeSearchAdvanced 的空集回落口径一致，两处不漂移）
        if (next.fields.isEmpty()) {
            next = next.copy(fields = SearchField.entries.toSet())
        }
        flow.value = next
        preferences?.let { store ->
            val settings = store.settings.value.copy(searchAdvanced = next)
            store.publish(settings)
            store.save(settings)
        }
    }
}
