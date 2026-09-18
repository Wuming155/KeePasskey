package com.keepasskey.app.ui

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「TOTP 周期一律取条目自身值」的**接线守卫**（ISSUE-P3-158）。
 *
 * 断言对象是源码文本：三处 UI 的倒计时 / 进度环分母此前**写死 30 秒**，而这类
 * 「某个调用点是否传了周期」的结构性约束在宿主单测里**无法运行时覆盖**——
 * Compose 组件只能在设备侧渲染（`androidTest`），少传一个 `totalSeconds` 不会有任何行为用例变红，
 * 只会让 `period != 30` 的条目静默显示错误的相位与环比例。
 *
 * 因此此处按仓内既有做法（见 `AutofillAuthResultWiringTest`）以源码文本为断言对象，
 * 并在断言前**剥离注释**：本批的 KDoc / 行内注释为解释动机**刻意引用了**旧写法
 * （「此前写死 30」），若不剥离会误判为残留。
 *
 * 行为面（何时重算、周期内零重算、`period != 30` 不漏拍）由
 * `TotpCountdownTrackerTest` 以虚拟时钟锁定；本文件只锁「UI 接线是否取到条目自身周期」。
 */
class TotpPeriodWiringGuardTest {

    @Test
    fun `列表行徽标按条目自身周期换算剩余秒数并作进度环分母`() {
        val source = readSource(VAULT_ROW_LAYOUTS)
        assertTrue(
            "徽标必须接收条目自身周期（缺此参数会退回列表页的全局口径）",
            source.contains("periodSeconds = entry.totpPeriod")
        )
        assertTrue(
            "剩余秒数必须按条目周期换算（OtpEngine 是唯一换算入口）",
            source.contains("OtpEngine.getRemainingSeconds(")
        )
        assertTrue(
            "迷你进度环分母必须取条目周期，不得沿用缺省的 30 秒",
            source.contains("totalSeconds = period")
        )
        assertFalse(
            "不得残留预览常量 TOTP_PREVIEW_REMAINING_SECONDS（它是「全局剩余秒数」口径的残留）",
            stripped(source).contains("TOTP_PREVIEW_REMAINING_SECONDS")
        )
    }

    @Test
    fun `验证器页进度环分母取条目自身周期`() {
        val source = stripped(readSource(AUTHENTICATOR_SCREEN))
        assertFalse(
            "不得再把进度环分母写死为 30（period != 30 时环比例错误）",
            source.contains("/ 30f")
        )
        assertTrue(
            "进度环分母必须取条目自身周期",
            source.contains("item.periodSeconds")
        )
    }

    @Test
    fun `条目详情页进度环分母取条目自身周期`() {
        val source = readSource(ENTRY_DETAIL_CARDS)
        assertTrue(
            "详情页迷你进度环必须显式传入条目周期（否则沿用缺省的 30 秒）",
            source.contains("totalSeconds = entry.totpPeriod")
        )
    }

    @Test
    fun `列表节拍不得再以全局 30 秒网格计算倒计时`() {
        val source = stripped(readSource(TOTP_COUNTDOWN_TRACKER))
        assertFalse(
            "不得再出现「按全局 30 秒取余」的倒计时计算（缺陷形态）",
            source.contains("% TOTP_PERIOD_SECONDS")
        )
        assertTrue(
            "跨周期判据必须按条目自身周期（去重周期集合）判定",
            source.contains("entryPeriods()") && source.contains("it.totpPeriod")
        )
    }

    /** 剥离块注释与行注释：本仓注释会为解释动机而引用旧写法，直接断言全文会误判 */
    private fun stripped(source: String): String = stripCommentsOnly(source)
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val VAULT_ROW_LAYOUTS =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultEntryRowLayouts.kt"
        const val TOTP_COUNTDOWN_TRACKER =
            "app/src/main/java/com/keepasskey/app/ui/model/TotpCountdownTracker.kt"
        const val AUTHENTICATOR_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/authenticator/AuthenticatorScreen.kt"
        const val ENTRY_DETAIL_CARDS =
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailCards.kt"

        /** 用例工作目录为 `app/`，故向上找到含 `settings.gradle.kts` 的仓库根 */
        val repositoryRoot: File by lazy {
            generateSequence(File(".").absoluteFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
        }
    }
}
