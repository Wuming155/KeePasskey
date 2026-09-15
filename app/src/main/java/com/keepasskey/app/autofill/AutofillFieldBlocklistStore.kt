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
 * 字段签名级屏蔽仓库（ISSUE-P3-43 ②，抗枚举加固见 ISSUE-P3-46）。
 *
 * 与包级黑名单（`AutofillBlocklistStore`）的分工：包级屏蔽整个应用，本仓库只屏蔽
 * 「某应用某表单的某个框」，粒度为 **包名 + 域 + 角色**，用户在手动选择器内一键写入。
 *
 * 设计约束：
 * - **不可逆、抗枚举持久化**：只落 [AutofillFieldSignature] 计算出的定长 hex 签名，
 *   **绝不**持久化包名、域名、表单内容明文，也**不落盘任何签名密钥材料**——
 *   密钥驻留 Android Keystore 内不可导出（见 [HmacFieldSignatureSource] 与
 *   [KeystoreHmacFieldSignatureSource]）；安全边界详见 [AutofillFieldSignature] KDoc；
 * - **fail-closed**：签名无法计算（包名非法 / 密钥不可用）时 [isBlocked] 返回 **true**——
 *   宁可少填一次，也不因非法输入把凭据下发到无法识别的目标；
 *   同时区分两类原因（ISSUE-P3-113）：**密钥不可用**会置位 [signatureUnavailable]，
 *   由自动填充健康自检卡片把「静默不填充」转为可归因的显式告警，而非仅靠用户自行猜测；
 * - **不可枚举回显**：签名不可逆，故设置页只能展示**条数**与「全部清除」，
 *   不存在「显示已屏蔽的站点列表」这一选项（如实呈现能力边界，不伪造列表）；
 * - **保守迁移**：从 v1（随机盐 + SHA-256）升级到 v2（Keystore HMAC）时，
 *   旧签名与旧盐一并清除——旧数据不可迁移且新签名不会误命中，等价于屏蔽记录清空；
 * - **可测性**：`context` 为 null（纯 JVM 单测）时退化为内存语义；签名密钥来源经
 *   [HmacFieldSignatureSource] 注入，单测可注入测试密钥。
 */
@Singleton
class AutofillFieldBlocklistStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 双构造器冲突
    @ApplicationContext private val context: Context?,
    private val signatureSource: HmacFieldSignatureSource
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val blockedFlow = MutableStateFlow(loadPersisted())

    /**
     * 签名密钥不可用标志（ISSUE-P3-113）。
     *
     * 判定为「已屏蔽」的 **fail-closed** 方向不变；但该方向的副作用是**静默放弃填充**——
     * 用户只看到「不出候选」而无从归因。本标志把该故障显式化，由自动填充健康自检卡片
     * （`AutofillHealthIssue.FIELD_BLOCK_SIGNATURE_UNAVAILABLE`）呈现修复指引。
     *
     * **只对「密钥不可用」置位，不对「输入非法」置位**：后者（包名不符合规范）是查询侧
     * 的正常干扰项，若一并置位会让 UI 报出与用户无关的告警。两者由 [hmacKeyUnavailable] 探针区分。
     */
    private val signatureUnavailableFlow = MutableStateFlow(false)

    /** 已屏蔽签名快照（升序，仅用于计数与清除；签名不可逆，无法回显为可读目标）。 */
    val blockedSignatures: StateFlow<List<String>> = blockedFlow.asStateFlow()

    /** 字段签名密钥是否不可用（ISSUE-P3-113；仅供健康自检与 UI 呈现，不改变 fail-closed 判定）。 */
    val signatureUnavailable: StateFlow<Boolean> = signatureUnavailableFlow.asStateFlow()

    /**
     * 该「包名 + 域 + 角色」是否已被用户屏蔽。
     *
     * **fail-closed**：签名不可计算时返回 true（视为已屏蔽），并（若为密钥不可用）
     * 置位 [signatureUnavailable] 使故障对用户可见。
     */
    fun isBlocked(packageName: String, webDomain: String?, role: AutofillFieldRole): Boolean {
        val signature = signatureOf(packageName, webDomain, role) ?: run {
            if (hmacKeyUnavailable()) signatureUnavailableFlow.value = true
            return true
        }
        return blockedFlow.value.contains(signature)
    }

    /**
     * 屏蔽该「包名 + 域 + 角色」。
     * @return true=新增成功；false=签名不可计算或已在屏蔽集中（调用方据此如实提示，不谎报成功）
     */
    fun block(packageName: String, webDomain: String?, role: AutofillFieldRole): Boolean {
        val signature = signatureOf(packageName, webDomain, role) ?: return false
        val current = blockedFlow.value
        if (current.contains(signature)) return false
        persist(current + signature)
        return true
    }

    /**
     * 解除该「包名 + 域 + 角色」的屏蔽。
     *
     * 无 UI 入口消费（签名不可逆，设置页无法定位单条），保留仅供**填充侧同上下文**的
     * 撤销路径与单测使用；不对外暴露"看起来能管理列表"的假能力。
     * @return true=移除成功；false=签名不可计算或本就未被屏蔽
     */
    fun unblock(packageName: String, webDomain: String?, role: AutofillFieldRole): Boolean {
        val signature = signatureOf(packageName, webDomain, role) ?: return false
        val current = blockedFlow.value
        if (!current.contains(signature)) return false
        persist(current - signature)
        return true
    }

    /** 清除全部字段级屏蔽（设置页唯一的批量管理入口）。@return 被清除的条数 */
    fun clearAll(): Int {
        val removed = blockedFlow.value.size
        if (removed > 0) persist(emptyList())
        return removed
    }

    private fun signatureOf(packageName: String, webDomain: String?, role: AutofillFieldRole): String? =
        AutofillFieldSignature.of(signatureSource, packageName, webDomain, role)

    /**
     * 密钥可用性探针（ISSUE-P3-113）：对**非秘密**常量原文做一次 MAC。
     *
     * [AutofillFieldSignature.of] 把「输入非法」与「密钥不可用」都折叠为 `null`，
     * 而二者对用户的意义完全不同（前者无需告警，后者意味着填充被保守放弃）。
     * 探针把两者区分开：只有本探针也失败时，才认定**密钥**不可用。
     *
     * 仅在签名已返回 `null` 的路径上调用，且置位后不再重复探测——正常路径零额外开销。
     */
    private fun hmacKeyUnavailable(): Boolean {
        if (signatureUnavailableFlow.value) return true
        return signatureSource.hmacSha256(KEY_PROBE) == null
    }

    private fun persist(list: List<String>) {
        val sorted = list.sorted()
        blockedFlow.value = sorted
        prefs?.edit()?.putStringSet(K_BLOCKED_SIGNATURES, sorted.toSet())?.apply()
    }

    private fun loadPersisted(): List<String> {
        migrateLegacyStorage()
        val raw = prefs?.getStringSet(K_BLOCKED_SIGNATURES, null) ?: return emptyList()
        // getStringSet 返回的是 SharedPreferences 内部集合引用，必须整体拷贝后再加工；
        // 同时丢弃长度/字符集不合法的脏条目（外部篡改或旧格式残留）
        return raw.toList().filter { isWellFormedSignature(it) }.sorted()
    }

    /**
     * 旧格式存储的保守迁移（ISSUE-P3-46）。
     *
     * v1 时代：签名 = `SHA-256(随机盐 ‖ ...)`，盐与签名同库落盘。升级到 v2（Keystore HMAC）后，
     * 旧盐既已无用、又属**密钥材料**，必须删除；旧签名与新签名处于不同密钥体系，
     * 不可能命中新目标，但仍会长期占据计数——故一并清除。
     *
     * 该迁移**只做一次**（以 schema 标记为闸门），且为**保守失效**：
     * 屏蔽记录回到「会填充」的默认态，不会产生误屏蔽。
     */
    private fun migrateLegacyStorage() {
        val store = prefs ?: return
        if (store.getString(K_SCHEMA, null) == AutofillFieldSignature.SCHEMA_VERSION) return
        store.edit()
            .remove(K_BLOCKED_SIGNATURES)
            .remove(K_SALT)
            .putString(K_SCHEMA, AutofillFieldSignature.SCHEMA_VERSION)
            .apply()
    }

    private fun isWellFormedSignature(value: String): Boolean =
        value.length == AutofillFieldSignature.SIGNATURE_HEX_LENGTH && value.all { it in HEX_ALPHABET }

    private companion object {
        const val PREFS_NAME = "keepasskey_autofill_field_blocklist"
        const val K_BLOCKED_SIGNATURES = "blocked_field_signatures"

        /** 已废弃的 v1 随机盐键：仅用于迁移时删除，确保 prefs 中不再残留密钥材料。 */
        const val K_SALT = "field_signature_salt"

        /** 签名格式标记：不等于当前 schema 时触发一次保守迁移。 */
        const val K_SCHEMA = "field_signature_schema"

        const val HEX_ALPHABET = "0123456789abcdef"

        /**
         * 密钥可用性探针原文（ISSUE-P3-113）：**非秘密**常量，仅用于判定密钥能否完成 MAC。
         * 取值参与不了任何签名语义（不进 [AutofillFieldSignature] 的规范化原文），
         * 故不构成跨设备可关联的固定特征。
         */
        val KEY_PROBE = "keepasskey.field_signature.probe".toByteArray(Charsets.UTF_8)
    }
}
