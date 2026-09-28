package com.keepasskey.app.autofill.legacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主动填充提示的接线与安全面守卫（ISSUE-P3-374 AC①③④⑤，静态源码比对）。
 *
 * 触发面与安全闸门（AC① 触发面文档见批次 §354.2）落在既有 legacy 通道服务上——
 * 行为本批已存在或本批升级（节流 / 通道重要度），守卫负责把「三态不发、点击只进
 * 受保护选择器、通知零敏感插值、通道低调」四条锁死，防后续改动静默回退。
 */
class LegacyActivePromptWiringTest {

    private fun readSource(path: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(6) {
            val candidate = dir ?: return@repeat
            val file = File(candidate, path)
            if (file.isFile) return file.readText()
            dir = candidate.parentFile
        }
        error("源码缺失：$path")
    }

    private val serviceSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/autofill/legacy/LegacyAutofillAccessibilityService.kt")

    // ===== AC②：节流升级接线 =====

    @Test
    fun `提示必须经按包节流且旧去抖已移除`() {
        assertTrue(
            "postOfferNotification 必须先过 AutofillActivePromptThrottle",
            serviceSource.contains("promptThrottle.shouldPrompt(targetPkg)")
        )
        assertFalse(
            "旧的全局 2 秒去抖不得回潮",
            serviceSource.contains("NOTIFY_DEBOUNCE_MS")
        )
        assertFalse(
            "AtomicLong 去抖实现不得回潮",
            serviceSource.contains("AtomicLong")
        )
    }

    // ===== AC⑤：库锁定 / 通道开关 / 自身包名三态不发 =====

    @Test
    fun `三态闸门齐备且先于通知构建`() {
        val notifyIndex = serviceSource.indexOf("postOfferNotification(")
        val switchIndex = serviceSource.indexOf("isAutofillLegacyAccessibilityEnabled()")
        val lockIndex = serviceSource.indexOf("vaultRepository.isLocked()")
        val selfIndex = serviceSource.indexOf("AutofillAccessPolicy.isSelfApp(")

        assertTrue("通知函数锚缺失", notifyIndex >= 0)
        assertTrue("通道开关闸门缺失（AC⑤ 态 2）", switchIndex in 0 until notifyIndex)
        assertTrue("库锁定闸门缺失（AC⑤ 态 1）", lockIndex in 0 until notifyIndex)
        assertTrue("自我排除闸门缺失（AC⑤ 态 3）", selfIndex in 0 until notifyIndex)
        // 自我排除须在事件入口与求值链各一次（既有 §232/§332 口径，防只删一处）
        val selfCount = Regex("""AutofillAccessPolicy\.isSelfApp\(""").findAll(serviceSource).count()
        assertTrue("自我排除应在事件入口与求值链各出现一次，当前=$selfCount", selfCount >= 2)
        // 黑名单闸门同在求值链
        assertTrue(
            "黑名单闸门缺失",
            serviceSource.contains("AutofillAccessPolicy.rejectReason(")
        )
    }

    // ===== AC④：点击只进受保护窗口选择器 =====

    @Test
    fun `通知点击目标只能是受保护选择器`() {
        assertTrue(
            "contentIntent 必须指向 LegacyFillPickerActivity（受保护窗口选择器），绝不直达填充",
            serviceSource.contains("Intent(this, LegacyFillPickerActivity::class.java)")
        )
        assertFalse(
            "通知不得直接携带回填动作（回填只经选择器递交 + 重扫复核）",
            Regex("""ACTION_SET_TEXT[^}]*setContentIntent""").containsMatchIn(serviceSource)
        )
    }

    // ===== AC③：通道低调 + 内容零敏感 =====

    @Test
    fun `通知通道重要度为低调`() {
        val channels = readSource("app/src/main/java/com/keepasskey/app/notification/NotificationChannels.kt")
        val block = channels.substringAfter("LEGACY_AUTOFILL(").substringBefore(")")
        assertTrue(
            "LEGACY_AUTOFILL 通道必须 IMPORTANCE_LOW（AC③ 不发声不横幅）",
            block.contains("IMPORTANCE_LOW")
        )
        assertFalse(
            "LEGACY_AUTOFILL 通道不得回潮为 DEFAULT/High",
            block.contains("IMPORTANCE_DEFAULT") || block.contains("IMPORTANCE_HIGH")
        )
    }

    @Test
    fun `通知文案零插值零条目字段`() {
        listOf("values/strings.xml", "values-en/strings.xml").forEach { path ->
            val source = readSource("app/src/main/res/$path")
            listOf("legacy_autofill_notification_title", "legacy_autofill_notification_body").forEach { key ->
                val value = Regex("<string name=\"$key\">(.*?)</string>").find(source)
                    ?.groupValues?.get(1)
                assertTrue("缺少 $key（$path）", value != null)
                assertFalse(
                    "$key 不得携带插值占位（AC③ 零敏感插值）",
                    value.orEmpty().contains("%")
                )
            }
        }
        // 通知构建只允许引用这两条静态文案（无 getString 拼接条目/包名/域名）
        val notifyBody = serviceSource.substringAfter("private fun postOfferNotification")
            .substringBefore("/** 观察选择器")
        assertTrue(
            "通知标题必须取静态资源",
            notifyBody.contains("R.string.legacy_autofill_notification_title")
        )
        assertTrue(
            "通知正文必须取静态资源",
            notifyBody.contains("R.string.legacy_autofill_notification_body")
        )
        assertFalse(
            "通知构建不得插值任何运行时标识（包名 / 域名 / 条目）",
            Regex("""getString\([^)]*,\s*[^)]+\)""").containsMatchIn(notifyBody)
        )
    }
}
