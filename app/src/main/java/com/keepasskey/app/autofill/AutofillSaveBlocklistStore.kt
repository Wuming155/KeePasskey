package com.keepasskey.app.autofill

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保存侧独立黑名单（ISSUE-P3-43 ③，对齐 Monica `save_blocked_targets`）。
 *
 * 与包级填充黑名单（`AutofillBlocklistStore`）**刻意分离**的理由：
 * 「不要再问我是否保存这个应用的密码」与「不要再给这个应用填充密码」是两种独立诉求。
 * 用同一份名单承载会迫使用户为了免除保存提示而一并放弃填充，属能力退化。
 *
 * 设计约束：
 * - 命中语义为**静默跳过保存**（`SaveCallback.onSuccess()`）：不落库、不报错、不打扰用户；
 * - **fail-closed**：包名非法 / 不可识别时 [isSaveBlocked] 返回 **true**——
 *   与填充侧同等保守（宁可不写入，也不把凭据落到无法识别归属的目标上）；
 * - 包名判据复用 [AutofillPackageNames]，与包级、字段级三处同源；
 * - `context` 为 null（纯 JVM 单测）时退化为内存语义。
 */
@Singleton
class AutofillSaveBlocklistStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 双构造器冲突
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val blockedFlow = MutableStateFlow(readPersisted())

    /** 「不再提示保存」的应用包名快照（升序），供设置页观察与管理。 */
    val blockedPackages: StateFlow<List<String>> = blockedFlow.asStateFlow()

    /**
     * 该应用的凭据保存是否已被用户禁止。
     *
     * **fail-closed**：包名非法一律按已禁止处理，绝不因非法输入放行写入。
     */
    fun isSaveBlocked(packageName: String): Boolean {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return true
        return blockedFlow.value.contains(normalized)
    }

    /**
     * 加入「不再提示保存」名单。
     * @return true=新增成功；false=包名非法或已在名单中（调用方据此如实提示，不静默吞掉）
     */
    fun add(packageName: String): Boolean {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return false
        val current = blockedFlow.value
        if (current.contains(normalized)) return false
        persist(current + normalized)
        return true
    }

    /**
     * 移出「不再提示保存」名单。
     * @return true=移除成功；false=包名非法或本就不在名单中
     */
    fun remove(packageName: String): Boolean {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return false
        val current = blockedFlow.value
        if (!current.contains(normalized)) return false
        persist(current - normalized)
        return true
    }

    private fun persist(list: List<String>) {
        val sorted = list.sorted()
        blockedFlow.value = sorted
        prefs?.edit()?.putStringSet(K_SAVE_BLOCKED_TARGETS, sorted.toSet())?.apply()
    }

    private fun readPersisted(): List<String> {
        val raw = prefs?.getStringSet(K_SAVE_BLOCKED_TARGETS, null) ?: return emptyList()
        // getStringSet 返回的是 SharedPreferences 内部集合引用，必须整体拷贝后再加工
        return raw.toList().mapNotNull { AutofillPackageNames.normalize(it) }.sorted()
    }

    private companion object {
        const val PREFS_NAME = "keepasskey_autofill_save_blocklist"
        const val K_SAVE_BLOCKED_TARGETS = "save_blocked_targets"
    }
}
