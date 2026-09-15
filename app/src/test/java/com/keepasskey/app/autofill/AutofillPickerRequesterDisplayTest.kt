package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-70（审计 E1）回归：**手动选择器必须展示请求方身份**。
 *
 * 缺陷形态：自动匹配路径有严格边界，而手动兜底选择器可把**任意条目**凭据交给请求方，
 * 页面却不显示「谁在请求」——用户在零归属信息下完成填充授权。
 *
 * 本用例分两层：
 * 1. **展示模型构造**（纯函数）——包名缺失不伪造归属；空白字段按「无」处理；
 * 2. **渲染与接线**（静态源码断言，对齐既有 `*WiringTest` 先例）——四项内容必须渲染、
 *    归属块必须位于搜索框之前（无需滚动即可见）、且域文案**不得**沿用确认页的
 *    「已经归属校验」措辞（选择器拿到的是表单自报域，两者语义不同）。
 */
class AutofillPickerRequesterDisplayTest {

    // ---------------- ① 展示模型构造 ----------------

    @Test
    fun `包名缺失时不构造归属（不伪造未知应用占位）`() {
        assertNull(buildAutofillPickerRequester(null, "Name", "AA", "example.com"))
        assertNull(buildAutofillPickerRequester("", "Name", "AA", "example.com"))
        assertNull(buildAutofillPickerRequester("   ", "Name", "AA", "example.com"))
    }

    @Test
    fun `包名保留原值而空白字段按无处理`() {
        val requester = buildAutofillPickerRequester(
            packageName = "  com.example.app  ",
            appLabel = "   ",
            certSha256Hex = "",
            reportedDomain = "  "
        )
        assertEquals("com.example.app", requester?.packageName)
        assertNull("空白应用名按「无名称」处理（不得渲染空行）", requester?.appLabel)
        assertNull("空白证书摘要按「不可读」处理", requester?.certSha256Hex)
        assertNull("空白域按「无域」处理", requester?.reportedDomain)
    }

    @Test
    fun `四项齐备时逐项保留`() {
        val requester = buildAutofillPickerRequester(
            packageName = "com.example.app",
            appLabel = "Example",
            certSha256Hex = "A".repeat(64),
            reportedDomain = "example.com"
        )
        assertEquals("com.example.app", requester?.packageName)
        assertEquals("Example", requester?.appLabel)
        assertEquals(64, requester?.certSha256Hex?.length)
        assertEquals("example.com", requester?.reportedDomain)
    }

    // ---------------- ② 渲染与接线 ----------------

    @Test
    fun `选择器页必须渲染包名 应用名 签名摘要与域四项`() {
        val screen = readSource(PICKER_SCREEN_PATH)
        for (required in listOf(
            "R.string.autofill_confirm_caller_package",
            "R.string.autofill_picker_requester_label",
            "R.string.autofill_confirm_caller_cert",
            "R.string.autofill_confirm_cert_unreadable",
            "R.string.autofill_picker_requester_domain",
            "R.string.autofill_picker_requester_domain_none"
        )) {
            assertTrue("选择器页缺少 $required 的渲染（AC① 要求四类信息齐备）", screen.contains(required))
        }
    }

    @Test
    fun `归属块必须位于搜索框之前（强制展示，无需滚动）`() {
        val screen = readSource(PICKER_SCREEN_PATH)
        val blockIndex = screen.indexOf("AutofillPickerRequesterBlock(it)")
        val searchIndex = screen.indexOf("OutlinedTextField(")
        assertTrue("未找到归属块渲染点", blockIndex >= 0)
        assertTrue("未找到搜索框渲染点", searchIndex >= 0)
        assertTrue(
            "归属块必须排在搜索框之前，否则「强制展示」在小屏上不可见",
            blockIndex < searchIndex
        )
    }

    @Test
    fun `域文案不得沿用确认页的已经归属校验措辞`() {
        val screen = readSource(PICKER_SCREEN_PATH)
        assertFalse(
            "选择器拿到的是表单自报域（未校验），不得沿用确认页的「已经归属校验」文案",
            screen.contains("R.string.autofill_confirm_caller_domain")
        )
        val zh = readSource("app/src/main/res/values/strings.xml")
        val en = readSource("app/src/main/res/values-en/strings.xml")
        assertTrue(
            "中文域文案须点明「未通过归属校验」",
            stringBody(zh, "autofill_picker_requester_domain").contains("未通过归属校验")
        )
        assertTrue(
            "英文域文案须点明 not ownership-verified",
            stringBody(en, "autofill_picker_requester_domain").contains("not ownership-verified")
        )
        assertTrue(
            "应用名文案须点明可由应用自声明（不得暗示其为权威锚点）",
            stringBody(zh, "autofill_picker_requester_label").contains("可自声明")
        )
    }

    @Test
    fun `落地 Activity 必须解析并传入请求方身份`() {
        val activity = readSource(PICKER_ACTIVITY_PATH)
        assertTrue("Activity 必须解析请求方身份", activity.contains("resolveRequester()"))
        assertTrue("Activity 必须把请求方身份传入界面", activity.contains("requester = requester"))
        assertTrue(
            "签名摘要必须复用确认页同一读取通道（AutofillOriginResolver）",
            activity.contains("autofillOriginResolver.callingAppCertSha256Hex(")
        )
        assertTrue(
            "应用名读取失败必须如实降级（不得伪造名称）",
            activity.contains("按无名称处理")
        )
    }

    // ---------------- 辅助 ----------------

    private fun stringBody(xml: String, name: String): String =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: error("未找到字符串资源 $name")

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码/资源文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PICKER_SCREEN_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerScreen.kt"
        const val PICKER_ACTIVITY_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerActivity.kt"
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
