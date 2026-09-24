package com.keepasskey.app.security

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 剪贴板「四类时机不受自动擦除开关约束」的**行为契约守卫**（**ISSUE-P3-293** AC③）。
 *
 * 背景：`autoClearClipboard == false` 只否决**定时**清除（`armScheduledClear` 走
 * `ClipboardClearPolicy.resolveScheduledTimeoutSeconds` 的纯函数裁决），而下列四条路径
 * **独立生效**、不查该设置：
 * ① 熄屏广播（`ACTION_SCREEN_OFF`）；② 应用切到后台（`ProcessLifecycleOwner` ON_STOP）；
 * ③ 会话锁定（`onSessionLocked`，由 `DatabaseSession` 经 `SessionLockObserver` 回调）；
 * ④ 冷启动对账（`reconcileOnColdStart`）。
 * 该行为**已登记**于 `resolved/batches/48-…`：「切后台即清与『自动擦除开关』独立生效；
 * 关闭自动擦除者亦受此保护」——但整改前**用户可见文案**（`sec_clipboard_risk_notice`）
 * 却承诺「一直留在系统剪贴板……直至被下次复制覆盖或设备重启」，与行为相悖。
 *
 * 本用例锁定两件事：
 * 1. **行为侧（静态判据）**：四条路径的实现体内**不得**出现 `autoClearClipboard` ——
 *    一旦有人「以文案为准」把开关接进这四条路径，本用例即红（防反向放宽安全性）。
 * 2. **文案侧**：`sec_clipboard_risk_notice` 中英两条必须点明四类时机与冷启动的误清面，
 *    且**不得**再出现「直至被下次复制覆盖或设备重启 / until overwritten or the device reboots」
 *    这类与实现相悖的承诺。
 *
 * **为何不用行为级用例**：宿主 JVM 单测**无法**构造 `ClipboardManager`（既有
 * `ClipboardSecurityManagerScheduledClearTest` 的 KDoc 已就此如实声明），故沿用本仓
 * 「接线守卫」的静态判据口径（同 `AutofillChannelSwitchWiringTest` / `CloudSyncSwitchWiringTest`）。
 */
class ClipboardClearTimingWiringTest {

    @Test
    fun `四类时机不查自动擦除开关`() {
        val code = stripCommentsOnly(readSource(MANAGER))

        listOf(
            "onSessionLocked" to "fun onSessionLocked()",
            "熄屏广播" to "override fun onReceive(",
            "切后台观察者" to "override fun onStop(",
            "冷启动对账" to "fun reconcileOnColdStart()"
        ).forEach { (label, signature) ->
            val body = functionBody(code, signature)
            assertTrue(
                "$label 必须确实清理剪贴板（否则该时机已失效）",
                body.contains("clearPendingSensitive()") || body.contains("clearClipboard()")
            )
            assertFalse(
                "$label 不得接入 autoClearClipboard——该开关只否决定时清除（ISSUE-P3-293 回归锁）",
                body.contains("autoClearClipboard")
            )
        }

        // 反空转正控制：同一次扫描必须能在**定时**路径里看到该设置（证明扫描器确实能看见它）
        val arm = functionBody(code, "private fun armScheduledClear(")
        assertTrue(
            "正控制失守：定时清除路径未读到 autoClearClipboard——扫描器可能已失效",
            arm.contains("autoClearClipboard")
        )
    }

    @Test
    fun `风险提示文案如实写明四类时机且不再承诺留在剪贴板`() {
        val zh = stringValue(readSource(ZH_STRINGS), "sec_clipboard_risk_notice")
        val en = stringValue(readSource(EN_STRINGS), "sec_clipboard_risk_notice")

        assertTrue("中文文案未取到值（解析失败）", zh.isNotBlank())
        assertTrue("英文文案未取到值（解析失败）", en.isNotBlank())

        // 四类时机必须逐一点明
        listOf("后台", "熄屏", "锁定", "冷启动").forEach { timing ->
            assertTrue("中文文案缺时机「$timing」的说明", zh.contains(timing))
        }
        listOf("leave the app", "screen-off", "locked", "cold-start").forEach { timing ->
            assertTrue("英文文案缺时机「$timing」的说明", en.contains(timing))
        }

        // 冷启动的误清面须如实声明（PD-41）
        assertTrue("中文文案须声明冷启动可能清除其它应用内容", zh.contains("其它应用"))
        assertTrue("英文文案须声明可能清除其它应用内容", en.contains("other apps"))

        // 不得再出现与实现相悖的承诺
        assertFalse("中文不得再承诺「直至被下次复制覆盖或设备重启」", zh.contains("设备重启"))
        assertFalse("中文不得再承诺「一直留在」", zh.contains("一直留在"))
        assertFalse(
            "英文不得再承诺 until overwritten or the device reboots",
            en.contains("device reboots", ignoreCase = true)
        )
    }

    // ========== helpers ==========

    private fun stringValue(xml: String, name: String): String {
        val marker = "<string name=\"$name\">"
        val start = xml.indexOf(marker)
        assertTrue("未找到字符串资源：$name", start >= 0)
        val bodyStart = start + marker.length
        val end = xml.indexOf("</string>", bodyStart)
        assertTrue("字符串资源未闭合：$name", end >= 0)
        return xml.substring(bodyStart, end)
    }

    /** 按花括号配平提取函数体（调用前须先剔除注释；四处签名均带花括号块） */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到签名：$signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("签名缺少代码块：$signature", open >= 0)
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

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val MANAGER =
            "app/src/main/java/com/keepasskey/app/security/ClipboardSecurityManager.kt"
        const val ZH_STRINGS = "app/src/main/res/values/strings.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"

        const val ROOT_SEARCH_DEPTH = 6

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
