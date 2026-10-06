package com.keepasskey.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ISSUE-P3-392 / ISSUE-P2-499：原生探活失败回落的**一次性可观测**登记。
 *
 * 判据：进程内只记一次；第二次回落不再改写 lastLoggedKernel；resetForTest 可清零闸门供多用例隔离。
 * ISSUE-P2-499 由 KDF 专有泛化为七内核共用（`NativeKdfFallbackLog` → [NativeFallbackLog]），
 * 本文件由原 `NativeKdfFallbackLogTest` 改名并以新增用例覆盖五个新内核名，**未删除任何原用例**。
 */
class NativeFallbackLogTest {

    /** ISSUE-P2-499 新增的五个内核名（与各回落决策点传入的字符串逐字一致）。 */
    private val newKernels = listOf("AES-CBC", "Twofish", "ChaCha20", "口令强度", "Passkey 签名")

    @Before
    fun reset() {
        NativeFallbackLog.resetForTest()
    }

    @Test
    fun `首次回落记录内核名`() {
        assertNull(NativeFallbackLog.lastLoggedKernel)
        NativeFallbackLog.noteFallbackOnce("Argon2")
        assertEquals("Argon2", NativeFallbackLog.lastLoggedKernel)
    }

    @Test
    fun `第二次回落不覆盖首次记录`() {
        NativeFallbackLog.noteFallbackOnce("Argon2")
        NativeFallbackLog.noteFallbackOnce("AES-KDF")
        assertEquals("进程内只记一次，后续回落不得覆盖", "Argon2", NativeFallbackLog.lastLoggedKernel)
    }

    @Test
    fun `reset后可再次记录`() {
        NativeFallbackLog.noteFallbackOnce("Argon2")
        NativeFallbackLog.resetForTest()
        assertNull(NativeFallbackLog.lastLoggedKernel)
        NativeFallbackLog.noteFallbackOnce("AES-KDF")
        assertEquals("AES-KDF", NativeFallbackLog.lastLoggedKernel)
    }

    /**
     * ISSUE-P2-499 AC②：新增五内核共用**同一**一次性闸门——首个回落内核名即唯一登记项，
     * 其余内核（含新内核之间互不覆盖）不再重复刷屏。
     */
    @Test
    fun `新增五内核回落同样被一次性闸门收敛`() {
        val first = newKernels.first()
        NativeFallbackLog.noteFallbackOnce(first)
        assertEquals(first, NativeFallbackLog.lastLoggedKernel)
        for (kernel in newKernels.drop(1)) {
            NativeFallbackLog.noteFallbackOnce(kernel)
            assertEquals(
                "进程内只记一次，新内核 [$kernel] 回落不得覆盖首次记录",
                first,
                NativeFallbackLog.lastLoggedKernel
            )
        }
    }

    /** ISSUE-P2-499 AC②：reset 后新内核名同样可再次进入登记通道。 */
    @Test
    fun `reset后新内核可再次记录`() {
        NativeFallbackLog.noteFallbackOnce(newKernels.first())
        NativeFallbackLog.resetForTest()
        assertNull(NativeFallbackLog.lastLoggedKernel)
        NativeFallbackLog.noteFallbackOnce(newKernels.last())
        assertEquals(newKernels.last(), NativeFallbackLog.lastLoggedKernel)
    }

    /**
     * ISSUE-P2-499 AC①：登记载荷**只有内核名**——`lastLoggedKernel` 恒等于传入值本身
     * （接口只收一个字符串，结构上无 KDF 参数 / 密钥材料通道）。
     */
    @Test
    fun `登记载荷恒等于传入内核名`() {
        for (kernel in listOf("Argon2", "AES-KDF") + newKernels) {
            NativeFallbackLog.resetForTest()
            NativeFallbackLog.noteFallbackOnce(kernel)
            assertEquals(kernel, NativeFallbackLog.lastLoggedKernel)
        }
    }
}
