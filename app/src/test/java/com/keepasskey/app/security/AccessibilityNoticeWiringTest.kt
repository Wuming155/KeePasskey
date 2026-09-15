package com.keepasskey.app.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-44 接线守卫（静态源码比对，体例沿用
 * [com.keepasskey.app.ColdStartAttachmentPurgeWiringTest]）。
 *
 * 本项的三个失效形态都不是「判定算错」，而是**接线被静默移除**或**隐式耦合**：
 *
 * 1. 无障碍信号**采集**：`RuntimeIntegrityDetector` 必须真的枚举已启用的无障碍服务，
 *    而不是把 `thirdPartyAccessibilityEnabled` 恒留默认 false（「加了字段没人填」）；
 * 2. 提示**消费点**：主密码输入页必须真的按 `uiState.accessibilityRiskNotice` 渲染提示
 *    （「加了判定没人消费」——本仓 ISSUE-P2-41 的原形态）；
 * 3. 口令语义**显式声明**：`SecurePasswordField` 必须显式 `semantics { password() }`，
 *    不得只依赖框架从 `PasswordVisualTransformation` 隐式推导（ISSUE-P2-44 AC②）。
 *
 * 说明：本项**不**断言「无障碍信号导致通道降级」——恰恰相反，它只提示、不降级，
 * 该语义由 [RuntimeIntegrityPolicyTest] 的行为断言锁定。
 */
class AccessibilityNoticeWiringTest {

    private val detectorSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/security/RuntimeIntegrityDetector.kt")

    private val unlockScreenSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockScreen.kt")

    private val secureFieldSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/components/SecurePasswordField.kt")

    @Test
    fun `检测器真的枚举已启用无障碍服务`() {
        val source = detectorSource

        assertTrue(
            "必须用官方 API 枚举已启用无障碍服务（含系统预装），而非恒 false",
            source.contains("getEnabledAccessibilityServiceList(")
        )
        assertTrue(
            "判定口径：服务包名 ≠ 本应用包名（不得把本应用自身算作第三方）",
            source.contains("pkg != ctx.packageName")
        )
        assertTrue(
            "采集结果必须写入 IntegritySignals.thirdPartyAccessibilityEnabled（字段接线不得遗漏）",
            source.contains("thirdPartyAccessibilityEnabled = detectThirdPartyAccessibility(ctx)")
        )
    }

    @Test
    fun `主密码输入页消费提示位并渲染无障碍提示`() {
        val source = unlockScreenSource

        assertTrue(
            "解锁页必须按 uiState.accessibilityRiskNotice 渲染提示",
            source.contains("uiState.accessibilityRiskNotice")
        )
        assertTrue(
            "提示文案须取专用字符串资源（不得硬编码）",
            source.contains("R.string.unlock_accessibility_notice_title") &&
                source.contains("R.string.unlock_accessibility_notice_body")
        )
    }

    @Test
    fun `口令字段显式声明 password 语义`() {
        val source = secureFieldSource

        assertTrue(
            "SecurePasswordField 必须显式 semantics { password() }",
            source.contains("semantics { password() }")
        )
        assertTrue(
            "密码键盘类型不得回退为普通文本（框架据此下发 IME 语义）",
            source.contains("keyboardType = KeyboardType.Password")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
