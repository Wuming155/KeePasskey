package com.keepasskey.app.ui

import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 三条凭据通道开关的接线守卫（**ISSUE-P2-228** 验收 AC②③⑤，静态源码比对，
 * 体例沿用原 `AccessibilityNoticeWiringTest`（ISSUE-P3-324 已随被测链路移除删除）。
 *
 * 本批修掉的不是「判定算错」，而是**整条链路是摆设**：整改前三个开关只回写一个纯内存
 * `StateFlow`——无持久化键（重启回弹）、无生产消费方（关掉啥也不影响）、
 * 且副标题声称走「无障碍填充通道」而本应用**从未注册过 AccessibilityService**。
 * 故本类的断言全部指向「摆设复现」的三种形态：
 *
 * 1. **无消费方**：每个开关读取器都必须出现在对应通道服务的源码里（服务侧真求值）；
 * 2. **无持久化**：三个键必须同时出现在读侧与写侧；UI 值必须取自持久化快照 `extState`；
 * 3. **虚假措辞**：填充卡的文案不得再声称需要无障碍权限，`autofill_legacy*` 键不得残留。
 */
class AutofillChannelSwitchWiringTest {

    private val autofillServiceSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt"
        )

    private val credentialServiceSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/passkey/KeePasskeyCredentialProviderService.kt"
        )

    private val assemblerSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt"
        )

    private val storeSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt")

    private val extendedSettingsSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt")

    private val projectionSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        )

    private val preferencesControllerSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsPreferencesController.kt"
        )

    // ========== AC③：每个开关都有真实生产消费方 ==========

    @Test
    fun `自动填充服务开关被填充与保存两条路径消费`() {
        assertEquals(
            "填充与保存是两条独立回调，关掉必须两侧同时生效",
            2,
            autofillServiceSource.countInvocationsOf("isAutofillServiceEnabled()")
        )
    }

    @Test
    fun `凭据管理器开关被 get 与 create 两条入口消费`() {
        assertEquals(
            "两条 CM 入口都须求值，否则关掉仍能候选或仍能保存",
            2,
            credentialServiceSource.countInvocationsOf("isCredentialProviderEnabled()")
        )
    }

    @Test
    fun `通行密钥开关同时约束候选出口与创建入口`() {
        assertTrue(
            "已解锁检索侧必须过滤公钥候选",
            assemblerSource.contains("isPasskeySupportEnabled()")
        )
        assertTrue(
            "创建侧必须拒绝公钥类注册请求",
            credentialServiceSource.contains("isPasskeySupportEnabled()")
        )
    }

    // ========== AC②：持久化闭环，重启不回弹 ==========

    @Test
    fun `三个开关键在读侧与写侧同时存在`() {
        listOf(
            "K_CREDENTIAL_PROVIDER_ENABLED",
            "K_PASSKEY_SUPPORT_ENABLED",
            "K_AUTOFILL_SERVICE_ENABLED"
        ).forEach { key ->
            assertTrue("$key 缺读侧", storeSource.contains("$key, defaults."))
            assertTrue("$key 缺写侧", storeSource.contains(".putBoolean($key"))
        }
        // ISSUE-P3-324：旧版无障碍通道开关键同口径（默认 false，见 LegacyAutofillWiringTest）
        assertTrue(
            "K_AUTOFILL_LEGACY_ACCESSIBILITY_ENABLED 缺读侧",
            storeSource.contains("K_AUTOFILL_LEGACY_ACCESSIBILITY_ENABLED, defaults.")
        )
        assertTrue(
            "K_AUTOFILL_LEGACY_ACCESSIBILITY_ENABLED 缺写侧",
            storeSource.contains(".putBoolean(K_AUTOFILL_LEGACY_ACCESSIBILITY_ENABLED")
        )
    }

    @Test
    fun `UI 开关值取自持久化快照而非内存回显`() {
        listOf(
            "credentialProviderEnabled = extState.credentialProviderEnabled",
            "passkeySupportEnabled = extState.passkeySupportEnabled",
            "autofillServiceEnabled = extState.autofillServiceEnabled",
            // ISSUE-P3-324：旧版无障碍通道开关同口径
            "autofillLegacyAccessibilityEnabled = extState.autofillLegacyAccessibilityEnabled"
        ).forEach {
            assertTrue("投影未经持久化快照：$it", projectionSource.contains(it))
        }
    }

    /** 回归锁：整改前的「纯内存第二数据源」不得复活（同名字段即其残留形态） */
    @Test
    fun `内存态 AutofillUiState 载体已彻底移除`() {
        assertFalse(preferencesControllerSource.contains("AutofillUiState"))
        assertFalse(projectionSource.contains("internal data class AutofillUiState"))
        assertFalse(
            "偏好控制器不得再持有这三个开关的 setter（应经扩展偏好持久化）",
            preferencesControllerSource.contains("fun setAutofillServiceEnabled")
        )
    }

    // ========== 默认值两侧一致（ISSUE-P2-43 的踩坑形态） ==========

    @Test
    fun `无持久化层时三开关按默认开启判定`() {
        val store = ExtendedSettingsStore(null)
        assertTrue(store.isCredentialProviderEnabled())
        assertTrue(store.isPasskeySupportEnabled())
        assertTrue(store.isAutofillServiceEnabled())
    }

    @Test
    fun `数据类默认值与单键读取缺省同值`() {
        val settings = ExtendedSettings()
        assertTrue(settings.credentialProviderEnabled)
        assertTrue(settings.passkeySupportEnabled)
        assertTrue(settings.autofillServiceEnabled)
        // 单键读取路径不经过数据类默认值，故两侧必须锚在同一个常量上
        assertTrue(
            "单键缺省必须引用 CHANNEL_SWITCH_DEFAULT（否则两处会漂移）",
            storeSource.contains("CHANNEL_SWITCH_DEFAULT)")
        )
    }

    // ========== AC①：虚假措辞不得残留、不得回归 ==========

    @Test
    fun `自动填充卡文案不再声称虚假的无障碍权限`() {
        // ISSUE-P2-228：三条框架通道的文案不得再声称「无障碍填充方式」（当时无对应实现）。
        // ISSUE-P3-324 更新：旧版无障碍自动填充通道已真实落地（LegacyAutofillAccessibilityService），
        // 其 autofill_legacy_accessibility_* 文案中的「无障碍」措辞**如实**描述真实存在的服务，
        // 属白名单例外；其余 autofill* 文案仍不得出现无障碍措辞。
        // 逐条按 <string> 元素取值比对——整文件正则会把相邻元素连成一片而误报（首版即踩到）。
        val zh = readSource("app/src/main/res/values/strings.xml")
        val en = readSource("app/src/main/res/values-en/strings.xml")
        val legacyKeys = setOf(
            "autofill_legacy_accessibility_title",
            "autofill_legacy_accessibility_sub"
        )

        listOf(
            "中文" to zh,
            "英文" to en
        ).forEach { (label, source) ->
            val values = autofillStringValues(source)
            // 防空转：正则若因元素写法变化而一条不匹配，本用例就会「永远绿」而失去守卫意义
            assertTrue(
                "$label 侧至少应解析出 10 条 autofill* 文案（实际 ${values.size}，解析正则已失效）",
                values.size >= 10
            )
            // 白名单键必须存在（旧版通道文案本体不得被误删）
            assertTrue(
                "$label 侧旧版通道文案缺失（须含 $legacyKeys）",
                values.map { it.first }.toSet().containsAll(legacyKeys)
            )
            val offenders = values
                .filter { (name, value) -> name !in legacyKeys }
                .filter { (_, value) -> value.contains("无障碍") || value.contains("ccessibilit") }
                .map { it.first }
            assertTrue("$label 仍有框架通道的无障碍措辞：$offenders", offenders.isEmpty())
        }
    }

    /** 提取全部 `autofill*` 字符串资源的 name 与 value（限定在单个元素内，不跨行拼接） */
    private fun autofillStringValues(source: String): List<Pair<String, String>> =
        Regex("<string name=\"(autofill[^\"]*)\">([^<]*)</string>").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    private fun String.countInvocationsOf(token: String): Int {
        var count = 0
        var at = indexOf(token)
        while (at >= 0) {
            count++
            at = indexOf(token, at + token.length)
        }
        return count
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
