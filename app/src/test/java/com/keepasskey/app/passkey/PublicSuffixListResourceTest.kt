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
 * 更新 `.dat` 必须走 `tools/publicsuffix/refresh_psl.py`，不得手工编辑。
 *
 * 同时锁定文件的基本规模、注释段格式与来源版本身份（`VERSION` / `COMMIT`），防止误替换成
 * 空文件 / 被截断或来自镜像的下载结果（ENG-20261028-39）。
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

    /**
     * 刷新流程的身份锚（ENG-20261028-39）：随仓表必须保留官方 `VERSION` / `COMMIT` 与官方
     * 拉取地址说明。`tools/publicsuffix/refresh_psl.py` 据此判定「是否需要刷新」，发布说明也据此
     * 记录实际使用的表版本；缺失即无法核对来源与适用版本，因此与装载前提同级机检。
     */
    @Test
    fun `前提_记录官方来源与版本身份`() {
        val lines = text.split('\n')
        val versions = lines.filter { it.startsWith("// VERSION: ") }
        val commits = lines.filter { it.startsWith("// COMMIT: ") }
        assertEquals("PSL 资源须有且仅有一个 VERSION 行", 1, versions.size)
        assertEquals("PSL 资源须有且仅有一个 COMMIT 行", 1, commits.size)
        assertTrue(
            "VERSION 须为官方 UTC 时间戳形态：${versions.first()}",
            Regex("""^// VERSION: \d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_UTC$""").matches(versions.first())
        )
        assertTrue(
            "COMMIT 须为 40 位十六进制摘要：${commits.first()}",
            Regex("""^// COMMIT: [0-9a-f]{40}$""").matches(commits.first())
        )
        assertTrue(
            "PSL 资源须保留官方拉取地址说明（禁止镜像 / 其它 VCS 来源）",
            lines.any { it.contains("https://publicsuffix.org/list/public_suffix_list.dat") }
        )
        assertTrue(
            "PSL 资源须保留 MPL-2.0 许可声明",
            lines.any { it.contains("Mozilla Public") }
        )
    }

    private companion object {
        const val PSL_RESOURCE_PATH = "/publicsuffix/public_suffix_list.dat"
    }
}
