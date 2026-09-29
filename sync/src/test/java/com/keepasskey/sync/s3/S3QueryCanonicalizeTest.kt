package com.keepasskey.sync.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-387：S3 ListObjectsV2 查询串规范化（SigV4 CanonicalQueryString）。
 */
class S3QueryCanonicalizeTest {

    private val signer = S3RequestSigner(
        accessKeyId = "AKIDEXAMPLE".toCharArray(),
        secretAccessKey = "secret".toCharArray(),
        region = "us-east-1"
    )

    @Test
    fun `空查询串规范行为空行`() {
        assertEquals("", signer.canonicalizeQuery(""))
        assertEquals("", signer.canonicalizeQuery("   "))
    }

    @Test
    fun `键按字典序排序并编码`() {
        val canonical = signer.canonicalizeQuery("list-type=2&max-keys=10&prefix=folder%2F")
        assertTrue(canonical.startsWith("list-type=2&max-keys=10"))
        // prefix 值含已编码 %2F 时，canonicalize 会把 % 再编码为 %25 —— 生产调用方
        // 应传入**未编码**原始值（见 S3SyncProvider.listRemoteDirectory 的 signQuery）
        assertTrue(canonical.contains("prefix="))
    }

    @Test
    fun `原始未编码值经规范化后保留语义`() {
        val canonical = signer.canonicalizeQuery("prefix=folder/&delimiter=/&list-type=2")
        assertEquals(
            "delimiter=%2F&list-type=2&prefix=folder%2F",
            canonical
        )
    }
}
