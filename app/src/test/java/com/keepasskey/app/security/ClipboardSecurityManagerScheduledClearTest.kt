package com.keepasskey.app.security

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-144 回归：`armScheduledClear` 的**分支顺序**使 `customTimeoutSeconds`
 * 无法强制擦除——**锁定现状与 KDoc 声明一致**。
 *
 * ## 缺陷背景（为何需要本用例）
 *
 * 原实现 `:181` 的 `if (!settings.autoClearClipboard) return@launch` **早于** `:183` 对
 * `customTimeoutSeconds` 的消费 ⇒ 用户关闭「自动擦除剪贴板」时，显式传入的自定义秒数被
 * **静默否决**；而入口 KDoc 只写「若为 null 则遵从 UserSettings」，**未声明**该否决语义。
 * 全仓无任何生产调用方传入非空 `customTimeoutSeconds`，故**当前零行为影响**；但
 * `ISSUE-P2-43` AC① 的「强制不可关闭短擦除」备选若照此实现，会变成**假加固**。
 *
 * ## 断言分层（两层缺一不可）
 *
 * 1. **行为层**（[ClipboardClearPolicy.resolveScheduledTimeoutSeconds] 纯函数）：
 *    `autoClearClipboard=false` + 非空自定义秒数 ⇒ `null`（**不调度清除**）；反向
 *    `autoClearClipboard=true` + 非空值 ⇒ **按自定义秒数**调度。该裁决刻意保持为**纯函数**
 *    （不触碰 `Context` / `ClipboardManager`），因为宿主 JVM 单测**无法**构造
 *    `ClipboardSecurityManager`——`Context.getSystemService` 在 android.jar 桩中抛
 *    `RuntimeException("Stub!")`，且本仓不引入 Robolectric / mock 框架
 *    （体例见 `ClipboardClearPolicyTest`、`BiometricCredentialStorageTest` 的同类说明）。
 * 2. **接线层**（源码守卫，两处）：顺序契约的**实现处**是策略函数本身
 *    （`resolveScheduledTimeoutSeconds` 内 `autoClearClipboard` 必须早于
 *    `customTimeoutSeconds`）；生产 `armScheduledClear` 必须**经**该函数裁决、
 *    不得自行内联替换（如直接 `scheduleClear(customTimeoutSeconds ?: …)`）——
 *    纯函数本身正确但被绕过的改法只有这一层能拦截。
 *
 * ⚠ **本用例锁定的是「现状 + 声明」而非期望的产品行为**：若未来采纳「强制擦除」，
 * 必须先按 `ClipboardSecurityManager` KDoc 所述改为「敏感路径无条件调度、用户设置作
 * **上限**而非**开关**」，并**同时**更新本用例——不得只改实现、让用例变红后删除断言。
 */
class ClipboardSecurityManagerScheduledClearTest {

    // ---------------------------------------------------------------------------------------
    // 行为层：ClipboardClearPolicy.resolveScheduledTimeoutSeconds
    // ---------------------------------------------------------------------------------------

    @Test
    fun `autoClearClipboard false 时非空 customTimeoutSeconds 被否决 不调度清除`() {
        val resolved = ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
            customTimeoutSeconds = CUSTOM_SECONDS,
            autoClearClipboard = false,
            settingsTimeoutSeconds = SETTINGS_SECONDS
        )

        assertNull(
            "用户关闭「自动擦除剪贴板」时，显式 customTimeoutSeconds 同样不生效" +
                "（ISSUE-P3-144 否决语义）——返回非 null 即等于「强制擦除」已生效，属未声明的行为变更",
            resolved
        )
    }

    @Test
    fun `autoClearClipboard false 时各种非空自定义秒数一律被否决 不调度清除`() {
        // 覆盖「极短 / 默认 / 长 / 负」四类显式取值，确认否决与秒数大小无关
        listOf(1, 30, 3600, -1).forEach { custom ->
            assertNull(
                "autoClearClipboard=false 下 customTimeoutSeconds=$custom 仍不得调度清除",
                ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
                    customTimeoutSeconds = custom,
                    autoClearClipboard = false,
                    settingsTimeoutSeconds = SETTINGS_SECONDS
                )
            )
        }
    }

    @Test
    fun `autoClearClipboard true 时非空 customTimeoutSeconds 按自定义秒数调度`() {
        assertEquals(
            "用户开启自动擦除时，显式自定义秒数必须**优先于**用户默认秒数生效",
            CUSTOM_SECONDS,
            ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
                customTimeoutSeconds = CUSTOM_SECONDS,
                autoClearClipboard = true,
                settingsTimeoutSeconds = SETTINGS_SECONDS
            )
        )
    }

    @Test
    fun `customTimeoutSeconds 为 null 时遵从 UserSettings 默认秒数`() {
        assertEquals(
            "null 表示「遵从 UserSettings」——既有语义，不得因本次整改改变",
            SETTINGS_SECONDS,
            ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
                customTimeoutSeconds = null,
                autoClearClipboard = true,
                settingsTimeoutSeconds = SETTINGS_SECONDS
            )
        )
    }

    @Test
    fun `解析结果小于等于 0 时不调度清除`() {
        listOf(0, -1).forEach { nonPositive ->
            assertNull(
                "解析出的秒数 $nonPositive 表示不清空（既有口径），不得调度",
                ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
                    customTimeoutSeconds = nonPositive,
                    autoClearClipboard = true,
                    settingsTimeoutSeconds = SETTINGS_SECONDS
                )
            )
        }
    }

    @Test
    fun `用户开关为假时优先于秒数裁决 正数自定义秒数亦不放行`() {
        // 顺序敏感的等价表述：把两个条件都设为「本可触发调度」的取值，结果必须是「不调度」。
        // 若实现把用户开关判在秒数裁决之后（即 :183 先于 :181），本断言即变红。
        assertNull(
            "两个分支同时命中时，用户开关必须**优先**（否决语义），而不是被正数秒数覆盖",
            ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
                customTimeoutSeconds = 1,
                autoClearClipboard = false,
                settingsTimeoutSeconds = 1
            )
        )
    }

    // ---------------------------------------------------------------------------------------
    // 接线层（源码守卫，均剔除注释后判定）：
    //   ① 顺序契约的**实现处**是策略函数本身——「先查用户开关、后取自定义秒数」必须逐字保持；
    //   ② 生产 armScheduledClear 必须**经**该函数裁决，不得自行内联替换（否则行为层用例
    //      与生产路径脱钩，形成「用例绿、行为仍旧」的假闭环）。
    // ---------------------------------------------------------------------------------------

    @Test
    fun `策略函数先查用户开关后取自定义秒数`() {
        val body = functionBody(stripComments(readManagerSource()), RESOLVER_SIGNATURE)
        // 只锁定**可执行语句**的先后（去掉缩进便于字面匹配），不锁定形参声明顺序——
        // 形参重排不改变语义，不应让守卫误报。
        val code = body.lines().joinToString("\n") { it.trim() }

        // 逐条给出命中下标，失败消息直接附带提取体原文：needle 与实际代码字面不一致
        // （下标 -1）与「提取范围过大（含文件尾）」两种成因可一次区分，无需反复试改常量。
        assertTrue(
            "[$MANAGER_SOURCE] ClipboardClearPolicy.resolveScheduledTimeoutSeconds 未读取 autoClearClipboard——" +
                "用户「关闭自动擦除」的设置被绕过，属产品行为变更（须先另行裁决）。\n" +
                "needle（下标 ${code.indexOf(AUTO_CLEAR_VETO_STATEMENT)}）：$AUTO_CLEAR_VETO_STATEMENT\n" +
                "提取到的函数体：\n$code",
            code.contains(AUTO_CLEAR_VETO_STATEMENT)
        )
        assertTrue(
            "[$MANAGER_SOURCE] ClipboardClearPolicy.resolveScheduledTimeoutSeconds 未消费 customTimeoutSeconds——" +
                "ISSUE-P2-43 AC①「强制短擦除」备选会因此变成假加固，须同步更新 KDoc 与本用例。\n" +
                "needle（下标 ${code.indexOf(CUSTOM_TIMEOUT_CONSUME_STATEMENT)}）：$CUSTOM_TIMEOUT_CONSUME_STATEMENT\n" +
                "提取到的函数体：\n$code",
            code.contains(CUSTOM_TIMEOUT_CONSUME_STATEMENT)
        )
        assertTrue(
            "[$MANAGER_SOURCE] 判断顺序被调换：`$AUTO_CLEAR_VETO_STATEMENT` 必须出现在 " +
                "`$CUSTOM_TIMEOUT_CONSUME_STATEMENT` **之前**。ISSUE-P3-144 的「否决语义」即由该顺序产生——" +
                "调换后用户关闭自动擦除时，显式非空自定义秒数仍会照常调度清除。\n" +
                "实际下标：veto=${code.indexOf(AUTO_CLEAR_VETO_STATEMENT)}, " +
                "consume=${code.indexOf(CUSTOM_TIMEOUT_CONSUME_STATEMENT)}\n" +
                "提取到的函数体：\n$code",
            code.indexOf(AUTO_CLEAR_VETO_STATEMENT) < code.indexOf(CUSTOM_TIMEOUT_CONSUME_STATEMENT)
        )
    }

    @Test
    fun `armScheduledClear 经策略裁决而不内联替换`() {
        val body = functionBody(stripComments(readManagerSource()), ARM_SIGNATURE)

        assertTrue(
            "[$MANAGER_SOURCE] armScheduledClear 必须经 $RESOLVER_CALL 裁决，不得自行内联替换——" +
                "否则本类行为层用例与生产路径脱钩（用例绿、行为仍旧）",
            body.contains(RESOLVER_CALL)
        )
        assertTrue(
            "[$MANAGER_SOURCE] armScheduledClear 的清除计划必须直接取自策略裁决结果——" +
                "形如 `scheduleClear(customTimeoutSeconds ?: …)` 的简化会在用户关闭自动擦除时仍然调度清除" +
                "（即 ISSUE-P3-144 的否决语义失效）",
            body.contains(SCHEDULE_FROM_RESOLVED)
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readManagerSource(): String {
        val file = File(repositoryRoot, MANAGER_SOURCE)
        assertTrue("文件不存在（是否被重命名/移动）：$MANAGER_SOURCE", file.isFile)
        return file.readText()
    }

    /**
     * 按花括号配对提取函数体（含函数体本身）。
     * 调用前须剔除注释，否则注释中的 `{` / `}` 会破坏配对。
     */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到函数：$signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("函数缺少函数体：$signature", open >= 0)

        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("函数体未闭合：$signature")
    }

    /** 剔除块注释与行注释——整改说明本身会写出被断言的字面量（体例同 `ClipboardSensitiveMarkConsistencyTest`） */
    private fun stripComments(source: String): String = stripCommentsOnly(source)
    private companion object {
        const val MANAGER_SOURCE =
            "app/src/main/java/com/keepasskey/app/security/ClipboardSecurityManager.kt"

        /** `armScheduledClear` 的签名片段（源码守卫用；剔除注释后仍唯一） */
        const val ARM_SIGNATURE = "private fun armScheduledClear("

        /** 顺序契约实现处的签名片段（策略函数体；剔除注释后仍唯一） */
        const val RESOLVER_SIGNATURE = "fun resolveScheduledTimeoutSeconds("

        /** 策略函数首条可执行语句：用户开关的**提前返回**（去掉缩进后的字面量） */
        const val AUTO_CLEAR_VETO_STATEMENT = "if (!autoClearClipboard) return null"

        /** 策略函数中对自定义秒数的**消费**语句（去掉缩进后的字面量） */
        const val CUSTOM_TIMEOUT_CONSUME_STATEMENT =
            "val timeoutSec = customTimeoutSeconds ?: settingsTimeoutSeconds"

        /** 策略解析函数的调用点（源码守卫用） */
        const val RESOLVER_CALL = "ClipboardClearPolicy.resolveScheduledTimeoutSeconds"

        /** 清除计划必须取自裁决结果（elvis 兜底为 `return@launch`，而非直接调度） */
        const val SCHEDULE_FROM_RESOLVED = "scheduleClear(timeoutSec, hash)"

        const val CUSTOM_SECONDS = 7
        const val SETTINGS_SECONDS = 30


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
