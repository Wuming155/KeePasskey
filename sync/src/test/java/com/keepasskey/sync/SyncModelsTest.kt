package com.keepasskey.sync

import com.keepasskey.sync.model.cleanEtag
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * cleanEtag 规范化解析回归测试。
 * 重点回归：旧实现逐字符剥离 trim('"',' ','W','/','\\') 会误伤以 W、/ 等字符
 * 开头或结尾的合法不透明 ETag；新实现仅做结构级剥离（W/ 前缀 + 成对引号）。
 */
class SyncModelsTest {

    @Test
    fun `去除成对包裹引号`() {
        assertEquals("abc123", cleanEtag("\"abc123\""))
        assertEquals("abc123", cleanEtag("abc123"))
    }

    @Test
    fun `去除弱校验前缀与引号`() {
        assertEquals("abc123", cleanEtag("W/\"abc123\""))
        assertEquals("abc123", cleanEtag("w/\"abc123\""))
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
