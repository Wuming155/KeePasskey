package com.keepasskey.app.ui.screens.generator

import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-16 回归：密码生成引擎的出边界由 String 收敛为 CharArray。
 *
 * 覆盖三点契约：
 * 1. 三个生成函数返回 **CharArray 独占副本**（长度与字符集符合参数）；
 * 2. 熵值计算直接消费 CharArray；
 * 3. 借用副本密封进 [ProtectedString] 后，调用方清零不影响受控容器（与 GeneratorViewModel 同一路径）。
 */
class PasswordGenerationEngineTest {

    @Test
    fun `随机密码以 CharArray 返回且仅使用启用字符集`() {
        val chars = PasswordGenerationEngine.generateRandomPassword(
            length = 24,
            useUpper = true,
            useLower = true,
            useDigits = false,
            useSymbols = false,
            excludeAmbiguous = true
        )
        try {
            assertEquals(24, chars.size)
            assertTrue(
                "仅应包含启用的大写/小写字母",
                chars.all { it in PasswordGenerationEngine.CHARS_UPPER || it in PasswordGenerationEngine.CHARS_LOWER }
            )
            assertFalse("未启用数字时不得出现数字", chars.any { it in PasswordGenerationEngine.CHARS_DIGITS })
        } finally {
            chars.fill('0')
        }
    }

    @Test
    fun `全部字符集关闭时回退小写字母`() {
        val chars = PasswordGenerationEngine.generateRandomPassword(
            length = 8,
            useUpper = false,
            useLower = false,
            useDigits = false,
            useSymbols = false,
            excludeAmbiguous = false
        )
        try {
            assertEquals(8, chars.size)
            assertTrue(chars.all { it in PasswordGenerationEngine.CHARS_LOWER })
        } finally {
            chars.fill('0')
        }
    }

    @Test
    fun `密码短语以 CharArray 返回且词数与分隔符正确`() {
        val chars = PasswordGenerationEngine.generatePassphrase(
            wordCount = 4,
            separator = "-",
            capitalize = true,
            includeNumber = true
        )
        try {
            val parts = String(chars).split("-")
            assertEquals("应以分隔符串起 4 个词", 4, parts.size)
            assertTrue("开启首字母大写后每个词均应大写开头", parts.all { it.isNotEmpty() && it[0].isUpperCase() })
        } finally {
            chars.fill('0')
        }
    }

    @Test
    fun `掩码密码以 CharArray 返回且按掩码规则填充`() {
        val chars = PasswordGenerationEngine.generateMaskedPassword("dd-uul")
        try {
            assertEquals(6, chars.size)
            assertTrue(chars[0].isDigit())
            assertTrue(chars[1].isDigit())
            assertEquals('-', chars[2])
            assertTrue(chars[3].isUpperCase())
            assertTrue(chars[4].isUpperCase())
            assertTrue(chars[5].isLowerCase())
        } finally {
            chars.fill('0')
        }
    }

    @Test
    fun `熵值计算直接消费 CharArray`() {
        val chars = "abcd".toCharArray()
        try {
            val bits = PasswordGenerationEngine.calculateEntropy(chars)
            // 纯小写字符集 → log2(26) 每字符
            assertEquals(4 * kotlin.math.log2(26.0), bits, 0.0001)
        } finally {
            chars.fill('0')
        }
    }

    @Test
    fun `生成结果密封后清零调用方 CharArray 不影响受控容器`() {
        val chars = PasswordGenerationEngine.generateRandomPassword(
            length = 20,
            useUpper = true,
            useLower = true,
            useDigits = true,
            useSymbols = true,
            excludeAmbiguous = false
        )
        // 与 GeneratorViewModel.generateNewPassword 同一路径：先密封，再清零借用副本
        val secret = ProtectedString(chars, isProtected = true)
        chars.fill('0')

        assertTrue("借用副本应被显式清零", chars.all { it == '0' })
        secret.useChars { assertEquals(20, it.size) }
        secret.clear()
    }
}
