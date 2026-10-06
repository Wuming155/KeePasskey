package com.keepasskey.sync.s3

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 ListObjectsV2 列举的**截断不变式**回归（`ISSUE-P3-510`）。
 *
 * ## 缺陷形态
 *
 * `RemoteListPage` 的 KDoc 不变式是「RemoteListPage.truncated 为 true 时
 * RemoteListPage.nextCursor 非空」，而原实现直接把服务器声明照抄：
 * `nextCursor = if (isTruncated) nextToken else null` 与 `truncated = isTruncated`，
 * 其中 `nextToken` 还额外过滤掉空白串 ⇒ 只要服务器回 `IsTruncated=true` 而**不**
 * 返回 `NextContinuationToken`（或返回空白），就会产出 **(true, null)** 这一矛盾对。
 *
 * 合规 AWS 不会出现该组合，但**端点由用户任填**，非规范 S3 兼容实现无法排除。后果面
 * 不在模型层而在 UI：`RemoteBrowseDialog` 只看 `truncated` 就渲染「加载更多」，
 * `RemoteBrowseController` 以 null cursor 重列 ⇒ 点了没有新数据，且 `cursor == null`
 * 分支会把已有累积**清回第 1 页**并吞掉错误，全程无提示。
 *
 * ## 修复口径
 *
 * 缺 / 空 token 时**降级为未截断**（`truncated = false`）并留一次告警：宁可不给
 * 「加载更多」，也不给一个必然空转、还会回缩已得结果的入口。UI 只读 `truncated`，
 * 故不变式成立即不再渲染空转按钮。
 */
class S3DirectoryListTruncationTest {

    @Test
    fun `IsTruncated 为真且带 token 时保留分页入口`() {
        val page = listPage(listXml(isTruncated = true, token = "next-page-token"))

        assertTrue("IsTruncated=true 且 token 有效时应保留截断标记", page.truncated)
        assertEquals("token 必须如实透传", "next-page-token", page.nextCursor)
    }

    @Test
    fun `IsTruncated 为真但缺 token 时降级为未截断（不再给空转入口）`() {
        val page = listPage(listXml(isTruncated = true, token = null))

        assertEquals(
            "缺 NextContinuationToken 时不得声明截断——否则 UI 会渲染点了也无新数据的「加载更多」",
            false,
            page.truncated
        )
        assertNull("降级后游标必须为 null", page.nextCursor)
    }

    @Test
    fun `IsTruncated 为真但 token 为空白时同样降级`() {
        val page = listPage(listXml(isTruncated = true, token = "   "))

        assertEquals("空白 token 与缺失同形，同样不得声明截断", false, page.truncated)
        assertNull(page.nextCursor)
    }

    @Test
    fun `IsTruncated 为假时不声明截断`() {
        val page = listPage(listXml(isTruncated = false, token = "ignored"))

        assertEquals(false, page.truncated)
        assertNull("未截断时不得携带游标", page.nextCursor)
    }

    @Test
    fun `任何响应形态下都不得产出 truncated 为真而游标为空的矛盾对`() {
        val samples = listOf(
            listXml(isTruncated = true, token = "t"),
            listXml(isTruncated = true, token = null),
            listXml(isTruncated = true, token = ""),
            listXml(isTruncated = false, token = null)
        )
        samples.forEach { xml ->
            val parsed = S3DirectoryList.parseListObjects(xml)
            assertNotNull("合法 ListBucketResult 必须解析成功", parsed)
            val page = parsed!!
            assertTrue(
                "RemoteListPage 不变式被破坏：truncated=${page.isTruncated} 而 nextToken=${page.nextToken}",
                !page.isTruncated || page.nextToken != null
            )
        }
    }

    private fun listPage(xml: String) = runBlocking {
        S3DirectoryList.list(
            remotePath = "",
            cursor = null,
            pageSize = 100,
            buildBucketRootUrl = { "https://s3.example.com/bucket" },
            sign = { _, _, _, _ -> emptyMap() },
            execute = { request -> xmlResponse(request, xml) }
        ).getOrThrow()
    }

    private fun xmlResponse(request: Request, xml: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(xml.toResponseBody("application/xml".toMediaType()))
        .build()

    private fun listXml(isTruncated: Boolean, token: String?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
        append("<Name>bucket</Name><Prefix></Prefix><KeyCount>1</KeyCount><MaxKeys>100</MaxKeys>")
        append("<IsTruncated>").append(isTruncated).append("</IsTruncated>")
        if (token != null) append("<NextContinuationToken>").append(token).append("</NextContinuationToken>")
        append("<Contents><Key>notes.kdbx</Key><Size>1234</Size>")
        append("<LastModified>2026-10-06T00:00:00.000Z</LastModified></Contents>")
        append("</ListBucketResult>")
    }
}
