package com.keepasskey.sync.s3

import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 目录列举的两条协议口径回归：
 *
 * - `ISSUE-P2-655`：wire URL 与 SigV4 canonical query 必须来自**同一份**规范输出；
 * - `ISSUE-P3-656`：XML `<LastModified>` 是 ISO-8601，不得再交给只认 RFC 1123 的 HTTP 日期解析器。
 */
class S3DirectoryListMetadataTest {

    /** 2026-10-06T00:00:00Z */
    private val expectedEpochMillis = 1_791_244_800_000L

    @Test
    fun `含 ampersand 的前缀在 wire 与签名之间保持一致`() = runBlocking {
        var signedParams: List<Pair<String, String>>? = null
        var signedUrl: String? = null
        var requestUrl: String? = null

        S3DirectoryList.list(
            remotePath = "foo&bar",
            cursor = null,
            pageSize = 100,
            buildBucketRootUrl = { "https://s3.example.com/bucket" },
            sign = { _, url, _, params ->
                signedUrl = url
                signedParams = params
                emptyMap()
            },
            execute = { request ->
                requestUrl = request.url.toString()
                xmlResponse(request, listXml(lastModified = "2026-10-06T00:00:00.000Z"))
            }
        ).getOrThrow()

        val params = requireNotNull(signedParams)
        val canonical = S3RequestSigner(
            "AKIDEXAMPLE".toCharArray(),
            "secret".toCharArray(),
            "us-east-1"
        ).canonicalizeQueryPairs(params)

        assertEquals("签名必须面向 bucket 根 URL，不含 query", "https://s3.example.com/bucket", signedUrl)
        val wireUrl = requireNotNull(requestUrl)
        assertEquals(
            "线上 query 必须与签名 canonical query 逐字一致（旧实现会在此处分叉）",
            canonical,
            wireUrl.substringAfter('?')
        )
        assertEquals("含 & 的前缀不得改变参数个数", 4, params.size)
        assertTrue("值必须整体编码为 foo%26bar%2F", wireUrl.contains("prefix=foo%26bar%2F"))
        assertEquals(
            "结构化入参里 prefix 必须是未编码的原始目录",
            "foo&bar/",
            params.first { it.first == "prefix" }.second
        )
    }

    @Test
    fun `ISO8601 LastModified 解析为真实 epoch 毫秒`() {
        val entry = parseSingleEntry("2026-10-06T00:00:00.000Z")
        assertEquals(expectedEpochMillis, entry.lastModifiedMillis)
    }

    @Test
    fun `无小数秒与带偏移的 ISO 时间同样解析`() {
        assertEquals(expectedEpochMillis, parseSingleEntry("2026-10-06T00:00:00Z").lastModifiedMillis)
        assertEquals(
            "带 +08:00 偏移的同一时刻必须归一到同一 epoch",
            expectedEpochMillis,
            parseSingleEntry("2026-10-06T08:00:00+08:00").lastModifiedMillis
        )
    }

    @Test
    fun `非法 XML 时间降级为未知而不伪装整页失败`() {
        val entry = parseSingleEntry("not-a-timestamp")
        assertEquals("无法解析时保持模型既有「0 = 未知」语义", 0L, entry.lastModifiedMillis)
    }

    @Test
    fun `HTTP 头仍按 RFC1123 解析`() {
        assertEquals(
            "HTTP 日期路径不得被 XML 时间整改带偏",
            expectedEpochMillis,
            S3HttpDateCodec.parse("Tue, 06 Oct 2026 00:00:00 GMT")
        )
        assertEquals("HTTP 解析器不认 ISO 时间（协议归口不同，不可混用）", 0L, S3HttpDateCodec.parse("2026-10-06T00:00:00.000Z"))
    }

    @Test
    fun `空时间元素保持未知`() {
        assertEquals(0L, parseSingleEntry("").lastModifiedMillis)
        val parsed = S3DirectoryList.parseListObjects(
            listXml(lastModified = null)
        )
        assertNotNull("缺 LastModified 元素不应导致整页解析失败", parsed)
        assertEquals(0L, parsed!!.entries.single().lastModifiedMillis)
    }

    @Test
    fun `ETag 与大小不受时间整改影响`() {
        val entry = parseSingleEntry("2026-10-06T00:00:00.000Z")
        assertEquals(1234L, entry.contentLength)
        assertEquals("notes.kdbx", entry.path)
        assertEquals("notes.kdbx", entry.name)
        assertTrue("普通对象必须标记为非目录", !entry.isDirectory)
    }

    private fun parseSingleEntry(lastModified: String?): com.keepasskey.sync.model.RemoteListEntry {
        val parsed = S3DirectoryList.parseListObjects(listXml(lastModified = lastModified))
        assertNotNull("合法 ListBucketResult 必须解析成功", parsed)
        return parsed!!.entries.single()
    }

    private fun xmlResponse(request: Request, xml: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(xml.toResponseBody("application/xml".toMediaType()))
        .build()

    private fun listXml(lastModified: String?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
        append("<Name>bucket</Name><Prefix></Prefix><KeyCount>1</KeyCount><MaxKeys>100</MaxKeys>")
        append("<IsTruncated>false</IsTruncated>")
        append("<Contents><Key>notes.kdbx</Key><Size>1234</Size>")
        if (lastModified != null) {
            append("<LastModified>").append(lastModified).append("</LastModified>")
        }
        append("</Contents>")
        append("</ListBucketResult>")
    }
}
