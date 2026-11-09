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
 *
 * ENG-20261028-12 整改：原「已删除的字符串态入口不得回归」用例按源码文本反查
 * `fun sha1HexUpper(` / `toHexString(`。该判据过宽（同文件的任何十六进制转换都会被判红）且只锁
 * 当时的实现写法，不能证明摘要未被物化为 String；已删除的 API 本身由编译期（无调用点即无编译）
 * 与代码评审兜底。若需长期约束「HMAC/SHA-1 摘要不得物化为不可擦 String」，应在既有扫描入口
 * 或评审规则中统一声明，不在此处按单文件字符串反查。
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
}
