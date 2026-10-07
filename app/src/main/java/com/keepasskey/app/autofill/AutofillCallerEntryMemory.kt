package com.keepasskey.app.autofill

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.app.security.CallerCertDigests
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「调用方 ↔ 条目」关联记忆（吸收 Monica `AutofillPreferences` 的互动记忆，
 * `completeAutofillInteraction` / `LAST_FILLED_IDENTIFIER`，见
 * `docs/references/自动填充关联记忆与字段识别的参考项目对照.md` §3.1）。
 *
 * ## 用途
 *
 * 纯 App（无 `webDomain`）表单在条目**未声明** `android://<包名>` 绑定前，严格匹配恒零候选，
 * 用户只能每次手选。本记忆把「用户在某调用方上**显式选中/确认填充**过哪一条」记下来，
 * 使该调用方**下一次**请求即可直接得到候选，无需重选（`ISSUE-P3-528`）。
 *
 * ## 准入口径（本仓改 Monica 的键）
 *
 * Monica 的记忆键是裸 interaction identifier（包名 / 域及其别名）；本仓沿用既有信任模型，
 * **键＝归一化包名 + 签名证书 SHA-256**（与 [AutofillCallerTrustStore] 同一构造）：
 * - 同包名换签名（重打包 / 侧载冒名）**不**命中——记忆不是包名维度的旁路；
 * - 摘要**全部不可读**时退回「仅包名」降级键（与信任存储同一取舍），摘要可读时不回退到它；
 * - 包名非法（[AutofillPackageNames.normalize] 为 null）一律不记录也不召回（fail-closed）。
 *
 * **本记忆不是放行判定**：它只决定「该条目是否进入候选集合」，进入后仍走既有字段级屏蔽、
 * 库锁定复核与**强制二次确认**（`Dataset.setAuthentication`）——用户仍看到调用方归属并要求确认。
 * 撤销通道＝既有自动填充黑名单（[com.keepasskey.app.data.repository.AutofillBlocklistStore]，
 * 屏蔽该应用即完全停止向其下发，记忆随之失效）；本类不设独立 UI。
 *
 * ## 存储
 *
 * 明文 `SharedPreferences`（与 [AutofillCallerTrustStore] 同级），值仅承载**条目 UUID（hex）与
 * 记录时刻**——不含任何凭据值、不落域名与账号。条目 UUID 为**非敏感标识**，但「哪个包用过哪条
 * 条目」本身构成元数据，故：容量上限 [MAX_ENTRIES]（超出按记录时刻淘汰最旧），且**刻意不**注册为
 * 会话锁定观察者（`AutofillLastFilledStore` 的锁定即清是「跨调用方误置顶」的收敛手段；
 * 本记忆的键含包名 + 签名，不存在跨调用方串用，跨会话保留正是本条的功能本体）。
 *
 * 可测性：`context` 为 null（纯 JVM 单测注入）时退化为进程内存语义。
 */
@Singleton
class AutofillCallerEntryMemory @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 无持久化层时的内存回退（仅用于纯 JVM 单测；生产始终有 prefs） */
    private val memoryAssociations = linkedMapOf<String, String>()

    /**
     * 记录「该调用方最近由用户显式交付过的条目」。
     *
     * 调用点必须是**用户显式动作已完成交付**处（选择器选中并回传 / 确认页确认并回传），
     * 不得在候选下发阶段写入——否则「看到候选」会被误记成「用户选了它」。
     */
    fun remember(packageName: String, certSha256Hex: String?, entryId: String) {
        val key = memoryKey(packageName, certSha256Hex) ?: return
        val normalizedEntryId = entryId.trim()
        if (normalizedEntryId.isEmpty()) return
        val value = "$normalizedEntryId$VALUE_SEPARATOR${System.currentTimeMillis()}"
        val p = prefs
        if (p == null) {
            memoryAssociations.remove(key)
            memoryAssociations[key] = value
            trimMemoryFallback()
            return
        }
        p.edit().putString(key, value).apply()
        evictOverflow(p)
    }

    /**
     * [remember] 的摘要集合重载：记录落在**主摘要**（[CallerCertDigests.primary]，当前有效签名者）
     * 之上，与 `AutofillCallerTrustStore.trust` 的写入点取值同源；召回侧仍按全部摘要任一命中。
     */
    fun remember(packageName: String, certDigests: CallerCertDigests, entryId: String) {
        remember(packageName, certDigests.primary, entryId)
    }

    /**
     * 召回该调用方最近被显式交付过的条目标识；无记录 / 包名非法时返回 null。
     *
     * @param certDigests 调用方**全部**签名摘要（当前 + 历史）：任一命中即召回——
     *   与 [AutofillCallerTrustStore.isTrusted] 同口径，签名轮换期不得因只取首个摘要而失配
     */
    fun recall(packageName: String, certDigests: CallerCertDigests): String? {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return null
        val keys = if (certDigests.isEmpty) {
            listOf("$normalized$KEY_SEPARATOR")
        } else {
            certDigests.values.map { "$normalized$KEY_SEPARATOR$it" }
        }
        return keys.firstNotNullOfOrNull { readEntryId(it) }
    }

    /** 清除全部记忆（测试与未来的显式清理入口用；生产撤销通道是自动填充黑名单） */
    fun clear() {
        memoryAssociations.clear()
        prefs?.edit()?.clear()?.apply()
    }

    private fun readEntryId(key: String): String? {
        val raw = prefs?.getString(key, null) ?: memoryAssociations[key] ?: return null
        val entryId = raw.substringBefore(VALUE_SEPARATOR).trim()
        return entryId.ifEmpty { null }
    }

    /** 记忆键：包名归一化 + 证书摘要（null / 不可读记空段）；包名非法返回 null（fail-closed） */
    private fun memoryKey(packageName: String, certSha256Hex: String?): String? {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return null
        return "$normalized$KEY_SEPARATOR${certSha256Hex.orEmpty()}"
    }

    /** 容量闸门：按键数超限时按记录时刻淘汰最旧（prefs 无序遍历，故只依赖值内时刻） */
    private fun evictOverflow(p: SharedPreferences) {
        val entries = p.all
        if (entries.size <= MAX_ENTRIES) return
        val overflow = entries.entries
            .sortedBy { (it.value as? String)?.substringAfter(VALUE_SEPARATOR, "")?.toLongOrNull() ?: 0L }
            .take(entries.size - MAX_ENTRIES)
        val editor = p.edit()
        overflow.forEach { editor.remove(it.key) }
        editor.apply()
    }

    /** 内存回退的容量闸门（`LinkedHashMap` 保插入序，等价于「淘汰最旧」） */
    private fun trimMemoryFallback() {
        while (memoryAssociations.size > MAX_ENTRIES) {
            val oldest = memoryAssociations.keys.firstOrNull() ?: return
            memoryAssociations.remove(oldest)
        }
    }

    private companion object {
        const val PREFS_NAME = "keepasskey_autofill_caller_entry"

        /** 键分隔符（包名与摘要）：与 `AutofillCallerTrustStore` 同构造 */
        const val KEY_SEPARATOR = "|"

        /** 值内分隔符（条目 id 与记录时刻）：`|` 不会出现在 hex 条目 id 中 */
        const val VALUE_SEPARATOR = "|"

        /**
         * 记忆容量上限。与 `AutofillLoginFieldMemory` 的 16 上下文闸门同量级——
         * 兜底形态的辅助记忆，不做无界增长。
         */
        const val MAX_ENTRIES = 16
    }
}
