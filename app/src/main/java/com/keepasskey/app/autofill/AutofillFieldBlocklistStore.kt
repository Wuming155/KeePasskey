package com.keepasskey.app.autofill

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字段签名级屏蔽仓库（ISSUE-P3-43 ②，对齐 Monica `blocked_field_signatures`）。
 *
 * 与包级黑名单（`AutofillBlocklistStore`）的分工：包级屏蔽整个应用，本仓库只屏蔽
 * 「某应用某表单的某个框」，粒度为 **包名 + 域 + 角色**，用户在手动选择器内一键写入。
 *
 * 设计约束：
 * - **不可逆持久化**：只落 [AutofillFieldSignature] 计算出的定长 hex 签名与一枚随机盐，
 *   **绝不**持久化包名、域名或任何表单内容明文（安全边界详见 [AutofillFieldSignature] KDoc）；
 * - **fail-closed**：签名无法计算（包名非法 / 盐缺失）时 [isBlocked] 返回 **true**——
 *   宁可少填一次，也不因非法输入把凭据下发到无法识别的目标；
 * - **不可枚举回显**：签名不可逆，故设置页只能展示**条数**与「全部清除」，
 *   不存在「显示已屏蔽的站点列表」这一选项（如实呈现能力边界，不伪造列表）；
 * - **可测性**：`context` 为 null（纯 JVM 单测）时退化为内存语义（含内存随机盐）。
 */
@Singleton
class AutofillFieldBlocklistStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 双构造器冲突
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 每安装随机盐：持久化于同一 prefs；无 context 时退化为进程内随机盐。 */
    private val salt: ByteArray = readOrCreateSalt()

    private val blockedFlow = MutableStateFlow(readPersisted())

    /** 已屏蔽签名快照（升序，仅用于计数与清除；签名不可逆，无法回显为可读目标）。 */
    val blockedSignatures: StateFlow<List<String>> = blockedFlow.asStateFlow()

    /**
     * 该「包名 + 域 + 角色」是否已被用户屏蔽。
     *
     * **fail-closed**：签名不可计算时返回 true（视为已屏蔽）。
     */
    fun isBlocked(packageName: String, webDomain: String?, role: AutofillFieldRole): Boolean {
        val signature = signatureOf(packageName, webDomain, role) ?: return true
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
        AutofillFieldSignature.of(salt, packageName, webDomain, role)

    private fun persist(list: List<String>) {
        val sorted = list.sorted()
        blockedFlow.value = sorted
        prefs?.edit()?.putStringSet(K_BLOCKED_SIGNATURES, sorted.toSet())?.apply()
    }

    private fun readPersisted(): List<String> {
        val raw = prefs?.getStringSet(K_BLOCKED_SIGNATURES, null) ?: return emptyList()
        // getStringSet 返回的是 SharedPreferences 内部集合引用，必须整体拷贝后再加工；
        // 同时丢弃长度/字符集不合法的脏条目（外部篡改或旧格式残留）
        return raw.toList().filter { isWellFormedSignature(it) }.sorted()
    }

    private fun isWellFormedSignature(value: String): Boolean =
        value.length == AutofillFieldSignature.SIGNATURE_HEX_LENGTH && value.all { it in HEX_ALPHABET }

    /**
     * 读取或首次生成随机盐。
     *
     * 盐丢失（如用户清数据）会使全部既有签名失效——等价于屏蔽记录被清空，
     * 属**保守失效**（回到"会填充"的默认态），不会造成误屏蔽。
     */
    private fun readOrCreateSalt(): ByteArray {
        val stored = prefs?.getString(K_SALT, null)
        val decoded = stored?.let { decodeHex(it) }
        if (decoded != null && decoded.size == SALT_BYTES) return decoded

        val fresh = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        prefs?.edit()?.putString(K_SALT, encodeHex(fresh))?.apply()
        return fresh
    }

    /**
     * 盐编解码刻意使用**手写 hex** 而非 `android.util.Base64`：
     * 后者在纯 JVM 单测中是未实现的桩方法，会让存储层在测试环境下静默退化，
     * 从而使「屏蔽是否真的生效」这一断言失去意义。
     */
    private fun encodeHex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(byte) }

    private fun decodeHex(value: String): ByteArray? {
        if (value.length % 2 != 0 || value.any { it !in HEX_ALPHABET }) return null
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(HEX_RADIX).toByte()
        }
    }

    private companion object {
        const val PREFS_NAME = "keepasskey_autofill_field_blocklist"
        const val K_BLOCKED_SIGNATURES = "blocked_field_signatures"
        const val K_SALT = "field_signature_salt"
        const val SALT_BYTES = 32
        const val HEX_ALPHABET = "0123456789abcdef"
        const val HEX_RADIX = 16
    }
}
