package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PSL 源文件（`src/main/resources/publicsuffix/public_suffix_list.dat`）的**前提机检**。
 *
 * `PublicSuffixList` 的装载实现为省掉逐行 `trim()` / `lowercase()`（那两笔开销在
 * `ISSUE-P1-238` 的 provider 应答预算内被实测放大到秒级），依赖源文件的三条约定。
 * 本用例把它们逐条锁死：**任何一条被破坏即红**，从而不可能「静默改变判定口径」——
 * 人工更新 `.dat` 时必须先复核本条。
 *
 * 同时锁定文件的基本规模与注释段格式（防止误替换成空文件 / 被截断的下载结果）。
 */
class PublicSuffixListResourceTest {

    private val rawBytes: ByteArray = checkNotNull(
        PublicSuffixListResourceTest::class.java.getResourceAsStream(PSL_RESOURCE_PATH)
    ) { "PSL 资源不在测试类路径上（src/main/resources 未参与单测运行时）" }
        .use { it.readBytes() }

    private val text: String = rawBytes.toString(Charsets.UTF_8)

    @Test
    fun `前提_行分隔符为纯 LF`() {
        assertEquals(
            "PSL 资源不得含 CR（装载实现只在行末做防御性裁剪，混合行尾会改变规则取值）",
            0,
            rawBytes.count { it == '\r'.code.toByte() }
        )
    }

    @Test
    fun `前提_规则行已小写且首尾无空白`() {
        val ruleLines = text.split('\n').filter { it.isNotEmpty() && !it.startsWith("//") }
        assertTrue("PSL 资源疑似为空或被截断（规则行数 ${ruleLines.size}）", ruleLines.size > 9_000)

        val notLowerCase = ruleLines.filter { it != it.lowercase() }
        assertTrue(
            "存在未小写的规则行（装载实现不再逐行 lowercase）：${notLowerCase.take(3)}",
            notLowerCase.isEmpty()
        )
        val padded = ruleLines.filter { it != it.trim() }
        assertTrue(
            "存在首尾带空白的规则行（装载实现不再逐行 trim）：${padded.take(3)}",
            padded.isEmpty()
        )
    }

    @Test
    fun `前提_注释以双斜杠起首且含官方分区标记`() {
        assertTrue("PSL 资源应以注释行起首", text.startsWith("//"))
        val comments = text.split('\n').filter { it.startsWith("//") }
        assertFalse("PSL 资源缺少注释段", comments.isEmpty())
        assertTrue(
            "PSL 资源缺少官方 ICANN 分区标记",
            comments.any { it.contains("BEGIN ICANN DOMAINS") }
        )
        assertTrue(
            "PSL 资源缺少官方 PRIVATE 分区标记",
            comments.any { it.contains("BEGIN PRIVATE DOMAINS") }
        )
    }

    private companion object {
        const val PSL_RESOURCE_PATH = "/publicsuffix/public_suffix_list.dat"
    }
}
