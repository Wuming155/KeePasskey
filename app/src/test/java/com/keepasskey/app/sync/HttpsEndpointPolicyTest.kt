package com.keepasskey.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HttpsEndpointPolicy 单元测试（ISSUE-P2-399：自 SettingsSyncController 抽出共享后的行为锁定；
 * 与原实现逐行为一致——空串放行、无 scheme 自动补 https://、显式非 https 拒绝）。
 */
class HttpsEndpointPolicyTest {

    @Test
    fun `空串原样放行`() {
        assertEquals("", HttpsEndpointPolicy.normalize(""))
    }

    @Test
    fun `无 scheme 输入自动补 https`() {
        assertEquals("https://dav.example.com/dav/", HttpsEndpointPolicy.normalize("dav.example.com/dav/"))
    }

    @Test
    fun `https 端点原样返回`() {
        assertEquals(
            "https://acct.r2.cloudflarestorage.com",
            HttpsEndpointPolicy.normalize("https://acct.r2.cloudflarestorage.com")
        )
    }

    @Test
    fun `显式 http 端点拒绝保存`() {
        assertNull(HttpsEndpointPolicy.normalize("http://dav.example.com/dav/"))
    }
}
