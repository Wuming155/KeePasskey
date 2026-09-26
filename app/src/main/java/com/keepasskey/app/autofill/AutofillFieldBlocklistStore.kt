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
 * 字段级屏蔽仓库（ISSUE-P3-43 ②；ISSUE-P3-328 用户裁决去 Keystore 化改存明文目标键）。
 *
 * 与包级黑名单（`AutofillBlocklistStore`）的分工：包级屏蔽整个应用，本仓库只屏蔽
 * 「某应用某表单的某个框」，粒度为 **包名 + 域 + 角色**，用户在手动选择器内一键写入。
 *
 * 设计约束：
 * - **明文目标键持久化（2026-09-25 用户裁决）**：落盘内容为 [AutofillFieldSignature]
 *   构造的规范化目标键（`v3|包名|域|角色`），**可读出屏蔽目标**——该面与 `PD-36`（大附件
 *   明文落私有目录）同源：读取 prefs 需同 UID / root，而该对手同时可读 `.kdbx` 库文件，
 *   元数据不构成额外秘密面。原 v2 的 Keystore HMAC 抗枚举加固随 ISSUE-P3-328 移除；
 * - **fail-closed**：目标键无法构造（包名非法）时 [isBlocked] 返回 **true**——
 *   宁可少填一次，也不因非法输入把凭据下发到无法识别的目标；
 * - **管理面如实呈现**：设置页展示**条数**与「全部清除」；ISSUE-P3-328 起目标键为明文，
 *   具备按条定位的技术条件，但管理 UI 维持既有「整体清除」形态（本批不扩面）；
 * - **保守迁移**：schema 变更（v1 随机盐 → v2 Keystore 签名 → v3 明文键）时一次性清空
 *   存量条目——新旧格式不同域，不可能误命中，等价于屏蔽记录清空；
 * - **可测性**：`context` 为 null（纯 JVM 单测）时退化为内存语义。
 */
@Singleton
class AutofillFieldBlocklistStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 双构造器冲突
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val blockedFlow = MutableStateFlow(loadPersisted())

    /** 已屏蔽目标键快照（升序，仅用于计数与清除）。 */
    val blockedSignatures: StateFlow<List<String>> = blockedFlow.asStateFlow()

    /**
     * 该「包名 + 域 + 角色」是否已被用户屏蔽。
     *
     * **fail-closed**：目标键不可构造（包名非法）时返回 true（视为已屏蔽）。
     */
    fun isBlocked(packageName: String, webDomain: String?, role: AutofillFieldRole): Boolean {
        val target = targetOf(packageName, webDomain, role) ?: return true
        return blockedFlow.value.contains(target)
    }

    /**
     * 屏蔽该「包名 + 域 + 角色」。
     * @return true=新增成功；false=目标键不可构造或已在屏蔽集中（调用方据此如实提示，不谎报成功）
     */
    fun block(packageName: String, webDomain: String?, role: AutofillFieldRole): Boolean {
        val target = targetOf(packageName, webDomain, role) ?: return false
        val current = blockedFlow.value
        if (current.contains(target)) return false
        persist(current + target)
        return true
    }

    /**
     * 解除该「包名 + 域 + 角色」的屏蔽。
     *
     * 无 UI 入口消费，保留仅供**填充侧同上下文**的撤销路径与单测使用；
     * 不对外暴露"看起来能管理列表"的假能力。
     * @return true=移除成功；false=目标键不可构造或本就未被屏蔽
     */
    fun unblock(packageName: String, webDomain: String?, role: AutofillFieldRole): Boolean {
        val target = targetOf(packageName, webDomain, role) ?: return false
        val current = blockedFlow.value
        if (!current.contains(target)) return false
        persist(current - target)
        return true
    }

    /** 清除全部字段级屏蔽（设置页唯一的批量管理入口）。@return 被清除的条数 */
    fun clearAll(): Int {
        val removed = blockedFlow.value.size
        if (removed > 0) persist(emptyList())
        return removed
    }

    private fun targetOf(packageName: String, webDomain: String?, role: AutofillFieldRole): String? =
        AutofillFieldSignature.of(packageName, webDomain, role)

    private fun persist(list: List<String>) {
        val sorted = list.sorted()
        blockedFlow.value = sorted
        prefs?.edit()?.putStringSet(K_BLOCKED_TARGETS, sorted.toSet())?.apply()
    }

    private fun loadPersisted(): List<String> {
        migrateLegacyStorage()
        val raw = prefs?.getStringSet(K_BLOCKED_TARGETS, null) ?: return emptyList()
        // getStringSet 返回的是 SharedPreferences 内部集合引用，必须整体拷贝后再加工；
        // 同时丢弃非本版本格式的脏条目（外部篡改或旧格式残留）
        return raw.toList().filter { AutofillFieldSignature.isWellFormedTarget(it) }.sorted()
    }

    /**
     * 旧格式存储的保守迁移（ISSUE-P3-46；ISSUE-P3-328 扩至 v2 → v3）。
     *
     * v1：签名 = `SHA-256(随机盐 ‖ ...)`，盐与签名同库落盘；v2：Keystore HMAC hex 签名；
     * v3（现行）：明文目标键。三个版本两两不同域，旧条目不可能命中新目标，但会长期
     * 占据计数——schema 变更时一并清除。v1 的旧盐属**密钥材料**、v2 的 Keystore 别名
     * 随实现移除成为无人管理残留，均在此一并删除。
     *
     * 该迁移**只做一次**（以 schema 标记为闸门），且为**保守失效**：
     * 屏蔽记录回到「会填充」的默认态，不会产生误屏蔽。
     */
    private fun migrateLegacyStorage() {
        val store = prefs ?: return
        if (store.getString(K_SCHEMA, null) == AutofillFieldSignature.SCHEMA_VERSION) return
        store.edit()
            .remove(K_BLOCKED_TARGETS)
            .remove(K_BLOCKED_SIGNATURES)
            .remove(K_SALT)
            .putString(K_SCHEMA, AutofillFieldSignature.SCHEMA_VERSION)
            .apply()
        // 退役残留清理（ISSUE-P3-328）：v2 的字段签名 Keystore 别名随实现移除，
        // 迁移是一次性闸门路径，恰好在此删除该别名（Keystore 不可达时静默跳过）
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(LEGACY_FIELD_SIGNATURE_KEY_ALIAS)
        }
    }

    private companion object {
        const val PREFS_NAME = "keepasskey_autofill_field_blocklist"

        /** 现行键名：明文目标键集合（ISSUE-P3-328 起沿用 v2 的 prefs 键名会与 hex 签名混域，故换名） */
        const val K_BLOCKED_TARGETS = "blocked_field_targets"

        /** 已废弃的 v2 hex 签名键名：仅用于迁移时删除。 */
        const val K_BLOCKED_SIGNATURES = "blocked_field_signatures"

        /** 已废弃的 v1 随机盐键：仅用于迁移时删除，确保 prefs 中不再残留密钥材料。 */
        const val K_SALT = "field_signature_salt"

        /** 签名格式标记：不等于当前 schema 时触发一次保守迁移。 */
        const val K_SCHEMA = "field_signature_schema"

        /** 遗留（ISSUE-P3-328）：v2 字段签名 Keystore HMAC 密钥别名，仅供迁移时清理。 */
        const val LEGACY_FIELD_SIGNATURE_KEY_ALIAS = "com.keepasskey.autofill_field_signature"
    }
}
