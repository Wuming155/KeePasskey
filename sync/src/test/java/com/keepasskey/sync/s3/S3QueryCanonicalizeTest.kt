package com.keepasskey.sync.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-387 / ISSUE-P2-655：S3 ListObjectsV2 查询参数规范化（SigV4 CanonicalQueryString）。
 *
 * ISSUE-P2-655 的缺陷形态：旧实现把「原始键值对拼串」交给签名侧，再由签名侧 `split('&')`
 * 还原成参数。值内出现 `&` 时，线上（值被编码为 `%26`）是**一个**参数，签名里却变成**两个**，
 * canonical request 与服务端计算不一致 ⇒ 含 `&` 的合法前缀必然 403。
 * 现接口收结构化键值表，不存在「拼完再拆」这一步。
 */
class S3QueryCanonicalizeTest {

    private val signer = S3RequestSigner(
        accessKeyId = "AKIDEXAMPLE".toCharArray(),
        secretAccessKey = "secret".toCharArray(),
        region = "us-east-1"
    )

    @Test
    fun `空查询表规范行为空行`() {
        assertEquals("", signer.canonicalizeQueryPairs(emptyList()))
    }

    @Test
    fun `键按字典序排序且键值分别编码`() {
        val canonical = signer.canonicalizeQueryPairs(
            listOf(
                "prefix" to "folder/",
                "delimiter" to "/",
                "list-type" to "2"
            )
        )
        assertEquals("delimiter=%2F&list-type=2&prefix=folder%2F", canonical)
    }

    @Test
    fun `值内 ampersand 只作数据不得拆出新参数`() {
        val params = listOf(
            "list-type" to "2",
            "max-keys" to "100",
            "delimiter" to "/",
            "prefix" to "foo&bar/"
        )
        val canonical = signer.canonicalizeQueryPairs(params)

        assertEquals(
            "参数个数必须与结构化入参一致——值内的 & 不得产生第 5 个参数",
            params.size,
            canonical.split("&").size
        )
        assertTrue(
            "prefix 的值必须整体编码为 foo%26bar%2F，实际: $canonical",
            canonical.contains("prefix=foo%26bar%2F")
        )
    }

    @Test
    fun `保留字符与 Unicode 按 SigV4 规则全量编码`() {
        val canonical = signer.canonicalizeQueryPairs(
            listOf("continuation-token" to "a=b%c+d e/中文")
        )
        assertEquals(
            "continuation-token=a%3Db%25c%2Bd%20e%2F%E4%B8%AD%E6%96%87",
            canonical
        )
    }

    @Test
    fun `重复键全部保留并按值排序`() {
        val canonical = signer.canonicalizeQueryPairs(
            listOf("tag" to "b", "tag" to "a")
        )
        assertEquals("tag=a&tag=b", canonical)
    }

    @Test
    fun `未保留字符不做百分号编码`() {
        val canonical = signer.canonicalizeQueryPairs(listOf("prefix" to "A-z_0.9~"))
        assertEquals("prefix=A-z_0.9~", canonical)
    }
}
