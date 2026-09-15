package com.keepasskey.app.data.breach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 泄露比对哈希器单元测试（TASK-47；ISSUE-P3-102 改写为字节态 API）。
 *
 * 已知答案向量取自公开常量：SHA-1("password") = `5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8`
 * （Pwned Passwords 官方文档示例值，非真实凭据）。
 *
 * ISSUE-P3-102 后本类的断言口径变化：
 * - 原 `sha1HexUpper()` / `splitPrefixSuffix(String)` 已**删除**（它们的组合会把完整 40 位摘要
 *   物化为 `String`，且摘要字节数组不清零）——现统一走字节态 `splitPrefixSuffixOfSha1()`；
 * - 「长度非法 fail-closed」用例随实现一并删除：摘要宽度由 SHA-1 结构性保证
 *   （20 字节 → 恒 40 位十六进制），不再存在「长度非法的入参」这一失败面。
 */
class BreachHasherTest {

    @Test
    fun `前缀后缀拆分符合 k-匿名协议且可还原完整摘要`() {
        val (prefix, suffix) = BreachHasher.splitPrefixSuffixOfSha1("password".toByteArray())
        assertEquals("5BAA6", prefix)
        assertEquals(BreachHasher.PREFIX_LENGTH, prefix.length)
        assertEquals("1E4C9B93F3F0682250B6CF8331B7EE68FD8", suffix)
        // 上送前缀 + 本地后缀必须能还原完整摘要（协议正确性锚点）
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", prefix + suffix)
    }

    @Test
    fun `空输入仍产出合法 SHA-1 且不抛异常`() {
        val (prefix, suffix) = BreachHasher.splitPrefixSuffixOfSha1(ByteArray(0))
        assertEquals("DA39A3EE5E6B4B0D3255BFEF95601890AFD80709", prefix + suffix)
    }

    @Test
    fun `输入不被本函数修改（借用语义下由调用方清零）`() {
        val data = "password".toByteArray()
        val before = data.copyOf()
        BreachHasher.splitPrefixSuffixOfSha1(data)
        assertTrue("哈希器不得改动调用方的明文缓冲", data.contentEquals(before))
    }

    @Test
    fun `已删除的字符串态入口不得回归（源码守卫）`() {
        // ISSUE-P3-102：`sha1HexUpper` / `splitPrefixSuffix(String)` 会把完整 40 位摘要物化为
        // 不可擦的 String，属已整改的泄漏面——禁止被重新引入
        val source = java.io.File(repositoryRoot, "app/src/main/java/com/keepasskey/app/data/breach/BreachHasher.kt")
        assertTrue("哈希器源码文件不存在", source.isFile)
        val code = source.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        assertTrue(
            "不得重新引入返回完整 40 位摘要 String 的入口（ISSUE-P3-102 已整改）",
            !code.contains("fun sha1HexUpper(") && !code.contains("toHexString(")
        )
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: java.io.File by lazy {
            var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (java.io.File(candidate, "app/src/main/java").isDirectory &&
                    java.io.File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
