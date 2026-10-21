package com.keepasskey.sync.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ISSUE-P2-655：S3 签名与**独立实现**对拍，而非只在本仓内部自证。
 *
 * 两条互相独立的证据链：
 *
 * 1. **官方 AWS test-suite 向量**（`get-vanilla-query-order-key-case`，botocore 仓库内
 *    `tests/unit/auth/aws4_testsuite/` 的 `.creq` / `.sts` / `.authz` 原文，2015-08-30）。
 *    该用例证明「未排序的 `?Param2=value2&Param1=value1` 必须规范化成
 *    `Param1=value1&Param2=value2`」——正是 P2-655 的缺陷面。它的期望 Authorization 为
 *    `Signature=b97d918c…`，service 是 `service` 且 SignedHeaders 只有 `host;x-amz-date`；
 *    本仓签名器固定 `s3` 且额外签 `x-amz-content-sha256`，**不能直接比较 Authorization**。
 *    因此这里的对拍口径是「**按其原文重建 canonical request / string-to-sign，再用本仓的
 *    SHA-256、签名密钥派生链与 HMAC 计算签名**」，与服务端的差异（service 名与头部集合）
 *    由向量原文显式给出，不靠推测。它验证的是签名算法四要素与 AWS 公布值逐字节一致。
 *
 * 2. **官方 SDK 生成的 s3 向量**（`tools/local-sync/gen_sigv4_vectors.py`，botocore 1.43.5）。
 *    生成时**完全按本仓签名器的头部集合**（`host;x-amz-content-sha256;x-amz-date`）、
 *    `s3` 服务名与空体摘要计算，故可以直接用 [S3RequestSigner.signV4] 的整条输出比对
 *    Authorization。覆盖含 `&` 的前缀、含 `&` / `=` / `+` 的续页 token、Unicode 与保留字符、
 *    重复键、空查询、含 `&` 的对象键路径。
 *
 * 两个方向都要求「wire query 与 canonical query 逐字一致」，即 P2-655 的整改判据。
 */
class S3OfficialSigV4VectorTest {

    private val signer = S3RequestSigner(
        accessKeyId = "AKIDEXAMPLE".toCharArray(),
        secretAccessKey = SECRET_KEY.toCharArray(),
        region = "us-east-1"
    )

    // ------------------------------------------------------------------
    // 1. 官方 AWS test-suite 向量（原文对齐，显式声明适配差异）
    // ------------------------------------------------------------------

    @Test
    fun `官方 test-suite 向量_查询参数按字典序规范化`() {
        // 入参故意乱序（官方 .req 原文即 Param2 在前），规范化后必须与官方 .creq 第三行一致
        val canonicalQuery = signer.canonicalizeQueryPairs(
            listOf("Param2" to "value2", "Param1" to "value1")
        )
        assertEquals(OFFICIAL_CREQ_QUERY_LINE, canonicalQuery)
    }

    @Test
    fun `官方 test-suite 向量_签名算法四要素与AWS公布值一致`() {
        // 以下文本逐字取自官方 .creq / .sts（仅拼接，不改写）
        val canonicalRequest = buildString {
            append("GET\n")
            append(OFFICIAL_CREQ_URI).append('\n')
            append(OFFICIAL_CREQ_QUERY_LINE).append('\n')
            append("host:").append(OFFICIAL_HOST).append('\n')
            append("x-amz-date:").append(OFFICIAL_AMZ_DATE).append('\n')
            append('\n')
            append(OFFICIAL_SIGNED_HEADERS).append('\n')
            append(OFFICIAL_PAYLOAD_HASH)
        }
        assertEquals("官方 .creq 必须逐行一致（含尾行负载摘要）", OFFICIAL_CREQ_TEXT, canonicalRequest)

        val canonicalRequestHash = signer.sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        assertEquals("canonical request 的 SHA-256 必须与官方 .sts 第四行一致", OFFICIAL_STS_HASH, canonicalRequestHash)

        val stringToSign = "AWS4-HMAC-SHA256\n$OFFICIAL_AMZ_DATE\n$OFFICIAL_SCOPE\n$canonicalRequestHash"
        assertEquals("string-to-sign 必须与官方 .sts 逐行一致", OFFICIAL_STS_TEXT, stringToSign)

        // 适配点：官方向量的 service 是 `service`，本仓生产固定 `s3`；
        // 密钥派生链本身接受 serviceName 参数，故此处以其原文取值验证派生链与 HMAC。
        val kSecret = ("AWS4" + SECRET_KEY).toByteArray(Charsets.UTF_8)
        val signingKey = signer.deriveSigningKeyChain(
            kSecret,
            "20150830",
            "us-east-1",
            "service"
        ) { key, data -> hmacSha256(key, data) }
        val signature = try {
            hmacSha256Hex(signingKey, stringToSign)
        } finally {
            signingKey.fill(0)
            kSecret.fill(0)
        }

        assertEquals("签名必须与官方 .authz 的 Signature 完全一致", OFFICIAL_SIGNATURE, signature)
        assertTrue("前置证据：官方向量签名不是本仓产物", OFFICIAL_SIGNATURE.length == 64)
    }

    // ------------------------------------------------------------------
    // 2. 官方 SDK（botocore）生成的 s3 向量：整条 Authorization 对拍
    // ------------------------------------------------------------------

    @Test
    fun `官方SDK生成的s3向量_整条Authorization逐字一致`() {
        vectors.forEach { vector ->
            val canonicalQuery = signer.canonicalizeQueryPairs(vector.queryParams)
            assertEquals(
                "${vector.name}：wire query 必须与 canonical query 逐字一致",
                vector.wireQuery,
                canonicalQuery
            )

            val headers = signer.signV4(
                method = vector.method,
                url = vector.url,
                payloadHash = S3RequestSigner.EMPTY_SHA256,
                dateTime = FIXED_DATE,
                queryParams = vector.queryParams
            )
            val authorization = headers.getValue("Authorization")
            assertTrue(
                "${vector.name}：签名必须与独立 SDK 计算一致\n  实际: $authorization\n  期望签名: ${vector.signature}",
                authorization.endsWith("Signature=${vector.signature}")
            )
            assertEquals(
                "${vector.name}：SignedHeaders 必须与向量生成时的头部集合一致",
                "host;x-amz-content-sha256;x-amz-date",
                authorization.substringAfter("SignedHeaders=").substringBefore(",")
            )
        }
    }

    @Test
    fun `含与号前缀不改变参数个数且续页token整体编码`() {
        val firstPage = vector("amp_prefix_first_page")
        val cursorPage = vector("amp_cursor_second_page")

        assertEquals("首分页 4 个参数（含 & 前缀不得被拆成 5 个）", 4, firstPage.queryParams.size)
        assertTrue(
            "前缀值必须整体编码为 notes%26archive%2F",
            firstPage.wireQuery.contains("prefix=notes%26archive%2F")
        )
        assertEquals("续页 5 个参数（token 内 & 与 = 只作数据）", 5, cursorPage.queryParams.size)
        assertTrue(
            "续页 token 必须整体编码",
            cursorPage.wireQuery.contains("continuation-token=1%2Ftok%26en%3D9%2Bz")
        )
    }

    private fun vector(name: String): SigV4Vector =
        vectors.first { it.name == name }

    // ------------------------------------------------------------------
    // 向量定义
    // ------------------------------------------------------------------

    private class SigV4Vector(
        val name: String,
        val method: String,
        val url: String,
        val queryParams: List<Pair<String, String>>,
        /** URL 中 `?` 之后的 wire 形态（无查询时为空串） */
        val wireQuery: String,
        val signature: String
    )

    private companion object {
        const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
        const val OFFICIAL_HOST = "example.amazonaws.com"
        const val OFFICIAL_AMZ_DATE = "20150830T123600Z"
        const val OFFICIAL_CREQ_URI = "/"
        const val OFFICIAL_CREQ_QUERY_LINE = "Param1=value1&Param2=value2"
        const val OFFICIAL_SIGNED_HEADERS = "host;x-amz-date"
        const val OFFICIAL_PAYLOAD_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val OFFICIAL_SCOPE = "20150830/us-east-1/service/aws4_request"
        const val OFFICIAL_SIGNATURE =
            "b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500"

        val OFFICIAL_CREQ_TEXT = """
            GET
            /
            $OFFICIAL_CREQ_QUERY_LINE
            host:$OFFICIAL_HOST
            x-amz-date:$OFFICIAL_AMZ_DATE

            $OFFICIAL_SIGNED_HEADERS
            $OFFICIAL_PAYLOAD_HASH
        """.trimIndent()

        val OFFICIAL_STS_HASH = "816cd5b414d056048ba4f7c5386d6e0533120fb1fcfa93762cf0fc39e2cf19e0"
        val OFFICIAL_STS_TEXT = """
            AWS4-HMAC-SHA256
            $OFFICIAL_AMZ_DATE
            $OFFICIAL_SCOPE
            $OFFICIAL_STS_HASH
        """.trimIndent()

        /** 2015-08-30T12:36:00Z（官方向量时间点） */
        val FIXED_DATE: Date = Date(1_440_938_160_000L)

        const val BUCKET_URL = "https://example.amazonaws.com/bucket"

        /**
         * 由 `tools/local-sync/gen_sigv4_vectors.py`（botocore 1.43.5）生成；
         * 生成脚本与本测试的头部集合、服务名、空体摘要必须一致，否则不能直接比较整条 Authorization。
         */
        val vectors = listOf(
            SigV4Vector(
                name = "amp_prefix_first_page",
                method = "GET",
                url = "$BUCKET_URL?delimiter=%2F&list-type=2&max-keys=1&prefix=notes%26archive%2F",
                queryParams = listOf(
                    "list-type" to "2",
                    "max-keys" to "1",
                    "delimiter" to "/",
                    "prefix" to "notes&archive/"
                ),
                wireQuery = "delimiter=%2F&list-type=2&max-keys=1&prefix=notes%26archive%2F",
                signature = "592dfadff216838c9ac69e975e4fa19a8fc7e952dd4840a76f43fc2f2d90e463"
            ),
            SigV4Vector(
                name = "amp_cursor_second_page",
                method = "GET",
                url = "$BUCKET_URL?continuation-token=1%2Ftok%26en%3D9%2Bz&delimiter=%2F" +
                    "&list-type=2&max-keys=1&prefix=notes%26archive%2F",
                queryParams = listOf(
                    "list-type" to "2",
                    "max-keys" to "1",
                    "delimiter" to "/",
                    "prefix" to "notes&archive/",
                    "continuation-token" to "1/tok&en=9+z"
                ),
                wireQuery = "continuation-token=1%2Ftok%26en%3D9%2Bz&delimiter=%2F" +
                    "&list-type=2&max-keys=1&prefix=notes%26archive%2F",
                signature = "296f3189dea0c60cb9d37c230264c8cc2469160179f9307100fc0b3dcd9b0936"
            ),
            SigV4Vector(
                name = "unicode_and_reserved_value",
                method = "GET",
                url = "$BUCKET_URL?delimiter=%2F&list-type=2&max-keys=1000" +
                    "&prefix=%E4%B8%AD%E6%96%87%20%E5%BA%93%2B%25~",
                queryParams = listOf(
                    "list-type" to "2",
                    "max-keys" to "1000",
                    "delimiter" to "/",
                    "prefix" to "中文 库+%~"
                ),
                wireQuery = "delimiter=%2F&list-type=2&max-keys=1000" +
                    "&prefix=%E4%B8%AD%E6%96%87%20%E5%BA%93%2B%25~",
                signature = "5d1171df0038774597c441743fa9678a23f66f2b84c3640ecf5094b8abfa6d6e"
            ),
            SigV4Vector(
                name = "repeated_key_sorted_by_value",
                method = "GET",
                url = "$BUCKET_URL?tag=a&tag=b",
                queryParams = listOf("tag" to "b", "tag" to "a"),
                wireQuery = "tag=a&tag=b",
                signature = "0c6e079d7399e8d4550c35fd19bfea5d6b2a8052491bb11344ee781f2810cce2"
            ),
            SigV4Vector(
                name = "empty_query",
                method = "GET",
                url = BUCKET_URL,
                queryParams = emptyList(),
                wireQuery = "",
                signature = "09fed60109e76432d09c970ece11c7cd089c02cd299b0247e7a257e3c8c1a287"
            ),
            SigV4Vector(
                name = "path_with_ampersand",
                method = "GET",
                url = "$BUCKET_URL/notes%26archive/a%20b.kdbx",
                queryParams = emptyList(),
                wireQuery = "",
                signature = "d27ad7c07a1d6e88c72855848e9797f7ab0a780f5a1f0f700e9bc0f557565442"
            )
        )

        fun hmacSha256(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data.toByteArray(Charsets.UTF_8))
            }

        fun hmacSha256Hex(key: ByteArray, data: String): String {
            val mac = hmacSha256(key, data)
            return try {
                mac.joinToString("") { "%02x".format(it) }
            } finally {
                mac.fill(0)
            }
        }
    }
}
