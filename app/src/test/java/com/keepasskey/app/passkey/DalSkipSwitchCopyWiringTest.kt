package com.keepasskey.app.passkey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P2-240` 守护用例：锁定「**界面文案 ↔ 偏好字段 ↔ 生产消费方**」三者对应关系
 * （AC⑤），体例沿用 [com.keepasskey.app.ui.AutofillChannelSwitchWiringTest] /
 * [com.keepasskey.app.security.AccessibilityNoticeWiringTest]（静态源码比对）。
 *
 * ## 本项要防的失效形态
 * 整改前该行是「措辞指向另一件事」的典型：界面写「跳过浏览器兼容层 / 不通过浏览器兼容适配直接填充
 * 原生表单」，而它接线的是 `skipDalVerification` —— 通行密钥注册时对调用方执行的
 * `DigitalAssetLinksVerifier` 远程归属声明校验降级（「浏览器兼容层」在该版本既无实现也无偏好项）。
 * 直接后果是 `DigitalAssetLinksVerifier` KDoc 所指的降级入口「用户根本看不到这个名字」，
 * 而 DAL 正是「任意应用冒充任意域名注册通行密钥」的核心防线。
 *
 * 故本类的断言指向三种复现形态：
 * 1. **文案漂移**：资源取值必须如实描述「通行密钥站点归属校验」，且不得残留浏览器兼容层措辞；
 * 2. **链路断点**：文案资源 → 开关行 → 持久化字段 → 注册门控，四段必须逐段连得上
 *    （少一段即「改了文案但没人消费」或「字段无人读」）；
 * 3. **锚点分叉**：`DigitalAssetLinksVerifier` KDoc / `ExtendedSettings` 字段注释 /
 *    `PasskeyCreateActivity` 告警日志必须与中文标题**逐字同锚**，任一侧改写而未同步另一侧即红。
 *
 * 说明：本项**不**断言任何判定口径——门控放行 / 拒绝语义由 [CredentialRejectionFeedbackTest]
 * 的行为断言锁定，本类只管「文案与接线不得再各说一套」。
 */
class DalSkipSwitchCopyWiringTest {

    private val zhStringsSource: String
        get() = readSource("app/src/main/res/values/strings.xml")

    private val enStringsSource: String
        get() = readSource("app/src/main/res/values-en/strings.xml")

    private val componentsSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillSettingsComponents.kt"
        )

    private val screenSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillSettingsScreen.kt"
        )

    private val uiStateSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt")

    private val projectionSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        )

    private val extendedSettingsSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt")

    private val storeSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt")

    private val verifierSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/passkey/DigitalAssetLinksVerifier.kt")

    private val createActivitySource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/passkey/PasskeyCreateActivity.kt")

    private val gateSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/passkey/PasskeyRegistrationGate.kt")

    // ========== AC①③④：文案值本身 ==========

    @Test
    fun `中文文案如实描述通行密钥站点归属校验与其降级后果`() {
        val title = stringValue(zhStringsSource, "autofill_skip_dal_title")
        val sub = stringValue(zhStringsSource, "autofill_skip_dal_sub")

        assertTrue("标题须点名真实控制对象（通行密钥 + 站点归属）：$title", title.contains("通行密钥"))
        assertTrue("标题须点名被降级的是「归属校验」：$title", title.contains("归属"))
        assertFalse("标题不得残留「浏览器兼容层」措辞：$title", title.contains("浏览器"))
        assertFalse("副标题不得再描述「原生表单填充」：$sub", sub.contains("原生表单"))
        assertFalse("副标题不得再描述浏览器兼容适配：$sub", sub.contains("兼容"))
        assertTrue("副标题须写明降级后果（开启后不再校验归属）：$sub", sub.contains("不再校验"))
        assertTrue("副标题须写明纵深后果（任意域名）：$sub", sub.contains("任意域名"))
    }

    @Test
    fun `英文文案与中文同步描述同一语义`() {
        val title = stringValue(enStringsSource, "autofill_skip_dal_title").lowercase()
        val sub = stringValue(enStringsSource, "autofill_skip_dal_sub").lowercase()

        assertTrue("英文标题须点名 passkey 与归属：$title", title.contains("passkey"))
        assertTrue("英文标题须点名 ownership：$title", title.contains("ownership"))
        assertFalse("英文标题不得残留浏览器兼容层措辞：$title", title.contains("browser"))
        assertFalse("英文副标题不得残留浏览器兼容层措辞：$sub", sub.contains("browser"))
        assertFalse("英文副标题不得再描述 native form 填充：$sub", sub.contains("native form"))
        // 降级后果两侧同口径：任何应用、任意域名
        assertTrue("英文副标题须写明校验被跳过：$sub", sub.contains("skipped"))
        assertTrue("英文副标题须写明「任何应用」：$sub", sub.contains("any app"))
        assertTrue("英文副标题须写明「任意域名」：$sub", sub.contains("any domain"))
    }

    // ========== AC⑤ 之一：偏好字段 ↔ 界面开关 ↔ 持久化 ==========

    @Test
    fun `开关行取值取自状态快照且使用本条专用文案资源`() {
        assertTrue(
            "开关标题须取专用资源，不得硬编码：autofill_skip_dal_title",
            componentsSource.contains("R.string.autofill_skip_dal_title")
        )
        assertTrue(
            "开关副标题须取专用资源：autofill_skip_dal_sub",
            componentsSource.contains("R.string.autofill_skip_dal_sub")
        )
        assertTrue(
            "勾选态必须绑定 skipDalVerification（否则是假开关）",
            componentsSource.contains("checked = uiState.skipDalVerification")
        )
        assertTrue(
            "变更必须上行到 onSkipDalVerificationToggle",
            componentsSource.contains("onCheckedChange = onSkipDalVerificationToggle")
        )
        assertTrue("二级页装配须透传该回调", screenSource.contains("onSkipDalVerificationToggle"))
    }

    @Test
    fun `字段经持久化层闭环且 UI 取持久化快照`() {
        assertTrue(
            "UI 快照须来自扩展偏好投影（不得回退为内存回显）",
            projectionSource.contains("skipDalVerification = extState.skipDalVerification")
        )
        assertTrue(
            "状态模型须保留该字段",
            uiStateSource.contains("val skipDalVerification: Boolean = false")
        )
        assertTrue(
            "缺读侧：持久化键未接回 load()",
            storeSource.contains("K_SKIP_DAL_VERIFICATION, defaults.skipDalVerification")
        )
        assertTrue(
            "缺写侧：开关不会落盘",
            storeSource.contains(".putBoolean(K_SKIP_DAL_VERIFICATION")
        )
    }

    // ========== AC⑤ 之二：偏好字段 ↔ 生产消费方 ==========

    @Test
    fun `注册门控是唯一生产消费方且真求值`() {
        assertTrue(
            "创建活动须从持久化偏好读取（ISSUE-P2-02 接线点）",
            createActivitySource.contains("extendedSettingsStore.load().skipDalVerification")
        )
        assertTrue(
            "读到的值须传入门控，不得只读不用",
            createActivitySource.contains("skipDalVerification = skipDalVerification,")
        )
        assertTrue(
            "门控须据此放行（用户显式取舍）",
            gateSource.contains("if (skipDalVerification) return null")
        )
    }

    // ========== AC①：KDoc / 字段注释 / 日志与标题逐字同锚 ==========

    @Test
    fun `KDoc 与字段注释及告警日志同锚于中文标题`() {
        val title = stringValue(zhStringsSource, "autofill_skip_dal_title")
        // 防空转：标题若被改成空串或极短串，本用例就会「永远绿」而失去锚定意义
        assertTrue("标题取值异常（疑似解析失败）：$title", title.length >= 6)

        listOf(
            "DigitalAssetLinksVerifier KDoc" to verifierSource,
            "ExtendedSettings 字段注释" to extendedSettingsSource,
            "PasskeyCreateActivity 告警日志" to createActivitySource,
            "PasskeyRegistrationGate 判定说明" to gateSource
        ).forEach { (label, source) ->
            assertTrue(
                "$label 未与界面标题同锚（两处各说一套即为本项缺陷形态）：$title",
                source.contains(title)
            )
        }
    }

    /** AC② 裁决：不新增与 `skipDalVerification` 并列的「跳过浏览器兼容层」偏好项（无实现即假开关） */
    @Test
    fun `不存在独立的浏览器兼容层偏好项`() {
        listOf(
            "ExtendedSettings" to extendedSettingsSource,
            "ExtendedSettingsStore" to storeSource,
            "SettingsUiState" to uiStateSource,
            "投影" to projectionSource
        ).forEach { (label, source) ->
            listOf("browserCompat", "BROWSER_COMPAT", "skipBrowser", "skip_browser").forEach { token ->
                assertFalse(
                    "$label 出现了浏览器兼容层偏好项 $token —— 该行为在本应用无实现、无消费方，" +
                        "新增即`ISSUE-P2-228` 同类的假开关（AC② 裁决：不得与 skipDalVerification 共用字段）",
                    source.contains(token)
                )
            }
        }
    }

    /** 取 `<string name="…">值</string>` 的值；缺失即断言失败（避免键改名后本类静默失效） */
    private fun stringValue(source: String, name: String): String {
        val match = Regex("<string name=\"$name\">([^<]*)</string>").find(source)
        assertTrue("字符串资源 $name 不存在（是否被改名/删除）", match != null)
        return match!!.groupValues[1]
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
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
