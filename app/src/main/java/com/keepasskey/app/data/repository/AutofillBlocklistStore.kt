package com.keepasskey.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动填充黑名单仓库（TASK-44 整改，对齐 KP2A「禁用自动填充查询」语义）。
 *
 * 背景：此前设置页仅展示 `disabledAutofillQueriesCount` 计数，而该计数**无任何写入方**，
 * 黑名单的数据源、持久化与填充侧消费全部缺失（TASK-36 已诚实化下架写死示例条目）。
 * 本仓库补上完整生命周期的数据底座：以包名集合为单位持久化，并对外提供
 * 变更可观察的 [blockedPackages] 与决策用的 [isBlocked]。
 *
 * 设计约束：
 * - **fail-closed**：仅用于「不填充」这一保守决策，命中即不下发数据集/凭据候选；
 *   包名不可信或无法解析时按未命中处理（不放大权限、不因异常放行数据）。
 * - **包名严格校验**：仅接受 Android 官方包名形态（≥2 段、每段字母开头、仅 `[A-Za-z0-9_]`），
 *   非法输入拒绝入库并由返回值如实告知调用方，杜绝拼错包名静默写入导致的「假屏蔽」。
 * - **可测性**：`context` 为 null（纯 JVM 单元测试注入）时退化为内存语义，不破坏单测。
 * - 与 [ExtendedSettingsStore] 同一进程单例（自动填充服务未声明独立进程），
 *   写入即时对填充侧生效；跨进程场景由 SharedPreferences 落盘保证重启后恢复。
 *
 * 已知语义边界（登记 STATUS §6）：浏览器类应用以自身包名发起 Credential Manager 请求，
 * 屏蔽浏览器包名即屏蔽其承载的全部站点填充，此为「按应用屏蔽」语义的固有结果。
 */
@Singleton
class AutofillBlocklistStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 双构造器冲突
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val blockedFlow = MutableStateFlow(readPersisted())

    /** 黑名单快照（按包名升序），供设置页与详情页观察 */
    val blockedPackages: StateFlow<List<String>> = blockedFlow.asStateFlow()

    /** 命中黑名单判定（fail-closed：包名非法或未命中一律按未屏蔽处理） */
    fun isBlocked(packageName: String): Boolean {
        val normalized = normalize(packageName) ?: return false
        return blockedFlow.value.contains(normalized)
    }

    /**
     * 加入黑名单。
     * @return true=新增成功；false=包名非法或已在黑名单中（调用方可据此如实提示，不静默吞掉）
     */
    fun add(packageName: String): Boolean {
        val normalized = normalize(packageName) ?: return false
        val current = blockedFlow.value
        if (current.contains(normalized)) return false
        persist(current + normalized)
        return true
    }

    /**
     * 从黑名单移除。
     * @return true=移除成功；false=包名非法或本就不在黑名单中
     */
    fun remove(packageName: String): Boolean {
        val normalized = normalize(packageName) ?: return false
        val current = blockedFlow.value
        if (!current.contains(normalized)) return false
        persist(current - normalized)
        return true
    }

    private fun persist(list: List<String>) {
        val sorted = list.sorted()
        blockedFlow.value = sorted
        prefs?.edit()?.putStringSet(K_BLOCKED_PACKAGES, sorted.toSet())?.apply()
    }

    private fun readPersisted(): List<String> {
        val raw = prefs?.getStringSet(K_BLOCKED_PACKAGES, null) ?: return emptyList()
        // getStringSet 返回的是 SharedPreferences 内部集合引用，必须整体拷贝后再加工
        return raw.toList().mapNotNull { normalize(it) }.sorted()
    }

    /**
     * 归一化并校验包名：小写化后按 Android 官方包名规则校验
     * （≥2 段、每段字母开头、仅 `[a-z0-9_]`、总长 ≤255）。非法返回 null。
     */
    internal fun normalize(packageName: String): String? {
        val trimmed = packageName.trim().lowercase()
        if (trimmed.length !in MIN_PACKAGE_LENGTH..MAX_PACKAGE_LENGTH) return null
        return if (PACKAGE_PATTERN.matches(trimmed)) trimmed else null
    }

    private companion object {
        const val PREFS_NAME = "keepasskey_autofill_blocklist"
        const val K_BLOCKED_PACKAGES = "blocked_packages"
        /** "a.b" 为最短合法包名 */
        const val MIN_PACKAGE_LENGTH = 3
        const val MAX_PACKAGE_LENGTH = 255
        val PACKAGE_PATTERN = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
    }
}
