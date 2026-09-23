package com.keepasskey.sync

import com.keepasskey.sync.model.cleanEtag
import com.keepasskey.sync.model.isWeakEtag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cleanEtag 规范化解析回归测试。
 * 重点回归：旧实现逐字符剥离 trim('"',' ','W','/','\\') 会误伤以 W、/ 等字符
 * 开头或结尾的合法不透明 ETag；其后实现做结构级剥离但**吞掉弱标记 `W/`**。
 * ISSUE-P1-275 AC② 起规范形态保留弱标记（读写两侧按 RFC 7232 / RFC 4918 强弱匹配规则一致化）。
 */
class SyncModelsTest {

    @Test
    fun `去除成对包裹引号`() {
        assertEquals("abc123", cleanEtag("\"abc123\""))
        assertEquals("abc123", cleanEtag("abc123"))
    }

    @Test
    fun `保留弱校验标记并去除引号`() {
        // ISSUE-P1-275 AC②：弱标记必须保留——剥标记后读写两侧只剩强形态，
        // 在弱存储标签的服务器上恒 412。规范形态 = W/ 前缀 + 不带引号不透明值
        assertEquals("W/abc123", cleanEtag("W/\"abc123\""))
        assertEquals("W/abc123", cleanEtag("w/\"abc123\""))
    }

    @Test
    fun `弱标记判定先于剥引号不误伤以W斜杠开头的不透明ETag`() {
        // 不透明标签内容本身可以 W/ 开头（"W/abc"）：弱标记判定必须在剥引号之前依语法形态
        // （W/ 后紧跟引号）进行，否则剥引号后与弱标签不可区分。规范化值 `W/abc` 与弱标签
        // 在裸形态下无法再区分（isWeakEtag 按 W/ 前缀判定，病态情形按弱处理、写侧退化为
        // 安全的 412 → 冲突重检，已记于 SyncModels KDoc），但 cleanEtag 至少保证不透明文本不丢
        assertEquals("W/abc", cleanEtag("\"W/abc\""))
    }

    @Test
    fun `规范化幂等`() {
        assertEquals("W/abc123", cleanEtag(cleanEtag("W/\"abc123\"")))
        assertEquals("abc123", cleanEtag(cleanEtag("\"abc123\"")))
    }

    @Test
    fun `弱校验判定`() {
        assertTrue(isWeakEtag("W/\"abc\""))
        assertTrue(isWeakEtag("W/abc"))
        assertFalse(isWeakEtag("\"abc\""))
        assertFalse(isWeakEtag(null))
        assertFalse(isWeakEtag(""))
    }

    @Test
    fun `不误伤以斜杠或W字符结尾开头的不透明ETag`() {
        // 旧实现会把 'W' 与 '/' 一并 trim 掉，导致 ETag 比对失配
        assertEquals("/leading/slash", cleanEtag("\"/leading/slash\""))
        assertEquals("opaque-etag/with/slashes", cleanEtag("\"opaque-etag/with/slashes\""))
        assertEquals("trailing-W", cleanEtag("\"trailing-W\""))
    }

    @Test
    fun `空白与null安全`() {
        assertEquals("", cleanEtag(null))
        assertEquals("", cleanEtag("   "))
        assertEquals("abc", cleanEtag("  \"abc\"  "))
    }
}
