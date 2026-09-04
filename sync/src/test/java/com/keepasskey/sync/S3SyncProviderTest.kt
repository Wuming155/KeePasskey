package com.keepasskey.sync

import com.keepasskey.sync.s3.S3SyncProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * S3SyncProvider 单元测试：
 * 验证 AWS SigV4 规范请求、Header 计算与鉴权串签名。
 */
class S3SyncProviderTest {

    @Test
    fun `测试 AWS SigV4 鉴权请求头格式`() {
        val provider = S3SyncProvider(
            endpoint = "https://s3.amazonaws.com",
            bucketName = "my-secure-vault",
            region = "us-east-1",
            accessKeyId = "AKIAEXAMPLEKEY",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )

        val fixedDate = Date(1788500000000L) // 固定测试时间点
        val headers = provider.signV4(
            method = "GET",
            url = "https://my-secure-vault.s3.amazonaws.com/vault.kdbx",
            payloadHash = S3SyncProvider.EMPTY_SHA256,
            dateTime = fixedDate
        )

        assertNotNull(headers["Authorization"])
        assertNotNull(headers["x-amz-date"])
        assertNotNull(headers["x-amz-content-sha256"])
        assertEquals(S3SyncProvider.EMPTY_SHA256, headers["x-amz-content-sha256"])

        val auth = headers["Authorization"].orEmpty()
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLEKEY/"))
        assertTrue(auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
        assertTrue(auth.contains("Signature="))
    }
}
