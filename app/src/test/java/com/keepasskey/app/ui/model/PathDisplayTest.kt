package com.keepasskey.app.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-132 ② 回归：长路径**中段省略**。
 *
 * 缺陷形态：密码库卡片直接渲染完整 `path` 并以 `TextOverflow.Ellipsis` 兜底——末端省略恰好
 * 砍掉最有辨识度的**文件名**，长路径还可能在窄屏折行把卡片撑高。
 *
 * 本用例锁定 [middleEllipsize] 的四条不变式（缺任一条都会让「看起来修好了」的实现悄悄退化）：
 * 1. 返回长度恒 ≤ 预算（否则折行问题原样存在）；
 * 2. 短于预算的文本**原样返回**（不得为了统一好看而改写短路径）；
 * 3. 文件名段（最后一段）在可行时**完整保留**，且头部切点落在分隔符上（不切碎目录名）；
 * 4. 退化输入（无分隔符 / 预算过小 / 空串）不抛异常且不产出空壳。
 */
class PathDisplayTest {

    private val longPath = "/storage/emulated/0/Documents/preview.kdbx"

    @Test
    fun `短于或等于预算的路径原样返回`() {
        assertEquals(longPath, middleEllipsize(longPath, longPath.length))
        assertEquals("/a/b.kdbx", middleEllipsize("/a/b.kdbx", DATABASE_PATH_MAX_CHARS))
        assertEquals("", middleEllipsize("", DATABASE_PATH_MAX_CHARS))
    }

    @Test
    fun `长路径按报告期望的中段省略保住文件名`() {
        val result = middleEllipsize(longPath, maxChars = 24)

        assertEquals("/storage/…/preview.kdbx", result)
        assertTrue("必须保住完整文件名段", result.endsWith("/preview.kdbx"))
    }

    @Test
    fun `结果长度恒不超过预算`() {
        val budgets = listOf(3, 4, 5, 8, 12, 16, 20, 24, 30, 36, 40)
        budgets.forEach { budget ->
            val result = middleEllipsize(longPath, budget)
            assertTrue(
                "预算 $budget 时产出长度 ${result.length} 超预算：$result",
                result.length <= budget
            )
        }
    }

    @Test
    fun `默认预算下长路径仍可单行展示且保留文件名`() {
        val veryLong = "/storage/emulated/0/Android/data/com.example/files/backup/vault-main.kdbx"
        val result = middleEllipsize(veryLong, DATABASE_PATH_MAX_CHARS)

        assertTrue("默认预算下必须至少砍掉一个字符", result.length < veryLong.length)
        assertTrue("默认预算下长度不得超预算", result.length <= DATABASE_PATH_MAX_CHARS)
        assertTrue("必须保住完整文件名", result.endsWith("/vault-main.kdbx"))
        assertTrue("中段省略应使用省略号", result.contains("…"))
    }

    @Test
    fun `无分隔符的长文本退化为对半中段省略`() {
        assertEquals("abc…ij", middleEllipsize("abcdefghij", maxChars = 6))
    }

    @Test
    fun `预算小于最小可用宽度时退化为纯截断`() {
        assertEquals("ab", middleEllipsize("abcdef", maxChars = 2))
        assertEquals("a", middleEllipsize("abcdef", maxChars = 1))
        assertEquals("", middleEllipsize("abcdef", maxChars = 0))
        assertEquals("", middleEllipsize("abcdef", maxChars = -5))
    }

    @Test
    fun `文件名自身超预算时不吞掉整个尾部`() {
        val result = middleEllipsize("/a/verylongfilename.txt", maxChars = 10)

        assertTrue("长度仍受预算约束", result.length <= 10)
        assertTrue("仍应保留尾部扩展名", result.endsWith(".txt"))
        assertTrue("仍应保留头部目录", result.startsWith("/a/"))
    }

    @Test
    fun `URL 形态路径同样按中段省略处理`() {
        val url = "https://dav.example.com/remote/keepass/vault-main.kdbx"
        val result = middleEllipsize(url, DATABASE_PATH_MAX_CHARS)

        assertTrue(result.length <= DATABASE_PATH_MAX_CHARS)
        assertTrue("URL 的文件名段同样必须保留", result.endsWith("/vault-main.kdbx"))
    }
}
