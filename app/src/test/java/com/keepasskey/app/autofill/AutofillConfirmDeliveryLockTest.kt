package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-95（审计 F-21）回归：**会话锁定后不得再回传认证结果**。
 *
 * 缺陷形态：`AutofillConfirmActivity.completeAuthResult()` 在生物识别 / 手动确认成功后
 * 无条件 `setResult(RESULT_OK)`，**不**校验会话是否已锁定（凭据管理器各路径均有该判定）。
 * 用户确认期间库被自动锁定 / 手动锁定后，框架仍会收到 RESULT_OK，把**已解密的数据集值写入
 * 目标表单**——形成「库已锁定但仍完成了一次填充」的语义漏洞（锁定本应立即终止一切下发）。
 *
 * 本用例分两层：
 * 1. **门控策略**（纯函数）——锁定即不允许回传；
 * 2. **接线守卫**（静态源码断言）——每一处 `setResult(RESULT_OK)` 之前**必须**出现门控判定，
 *    且存在显式的「丢弃未决响应」路径（`setResult(RESULT_CANCELED)`）。
 */
class AutofillConfirmDeliveryLockTest {

    private val activitySource: String by lazy { readSource(CONFIRM_ACTIVITY_PATH) }

    // ---------------- ① 门控策略 ----------------

    @Test
    fun `会话锁定时不允许回传认证结果`() {
        assertTrue("库未锁定：允许回传", AutofillAuthenticationPolicy.canDeliverAuthResult(vaultLocked = false))
        assertFalse("库已锁定：必须丢弃未决响应", AutofillAuthenticationPolicy.canDeliverAuthResult(vaultLocked = true))
    }

    // ---------------- ② 接线守卫 ----------------

    @Test
    fun `每一处回传 RESULT_OK 之前必须经过锁定门控`() {
        val marker = "setResult(RESULT_OK)"
        val indices = Regex(Regex.escape(marker)).findAll(activitySource).map { it.range.first }.toList()

        assertTrue("确认页必须存在回传路径（未找到 $marker）", indices.isNotEmpty())
        for (index in indices) {
            // 取回传点前的一段窗口：门控判定必须出现在其中（即回传不得无条件发生）
            val windowStart = (index - 400).coerceAtLeast(0)
            val window = activitySource.substring(windowStart, index)
            assertTrue(
                "回传 $marker 之前必须调用 AutofillAuthenticationPolicy.canDeliverAuthResult 门控" +
                    "（否则锁定后框架仍会写入凭据值）",
                window.contains("canDeliverAuthResult(")
            )
        }
    }

    @Test
    fun `确认页必须提供丢弃未决响应的显式路径`() {
        assertTrue(
            "必须显式以 RESULT_CANCELED 丢弃未决响应（而非仅 finish）",
            activitySource.contains("setResult(RESULT_CANCELED)")
        )
        assertTrue(
            "必须有集中收口的丢弃入口（discardPendingResult）",
            activitySource.contains("fun discardPendingResult()")
        )
    }

    @Test
    fun `锁定门控必须覆盖进入与回传两个时点`() {
        val gateUsages = Regex("AutofillAuthenticationPolicy\\.canDeliverAuthResult\\(")
            .findAll(activitySource).count()
        assertTrue(
            "门控必须同时覆盖「进入确认流程」与「回传前」两个时点（实际 $gateUsages 处）——" +
                "否则 TOTP 二次动作（含超时等待）期间发生的锁定会漏判",
            gateUsages >= 2
        )
        assertTrue(
            "库锁定时不得写入「上次填充条目」记忆（副作用早于门控即会误记录）",
            activitySource.indexOf("autofillLastFilledStore.record(") >
                activitySource.indexOf("AutofillAuthenticationPolicy.canDeliverAuthResult(")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CONFIRM_ACTIVITY_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt"
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
