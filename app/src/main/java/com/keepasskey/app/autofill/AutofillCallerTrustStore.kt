package com.keepasskey.app.autofill

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 确认页展示的调用方归属信息（ISSUE-P1-24 AC①）。
 *
 * **不可伪造锚点**只有 [packageName]（系统结构树 `activityComponent` 提供，非调用方自报）与
 * [certSha256Hex]（本应用经 PackageManager 读取的签名证书摘要）；label / icon 可被调用方
 * 自声明，不作为归属依据。[webDomain] 为**归属校验后**的域（受信浏览器白名单或 DAL 验证
 * 通过），表单自报且未通过校验的域不会到达确认页（不下发候选）。
 *
 * [certSha256Hex] 可为 null：Android 11+ 包可见性受限（无 `<queries>`）或签名读取失败——
 * 展示侧必须如实标注「不可读」，不得以占位摘要冒充。
 */
data class AutofillCallerAttribution(
    val packageName: String,
    val certSha256Hex: String?,
    val webDomain: String?,
    /** 是否为「首次出现」目标（未获用户显式授权）——true 时确认页要求显式授权（AC②） */
    val firstOccurrence: Boolean
)

/**
 * 调用方「首次绑定」信任存储（ISSUE-P1-24 AC②）。
 *
 * 持久化记录用户在确认页**显式授权**过的调用方，键为「包名 + 签名证书 SHA-256」：
 * - 同包名但签名不同（重打包 / 换签名）即视为**首次出现**，重新要求显式授权——
 *   这是包可见性受限下能做到的最强绑定；
 * - 证书摘要不可读时退化为仅按包名记录（并在确认页如实标注「不可读」）；
 * - 撤销通道为既有 [com.keepasskey.app.data.repository.AutofillBlocklistStore]
 *   （屏蔽该应用即完全停止向其填充），本存储不设独立的撤销 UI。
 *
 * 可测性：`context` 为 null（纯 JVM 单元测试注入）时退化为内存语义，不破坏单测。
 */
@Singleton
class AutofillCallerTrustStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 双构造器冲突
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val memoryTrusted = mutableSetOf<String>()

    /** 是否已获用户显式授权（fail-closed：包名非法一律按未授权处理） */
    fun isTrusted(packageName: String, certSha256Hex: String?): Boolean {
        val key = trustKey(packageName, certSha256Hex) ?: return false
        return prefs?.getBoolean(key, false) ?: memoryTrusted.contains(key)
    }

    /**
     * 记录显式授权（用户在确认页勾选「记住此应用」）。
     * @return true=写入成功；false=包名非法（调用方可据此如实提示，不静默吞掉）
     */
    fun trust(packageName: String, certSha256Hex: String?): Boolean {
        val key = trustKey(packageName, certSha256Hex) ?: return false
        prefs?.edit()?.putBoolean(key, true)?.apply() ?: memoryTrusted.add(key)
        return true
    }

    /**
     * 撤销授权（确认页取消勾选；对称操作，保证勾选状态与存储一致）。
     * @return true=撤销成功；false=包名非法或本就未授权
     */
    fun untrust(packageName: String, certSha256Hex: String?): Boolean {
        val key = trustKey(packageName, certSha256Hex) ?: return false
        val existed = prefs?.contains(key) ?: memoryTrusted.contains(key)
        if (!existed) return false
        prefs?.edit()?.remove(key)?.apply() ?: memoryTrusted.remove(key)
        return true
    }

    /**
     * 信任键：包名归一化（复用自动填充包名判据）+ 证书摘要（null 记空段）。
     * 包名非法返回 null（永不信任，fail-closed）。
     */
    private fun trustKey(packageName: String, certSha256Hex: String?): String? {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return null
        return "$normalized|${certSha256Hex.orEmpty()}"
    }

    private companion object {
        const val PREFS_NAME = "keepasskey_autofill_caller_trust"
    }
}
