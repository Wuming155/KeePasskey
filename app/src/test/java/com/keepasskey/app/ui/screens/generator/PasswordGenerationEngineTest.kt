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
    fun `熵值计算直接消费 CharArray 且与详情页同一真相源（ISSUE-P2-286 AC①④）`() {
        val chars = "abcd".toCharArray()
        try {
            val bits = PasswordGenerationEngine.calculateEntropy(chars)
            // 字符集代理模型已退役：读数 = crypto 内核 guessesLog10（与详情页同一实现）。
            // 一致性（AC④）：同一输入经生成器路径与详情页路径读数必须完全一致
            val detailBits = com.keepasskey.app.ui.screens.detail.PasswordEntropyEstimator
                .estimateBits(chars)?.toDouble()
            assertEquals("生成器与详情页读数必须一致（单一真相源）", detailBits, bits)
            // 旧代理模型的读数（4 × log2(26) ≈ 18.8）必须不再复现
            assertTrue(
                "旧「字符集 × 长度」代理读数必须不再复现: $bits",
                kotlin.math.abs(bits - 4 * kotlin.math.log2(26.0)) > 0.0001
            )
        } finally {
            chars.fill('0')
        }
    }

    @Test
    fun `口令短语熵模型：词数乘 log2 词表加变形位（ISSUE-P2-286 AC②③）`() {
        // 词表规模钉死（模型的基数来源，词表变更须同步重估）
        assertEquals(2011, PasswordGenerationEngine.DICEWARE_WORD_COUNT)
        // 4 词 + 数字变形：4 × log2(2011) + log2(90) ≈ 50.39
        assertEquals(50, PasswordGenerationEngine.passphraseEntropyBits(wordCount = 4, includeNumber = true))
        // 无数字变形：4 × log2(2011) ≈ 43.89
        assertEquals(43, PasswordGenerationEngine.passphraseEntropyBits(wordCount = 4, includeNumber = false))
        // 边界：0 词恒 0
        assertEquals(0, PasswordGenerationEngine.passphraseEntropyBits(wordCount = 0, includeNumber = true))
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
