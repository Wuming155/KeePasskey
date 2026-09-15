package com.keepasskey.app.passkey

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.app.autofill.AutofillPackageNames
import com.keepasskey.app.security.CallerCertDigests
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credential Manager（CM）通道的调用方「首次绑定」信任存储（**ISSUE-P2-83**）。
 *
 * ## 缺陷形态
 *
 * `android://<包名>` 维度在 CM 通道只做**纯字符串相等**（`DomainMatcher.isAndroidPackageMatch`），
 * 不比对调用方签名 ⇒ 以同 `applicationId` 侧载的应用可命中为真实应用建立的条目。
 * 这与 `ISSUE-P2-46`（自动填充通道）**同根因、不同通道**，故 `ISSUE-P2-46` 的闭环
 * **不得**读作「`android://` 维度整体已加固」。
 *
 * ## 与 [com.keepasskey.app.autofill.AutofillCallerTrustStore] / `ISSUE-P1-24` 的语义边界
 *
 * **刻意不复用同一存储**，三条边界逐条写明：
 *
 * 1. **独立文件、独立键空间**（`keepasskey_cm_caller_trust` vs `keepasskey_autofill_caller_trust`）。
 *    自动填充侧的信任语义是「用户在**确认页勾选**『记住此应用』」；若共用存储，一次 CM 授权
 *    就会让自动填充侧的首现闸门**静默不再询问**——那是对 `ISSUE-P1-24` AC② 的**削弱**，
 *    本项明令禁止。两个通道各自授权、各自询问，任一通道的授权都不会为另一通道背书。
 * 2. **写入点不同**：CM 的生物识别路径**没有勾选位**（系统 `BiometricPrompt` 不可承载 UI 控件），
 *    故 CM 侧的授权写入落在**用户显式把凭据交给该调用方**的流程里——保存（[PasswordSaveActivity]）
 *    与注册（[PasskeyCreateActivity]）两条，二者都在受保护窗口内、都由用户主动发起。
 * 3. **同一 fail-closed 口径**：签名摘要不可读时**不写入**「仅包名」降级键——
 *    「只认包名」正是本项要消灭的形态；此时该调用方保持**未绑定**。
 *
 * ## 判定口径
 *
 * [isTrusted] 按调用方**全部**签名摘要判定（任一命中即通过，`ISSUE-P3-93` 的多签名者模型）；
 * 写入时记录配置的**主摘要**（与自动填充侧 `AutofillPickerActivity` 的写入口径一致）。
 *
 * 可测性：`context` 为 null（纯 JVM 单元测试注入）时退化为内存语义，不破坏单测。
 */
@Singleton
class CredentialManagerCallerTrustStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val memoryTrusted = mutableSetOf<String>()

    /**
     * 是否已获用户显式授权（**任一**签名摘要命中即通过）。
     *
     * fail-closed 两条：包名非法 → false；摘要**全不可读** → false（不回退到「仅包名」，
     * 与 [com.keepasskey.app.autofill.AutofillCallerTrustStore] 的单摘要重载口径不同——
     * 那里保留 `pkg|` 降级键是 `ISSUE-P1-24` 的既有取舍，本通道**不继承**该放宽）。
     */
    fun isTrusted(packageName: String, certDigests: CallerCertDigests): Boolean {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return false
        if (certDigests.isEmpty) return false
        return certDigests.anyMatch { isTrustedKey(normalized, it) }
    }

    /** 单摘要重载（兼容既有单摘要调用点 / 测试；同样拒绝空摘要） */
    fun isTrusted(packageName: String, certSha256Hex: String?): Boolean {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return false
        val digest = certSha256Hex.normalizedDigest() ?: return false
        return isTrustedKey(normalized, digest)
    }

    /**
     * 该包名是否**已有任一签名绑定**（ISSUE-P2-83 AC② 的判定支点）。
     *
     * 用于区分两种「[isTrusted] 返回 false」——它们的安全含义**完全相反**：
     * - **已有绑定但摘要不匹配** ⇒ 绑定过的应用被换签名 / 被同 `applicationId` 侧载应用顶替
     *   ⇒ 必须**不命中**；
     * - **从未绑定** ⇒ 无法判定（无参照），退回包名维度的既有行为（见
     *   [com.keepasskey.app.passkey.CredentialManagerPackageBindingGate] 的边界说明）。
     *
     * 只看键的**存在性**，不读值，故不泄露任何摘要内容。
     */
    fun hasAnyBindingFor(packageName: String): Boolean {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return false
        val prefix = "$normalized|"
        val persisted = prefs?.all?.keys?.any { it.startsWith(prefix) } ?: false
        return persisted || memoryTrusted.any { it.startsWith(prefix) }
    }

    /**
     * 记录显式授权（用户在保存 / 注册流程中把凭据交给该调用方）。
     *
     * @return true=写入成功；false=包名非法或摘要不可读（**均保持未绑定**，调用方不得据此放行）
     */
    fun trust(packageName: String, certSha256Hex: String?): Boolean {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return false
        val digest = certSha256Hex.normalizedDigest() ?: return false
        val key = trustKey(normalized, digest)
        prefs?.edit()?.putBoolean(key, true)?.apply() ?: memoryTrusted.add(key)
        return true
    }

    /** 撤销授权（对称操作；撤销通道另有既有的「屏蔽该应用」黑名单，本存储不设独立 UI） */
    fun untrust(packageName: String, certSha256Hex: String?): Boolean {
        val normalized = AutofillPackageNames.normalize(packageName) ?: return false
        val digest = certSha256Hex.normalizedDigest() ?: return false
        val key = trustKey(normalized, digest)
        val existed = prefs?.contains(key) ?: memoryTrusted.contains(key)
        if (!existed) return false
        prefs?.edit()?.remove(key)?.apply() ?: memoryTrusted.remove(key)
        return true
    }

    private fun isTrustedKey(normalizedPackage: String, certSha256Hex: String): Boolean {
        val key = trustKey(normalizedPackage, certSha256Hex)
        return prefs?.getBoolean(key, false) ?: memoryTrusted.contains(key)
    }

    private fun trustKey(normalizedPackage: String, certSha256Hex: String): String =
        "$normalizedPackage|$certSha256Hex"

    /** 摘要归一化：trim + 大写（`CallerCertDigests` 同口径）；空 / 全空白 → null（fail-closed） */
    private fun String?.normalizedDigest(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)

    private companion object {
        const val PREFS_NAME = "keepasskey_cm_caller_trust"
    }
}
