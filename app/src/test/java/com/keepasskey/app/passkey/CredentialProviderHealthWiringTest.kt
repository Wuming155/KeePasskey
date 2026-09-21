package com.keepasskey.app.passkey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P2-239` 的**接线守卫**（源码文本断言），与 [CredentialProviderRegistrationTest]
 * （判定穷举）配对。
 *
 * 本项要防的失效形态是「检测做了但链条断在某一处」：
 * ① 平台查询被复制到多处 ⇒ 两处口径漂移；② 判定结果无人消费 ⇒ 探了白探（回归静默失效）；
 * ③ 健康卡的异常项没有文案键 ⇒ 渲染期缺字；④ 修复入口用臆测 action 或死链；
 * ⑤ 文案声称「已从系统移除」⇒ 与真实语义相反（组件注册由 Manifest 决定，应用内无法动态摘除）。
 */
class CredentialProviderHealthWiringTest {

    private val probeSource: String
        get() = readRepoFile(PROBE)

    private val autofillProbeSource: String
        get() = readRepoFile(AUTOFILL_PROBE)

    private val healthCardSource: String
        get() = readRepoFile(HEALTH_CARD)

    private val navSource: String
        get() = readRepoFile(SYSTEM_SETTINGS_NAV)

    // ---------- AC③：平台查询单点化 ----------

    @Test
    fun `凭据提供者启用查询只有一处`() {
        assertTrue(
            "[$PROBE] 未调用公开 API isEnabledCredentialProviderService（探针退化为常真）",
            probeSource.contains("isEnabledCredentialProviderService(")
        )
        assertTrue(
            "[$PROBE] 本应用组件名须由组件类推导（applicationId 与源码命名空间不一致，硬编码即判错）",
            probeSource.contains("ComponentName(context, KeePasskeyCredentialProviderService::class.java)")
        )
        assertTrue(
            "[$PROBE] 读取失败必须收敛为 UNREADABLE（AC②：不得当正常）",
            probeSource.contains("CredentialProviderEnabledState.UNREADABLE")
        )
        assertFalse(
            "[$AUTOFILL_PROBE] 自动填充探针自行做凭据提供者查询 —— 平台查询必须单点化在 $PROBE（两处口径必然漂移）",
            autofillProbeSource.contains("isEnabledCredentialProviderService(")
        )
        assertTrue(
            "[$AUTOFILL_PROBE] 未注入凭据提供者探针（ISSUE-P2-239 的读数来源）",
            autofillProbeSource.contains("CredentialProviderHealthProbe")
        )
    }

    /**
     * 自省：全仓不得再出现对**内部键** `credential_service` 的读取。
     *
     * 该键（`Settings.Secure` 内部键，AOSP 无公开常量）是创建请求路由的权威，但**普通应用不可读**
     * ——初版判定据它实现，真机上抛 `SecurityException`（2026-09-21，Redmi 4X / API 37），
     * 判定因此永远停在「未知」。守卫在此防止有人「凭记忆」把它改回去：改了即红，
     * 并迫使改回者先读 §247 的取舍说明。
     */
    @Test
    fun `不得回到读取内部键 credential_service`() {
        // 只认**带引号的字面量**（即真的把它当键名去读）；KDoc 里的说明性提及（不带引号）不算
        val quotedKey = "\"credential_service\""
        val hits = kotlinFilesUnder(MAIN_SOURCES)
            .filter { it.isFile }
            .filter { it.readText().contains(quotedKey) }
            .map { it.name }
            .sorted()

        assertTrue(
            "出现对内部键 credential_service 的读取：$hits —— 该键对普通应用不可读" +
                "（真机 SecurityException），判定会永远停在「未知」；公开替代见 $PROBE",
            hits.isEmpty()
        )
    }

    // ---------- AC①：异常项必须有人消费且能被修复 ----------

    @Test
    fun `判定结果被健康报告消费`() {
        val policySource = readRepoFile(HEALTH_POLICY)
        listOf(
            "CredentialProviderRegistration.NOT_REGISTERED" to "CREDENTIAL_PROVIDER_NOT_REGISTERED",
            "CredentialProviderRegistration.UNKNOWN" to "CREDENTIAL_PROVIDER_STATE_UNKNOWN"
        ).forEach { (registrationBranch, issue) ->
            assertTrue(
                "[$HEALTH_POLICY] 未消费 $registrationBranch（探测结果无人读即为「探了白探」）",
                policySource.contains(registrationBranch)
            )
            assertTrue("[$HEALTH_POLICY] 缺异常项 $issue", policySource.contains(issue))
        }
    }

    @Test
    fun `健康卡为两个新异常项各配文案与系统设置入口`() {
        assertTrue(
            "[$HEALTH_CARD] 未为「系统未登记本应用」接线文案",
            healthCardSource.contains("R.string.autofill_health_issue_cp_not_registered")
        )
        assertTrue(
            "[$HEALTH_CARD] 未为「登记状态未知」接线文案（AC②：读不到也要如实呈现）",
            healthCardSource.contains("R.string.autofill_health_issue_cp_state_unknown")
        )
        assertTrue(
            "[$HEALTH_CARD] 两态均须给出系统设置入口（修复 / 自行核对）",
            healthCardSource.contains("SystemSettingsNavigation.credentialProviderIntent(context)")
        )
        assertTrue(
            "[$NAV] 凭据提供程序设置页必须用公开 action android.settings.CREDENTIAL_PROVIDER",
            navSource.contains("Settings.ACTION_CREDENTIAL_PROVIDER")
        )
        assertTrue(
            "[$NAV] 不可解析时必须返回 null（保留纯文案，绝不出现死链）",
            navSource.contains("resolveActivity(context.packageManager) != null")
        )
    }

    // ---------- AC④：文案不得声称「已从系统移除」 ----------

    @Test
    fun `文案不得声称已从系统移除`() {
        val zh = readRepoFile(ZH_STRINGS)
        val en = readRepoFile(EN_STRINGS)

        listOf(
            "autofill_health_issue_cp_not_registered",
            "autofill_health_issue_cp_state_unknown"
        ).forEach { key ->
            val zhValue = stringValue(zh, key)
            val enValue = stringValue(en, key)
            listOf("已从系统移除", "已移除该服务", "removed from the system", "uninstalled").forEach { banned ->
                assertFalse("[$key] 文案不得声称「$banned」：$zhValue", zhValue.contains(banned))
                assertFalse("[$key] 文案不得声称「$banned」：$enValue", enValue.contains(banned))
            }
            assertTrue("[$key] 中文文案须指向系统设置路径：$zhValue", zhValue.contains("凭据提供程序"))
            assertTrue(
                "[$key] 英文文案须指向同一路径：$enValue",
                enValue.contains("credential providers")
            )
        }
        // 「未登记」须写明用户可感知的后果（点保存没反应），而不是只说状态
        assertTrue(
            "「未登记」文案须写明后果（否则用户无从把状态与现象对上）",
            stringValue(zh, "autofill_health_issue_cp_not_registered").contains("没有反应")
        )
        assertTrue(
            "「未知」文案须如实声明读不到，且不得写成「已启用 / 正常」",
            stringValue(zh, "autofill_health_issue_cp_state_unknown").contains("状态未知")
        )
    }

    private fun stringValue(source: String, name: String): String {
        val match = Regex("<string name=\"$name\">([^<]*)</string>").find(source)
        assertTrue("字符串资源 $name 不存在（是否被改名/删除）", match != null)
        return match!!.groupValues[1]
    }

    private fun readRepoFile(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val candidate = dir ?: return@repeat
            val target = File(candidate, relativePath)
            if (target.isFile) return target.readText()
            dir = candidate.parentFile
        }
        error("无法定位仓库文件：$relativePath（起始：${System.getProperty("user.dir")}）")
    }

    /** 收集 `app/src/main/java` 下的 Kotlin 源文件（本仓 app 模块为单一源根） */
    private fun kotlinFilesUnder(relativeRoot: String): List<File> =
        File(readRepoRoot(), relativeRoot).walkTopDown().filter { it.extension == "kt" }.toList()

    private fun readRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val candidate = dir ?: return@repeat
            if (File(candidate, "app/src/main/java").isDirectory) return candidate
            dir = candidate.parentFile
        }
        error("未能定位仓库根（自 ${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 4

        const val MAIN_SOURCES = "app/src/main/java"
        const val PROBE = "app/src/main/java/com/keepasskey/app/passkey/CredentialProviderHealthProbe.kt"
        const val AUTOFILL_PROBE = "app/src/main/java/com/keepasskey/app/autofill/AutofillHealthProbe.kt"
        const val HEALTH_POLICY = "app/src/main/java/com/keepasskey/app/autofill/AutofillHealthPolicy.kt"
        const val HEALTH_CARD =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillHealthCard.kt"
        const val SYSTEM_SETTINGS_NAV =
            "app/src/main/java/com/keepasskey/app/ui/components/SystemSettingsNavigation.kt"

        /** 断言消息内用的短名（同一路径，避免消息里出现长路径把可读性压掉） */
        const val NAV = SYSTEM_SETTINGS_NAV
        const val ZH_STRINGS = "app/src/main/res/values/strings.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"
    }
}
