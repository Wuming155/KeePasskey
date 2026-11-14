package com.keepasskey.crypto

import com.keepasskey.core.log.AppLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P2` C4：原生回落按内核各记一次，且原因可区分「so 缺失」与「KAT/运行期失败」。
 *
 * 旧实现是进程级单闸门，先回落的内核遮蔽后续内核；本文件锁定新的按内核语义。
 */
class NativeFallbackLogTest {

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        NativeFallbackLog.resetForTest()
        lines.clear()
        AppLog.diagnosticLineSink = { _, tag, message ->
            if (tag == "NativeFallback") lines += message
        }
    }

    @After
    fun tearDown() {
        AppLog.diagnosticLineSink = null
        NativeFallbackLog.resetForTest()
    }

    @Test
    fun `不同内核各输出一行且同一内核不重复`() {
        NativeFallbackLog.noteFallbackOnce("AES-CBC")
        NativeFallbackLog.noteFallbackOnce("Argon2")
        NativeFallbackLog.noteFallbackOnce("AES-CBC")

        assertEquals(setOf("AES-CBC", "Argon2"), NativeFallbackLog.loggedKernels())
        assertEquals("每个内核首次回落各一行", 2, lines.size)
        assertTrue(lines[0].contains("AES-CBC"))
        assertTrue(lines[1].contains("Argon2"))
    }

    @Test
    fun `so 缺失与 KAT 失败使用不同原因枚举`() {
        NativeFallbackLog.libraryLoadedProbe = { false }
        NativeFallbackLog.noteFallbackOnce("AES-CBC")

        NativeFallbackLog.resetForTest()
        NativeFallbackLog.libraryLoadedProbe = { true }
        NativeFallbackLog.errorCodeProbe = { NativeErrorCode.NONE }
        NativeFallbackLog.noteFallbackOnce("Argon2")

        assertEquals(2, lines.size)
        assertTrue("so 缺失须标 LIBRARY_UNAVAILABLE：${lines[0]}", lines[0].contains("reason=LIBRARY_UNAVAILABLE"))
        assertTrue("自测失败须标 KAT_MISMATCH_OR_ERROR：${lines[1]}", lines[1].contains("reason=KAT_MISMATCH_OR_ERROR"))
        // 库未加载时不做原因码查询（调用只会再抛一次 UnsatisfiedLinkError）
        assertFalse("so 缺失行不得带 code=：${lines[0]}", lines[0].contains("code="))
        // 库已加载：原因码可读，NONE 即「调用成功但结果不符」，正是真正的 KAT 不匹配
        assertTrue("库已加载须带 code=：${lines[1]}", lines[1].contains("code=NONE"))
    }

    @Test
    fun `库已加载时失败原因码可区分入参非法与 panic`() {
        NativeFallbackLog.libraryLoadedProbe = { true }

        NativeFallbackLog.errorCodeProbe = { NativeErrorCode.INVALID_ARGUMENT }
        NativeFallbackLog.noteFallbackOnce("AES-CBC")

        NativeFallbackLog.resetForTest()
        NativeFallbackLog.libraryLoadedProbe = { true }
        NativeFallbackLog.errorCodeProbe = { NativeErrorCode.PANIC }
        NativeFallbackLog.noteFallbackOnce("Twofish-CBC")

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("code=INVALID_ARGUMENT"))
        assertTrue(lines[1].contains("code=PANIC"))
    }

    @Test
    fun `原因码不可查询时不写 code 且仍输出 reason`() {
        NativeFallbackLog.libraryLoadedProbe = { true }
        NativeFallbackLog.errorCodeProbe = { null }

        NativeFallbackLog.noteFallbackOnce("ChaCha20")

        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("reason=KAT_MISMATCH_OR_ERROR"))
        assertFalse("不可查询时不得凭空给码：${lines[0]}", lines[0].contains("code="))
    }

    @Test
    fun `原因码枚举名稳定`() {
        assertEquals("NONE", NativeErrorCode.nameOf(NativeErrorCode.NONE))
        assertEquals("INVALID_ARGUMENT", NativeErrorCode.nameOf(NativeErrorCode.INVALID_ARGUMENT))
        assertEquals("JNI", NativeErrorCode.nameOf(NativeErrorCode.JNI))
        assertEquals("KERNEL", NativeErrorCode.nameOf(NativeErrorCode.KERNEL))
        assertEquals("PANIC", NativeErrorCode.nameOf(NativeErrorCode.PANIC))
        assertEquals("UNKNOWN(99)", NativeErrorCode.nameOf(99))
    }

    @Test
    fun `模拟 UnsatisfiedLinkError 与 KAT 不匹配各输出一行`() {
        // 关闭条件①的两个形态：库不可用（UnsatisfiedLinkError 被 NativeCryptoLibrary 归一为
        // loaded=false）与库可用但自测不符（调用都成功、结果与参照不一致 ⇒ code=NONE）。
        NativeFallbackLog.libraryLoadedProbe = { false }
        NativeFallbackLog.noteFallbackOnce("AES-KDF")

        NativeFallbackLog.resetForTest()
        NativeFallbackLog.libraryLoadedProbe = { true }
        NativeFallbackLog.errorCodeProbe = { NativeErrorCode.NONE }
        NativeFallbackLog.noteFallbackOnce("AES-KDF")

        assertEquals("同一内核两次不同原因各记一行", 2, lines.size)
        assertTrue(
            "UnsatisfiedLinkError 形态须为 LIBRARY_UNAVAILABLE：${lines[0]}",
            lines[0].contains("AES-KDF") && lines[0].contains("reason=LIBRARY_UNAVAILABLE")
        )
        assertTrue(
            "KAT 不匹配形态须为 KAT_MISMATCH_OR_ERROR + code=NONE：${lines[1]}",
            lines[1].contains("AES-KDF") &&
                lines[1].contains("reason=KAT_MISMATCH_OR_ERROR") &&
                lines[1].contains("code=NONE")
        )
    }

    @Test
    fun `reset 后同一内核可再次登记`() {
        NativeFallbackLog.noteFallbackOnce("AES-KDF")
        assertEquals("AES-KDF", NativeFallbackLog.lastLoggedKernel)

        NativeFallbackLog.resetForTest()

        assertEquals(emptySet<String>(), NativeFallbackLog.loggedKernels())
        NativeFallbackLog.noteFallbackOnce("AES-KDF")
        assertEquals("AES-KDF", NativeFallbackLog.lastLoggedKernel)
    }
}
