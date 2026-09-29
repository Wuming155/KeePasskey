package com.keepasskey.crypto.kdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-392：原生 KDF 探活失败回落的**一次性可观测**登记。
 *
 * 判据：进程内只记一次；第二次回落不再改写 lastLoggedKernel；
 * resetForTest 可清零闸门供多用例隔离。
 */
class NativeKdfFallbackLogTest {

    @Before
    fun reset() {
        NativeKdfFallbackLog.resetForTest()
    }

    @Test
    fun `首次回落记录内核名`() {
        assertNull(NativeKdfFallbackLog.lastLoggedKernel)
        NativeKdfFallbackLog.noteFallbackOnce("Argon2")
        assertEquals("Argon2", NativeKdfFallbackLog.lastLoggedKernel)
    }

    @Test
    fun `第二次回落不覆盖首次记录`() {
        NativeKdfFallbackLog.noteFallbackOnce("Argon2")
        NativeKdfFallbackLog.noteFallbackOnce("AES-KDF")
        assertEquals("进程内只记一次，后续回落不得覆盖", "Argon2", NativeKdfFallbackLog.lastLoggedKernel)
    }

    @Test
    fun `reset后可再次记录`() {
        NativeKdfFallbackLog.noteFallbackOnce("Argon2")
        NativeKdfFallbackLog.resetForTest()
        assertNull(NativeKdfFallbackLog.lastLoggedKernel)
        NativeKdfFallbackLog.noteFallbackOnce("AES-KDF")
        assertEquals("AES-KDF", NativeKdfFallbackLog.lastLoggedKernel)
    }
}
