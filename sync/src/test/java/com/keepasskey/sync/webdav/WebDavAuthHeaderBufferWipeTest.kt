package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.SyncException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** WebDAV Basic 认证中间缓冲与凭据载体的行为、生命周期契约（虚构凭据）。 */
class WebDavAuthHeaderBufferWipeTest {

    @Test
    fun `虚构 ASCII 中文与代理对口令按 UTF8 构造且不修改借用数组`() {
        val cases = listOf(
            Triple("fake-user", "fake-pass-123", "fake-user:fake-pass-123"),
            Triple("虚构用户", "假口令-中文-😀", "虚构用户:假口令-中文-😀"),
            Triple("empty-pass-user", "", "empty-pass-user:")
        )

        cases.forEach { (username, password, expectedCredentials) ->
            val passwordChars = password.toCharArray()
            val originalPassword = passwordChars.copyOf()

            val header = WebDavAuthHeader.build(username, passwordChars)

            assertTrue(header.startsWith("Basic "))
            val decoded = Base64.getDecoder()
                .decode(header.removePrefix("Basic "))
                .toString(Charsets.UTF_8)
            assertEquals(expectedCredentials, decoded)
            assertArrayEquals("调用方借用的 CharArray 不得被清除或改写", originalPassword, passwordChars)
        }
    }

    @Test
    fun `编码与拼接缓冲在正常退出后均清零`() {
        val passwordChars = "fake-中文-😀".toCharArray()
        var encodedBytes: ByteArray? = null
        var combinedBytes: ByteArray? = null

        val encodedLength = withWipedPasswordBuffer(passwordChars) { encoded ->
            encodedBytes = encoded.array()
            assertArrayEquals(
                "UTF-8 编码阶段应实际持有虚构口令字节",
                "fake-中文-😀".toByteArray(Charsets.UTF_8),
                encoded.array().copyOfRange(encoded.arrayOffset(), encoded.arrayOffset() + encoded.limit())
            )
            encoded.remaining()
        }
        withWipedCredentialBytes(encodedLength + 1) { combined ->
            combinedBytes = combined
            combined.fill(0x5A)
        }

        val wipedEncodedBytes = checkNotNull(encodedBytes)
        val wipedCombinedBytes = checkNotNull(combinedBytes)
        assertArrayEquals(ByteArray(wipedEncodedBytes.size), wipedEncodedBytes)
        assertArrayEquals(ByteArray(wipedCombinedBytes.size), wipedCombinedBytes)
    }

    @Test
    fun `拼接操作抛错时编码与拼接缓冲都在 finally 清零`() {
        val passwordChars = "fake-password-密".toCharArray()
        var encodedBytes: ByteArray? = null
        var combinedBytes: ByteArray? = null
        val failure = IllegalStateException("synthetic credential encoding failure")

        val thrown = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            withWipedPasswordBuffer(passwordChars) { encoded ->
                encodedBytes = encoded.array()
                withWipedCredentialBytes(encoded.remaining() + 1) { combined ->
                    combinedBytes = combined
                    combined.fill(0x5A)
                    throw failure
                }
            }
        }

        assertSame(failure, thrown)
        val wipedEncodedBytes = checkNotNull(encodedBytes)
        val wipedCombinedBytes = checkNotNull(combinedBytes)
        assertArrayEquals(ByteArray(wipedEncodedBytes.size), wipedEncodedBytes)
        assertArrayEquals(ByteArray(wipedCombinedBytes.size), wipedCombinedBytes)
        assertEquals("synthetic credential encoding failure", thrown.message)
    }

    @Test
    fun `拼接缓冲分配失败时已创建的编码缓冲仍清零`() {
        val passwordChars = "fake-password-😀".toCharArray()
        var encodedBytes: ByteArray? = null
        val failure = IllegalStateException("synthetic combined-buffer allocation failure")

        val thrown = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            withWipedPasswordBuffer(passwordChars) { encoded ->
                encodedBytes = encoded.array()
                withWipedCredentialBytes(
                    size = encoded.remaining() + 1,
                    allocate = { throw failure }
                ) { error("分配失败后不得进入拼接操作") }
            }
        }

        assertSame(failure, thrown)
        val wipedEncodedBytes = checkNotNull(encodedBytes)
        assertArrayEquals(ByteArray(wipedEncodedBytes.size), wipedEncodedBytes)
        assertArrayEquals("分配失败也不得改写借用输入", "fake-password-😀".toCharArray(), passwordChars)
    }

    @Test
    fun `并发构造认证头时缓冲互不串扰且借用口令保持不变`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 16).map { index ->
                executor.submit<Pair<String, String>> {
                    val password = "fake-password-$index-密😀"
                    val passwordChars = password.toCharArray()
                    val original = passwordChars.copyOf()
                    val header = WebDavAuthHeader.build("fake-user-$index", passwordChars)
                    assertArrayEquals(original, passwordChars)
                    header to "fake-user-$index:$password"
                }
            }

            futures.forEach { future ->
                val (header, expectedCredentials) = future.get()
                val decoded = Base64.getDecoder()
                    .decode(header.removePrefix("Basic "))
                    .toString(Charsets.UTF_8)
                assertEquals(expectedCredentials, decoded)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `认证载体独占复制口令且容忍调用方构造后立即擦除`() {
        val borrowed = "fake-pass-中文-😀".toCharArray()
        val original = borrowed.copyOf()

        val credentials = WebDavAuthCredentials("虚构用户", borrowed)

        val retained = checkNotNull(credentials.retainedPasswordBufferForTest())
        assertNotSame("载体不得驻留调用方借用的数组", borrowed, retained)
        assertArrayEquals("载体副本应保有原口令", original, retained)

        // 既有收口：Provider 构造返回后调用方立即擦除借用数组，载体仍须能按请求出凭证
        borrowed.fill('0')
        val header = credentials.authorizationHeader()
        assertEquals("虚构用户:fake-pass-中文-😀", decodeBasicCredentials(header))
        assertEquals("按需生成不改变凭据内容", header, credentials.authorizationHeader())
    }

    @Test
    fun `失效后副本确定性清零并拒绝继续构造认证头`() {
        val credentials = WebDavAuthCredentials("fake-user", "fake-pass-密😀".toCharArray())
        val retained = checkNotNull(credentials.retainedPasswordBufferForTest())

        credentials.invalidate()

        assertArrayEquals(
            "失效必须确定性清零独占副本，不依赖 GC",
            CharArray(retained.size) { '0' },
            retained
        )
        assertNull("失效后不再持有任何口令副本", credentials.retainedPasswordBufferForTest())
        org.junit.Assert.assertThrows(SyncException.AuthenticationError::class.java) {
            credentials.authorizationHeader()
        }

        // 幂等：重复失效与 close 都不放大失败面
        credentials.invalidate()
        credentials.close()
        assertNull(credentials.retainedPasswordBufferForTest())
    }

    @Test
    fun `并发取头与失效之间只产出完整凭据或鉴权失败`() {
        val credentials = WebDavAuthCredentials("fake-user", "fake-pass-密😀".toCharArray())
        val retained = checkNotNull(credentials.retainedPasswordBufferForTest())
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val firstHeader = CountDownLatch(1)
        try {
            val readers = (0 until 6).map {
                executor.submit<Int> {
                    start.await()
                    var validHeaders = 0
                    repeat(200) {
                        try {
                            assertEquals(
                                "fake-user:fake-pass-密😀",
                                decodeBasicCredentials(credentials.authorizationHeader())
                            )
                            validHeaders++
                            firstHeader.countDown()
                        } catch (_: SyncException.AuthenticationError) {
                            // 已失效后拒绝构造属预期结果
                        }
                    }
                    validHeaders
                }
            }
            val invalidator = executor.submit {
                start.await()
                // 先确保失效前确有请求在取头，失效才与取头形成真实交错
                firstHeader.await()
                credentials.invalidate()
            }
            start.countDown()

            readers.forEach { future -> future.get() }
            invalidator.get()

            assertArrayEquals("并发失效不得留下明文残留", CharArray(retained.size) { '0' }, retained)
            org.junit.Assert.assertThrows(SyncException.AuthenticationError::class.java) {
                credentials.authorizationHeader()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun decodeBasicCredentials(header: String): String {
        assertTrue("认证头必须以 Basic 前缀交付: $header", header.startsWith("Basic "))
        return Base64.getDecoder()
            .decode(header.removePrefix("Basic "))
            .toString(Charsets.UTF_8)
    }
}
