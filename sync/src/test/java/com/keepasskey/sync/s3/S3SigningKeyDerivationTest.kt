package com.keepasskey.sync.s3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `ISSUE-P3-657`：SigV4 签名密钥级联派生的**异常出口清理**。
 *
 * 缺陷形态：kDate / kRegion / kService 在第一个 try 内依次派生，其 finally 只清 kSecret；
 * 清前三级的第二个 try 只有「四级全部成功」后才会进入。中间任一次 HMAC 抛异常，
 * 已分配的派生数组就跳过清理等待 GC（真实 HMAC 异常 / Provider 资源错误才触发，
 * 故正常签名向量覆盖不到）。
 *
 * 验证手段：向派生链注入受控故障替身，保留每次 HMAC 的**返回值引用**，在第 2/3/4 步
 * 依次抛异常，断言此前已分配的数组全部被填零；成功路径断言三级中间量被填零而
 * 最终 signingKey 未被填零（所有权移交给调用方）。
 */
class S3SigningKeyDerivationTest {

    private val kSecret = "AWS4secret".toByteArray(Charsets.UTF_8)

    @Test
    fun `第2步失败时已分配的 kDate 被擦除`() {
        val allocated = mutableListOf<ByteArray>()
        var call = 0
        val failure = IllegalStateException("hmac failure at step 2")

        val thrown = runCatching {
            signer().deriveSigningKeyChain(kSecret.copyOf(), "20261006", "us-east-1", "s3") { key, _ ->
                call++
                if (call == 2) throw failure
                ByteArray(32) { (0x11 * call).toByte() }.also { allocated += it }
            }
        }.exceptionOrNull()

        assertTrue("受控异常必须如实上浮，不得被清理逻辑吞掉", thrown === failure)
        assertEquals("第 1 步只分配了 kDate", 1, allocated.size)
        assertWiped(allocated[0], "kDate")
    }

    @Test
    fun `第3步失败时 kDate 与 kRegion 都被擦除`() {
        val allocated = observeFailure(3)
        assertEquals(2, allocated.size)
        allocated.forEachIndexed { index, array -> assertWiped(array, "第 ${index + 1} 步派生量") }
    }

    @Test
    fun `第4步失败时三级中间量全部被擦除`() {
        val allocated = observeFailure(4)
        assertEquals(3, allocated.size)
        allocated.forEachIndexed { index, array -> assertWiped(array, "第 ${index + 1} 步派生量") }
    }

    @Test
    fun `成功路径擦除中间量且不擦最终 signingKey`() {
        val allocated = mutableListOf<ByteArray>()
        var expectedSigningKey: ByteArray? = null
        var call = 0

        val signingKey = signer().deriveSigningKeyChain(kSecret.copyOf(), "20261006", "us-east-1", "s3") { _, _ ->
            call++
            ByteArray(32) { call.toByte() }.also {
                allocated += it
                // 第 4 次是最终 signingKey：先留独立副本，避免用同一实例自证
                if (call == 4) expectedSigningKey = it.copyOf()
            }
        }

        assertEquals("四级派生固定 4 次调用", 4, call)
        assertEquals(4, allocated.size)
        (0..2).forEach { index -> assertWiped(allocated[index], "中间派生量 ${index + 1}") }
        assertArrayEquals(
            "最终 signingKey 的所有权移交调用方，本方法不得擦除",
            requireNotNull(expectedSigningKey),
            signingKey
        )
        assertTrue("signingKey 不应被清零", signingKey.any { it != 0.toByte() })
    }

    @Test
    fun `未分配数组不访问且空 kSecret 不导致崩溃`() {
        val thrown = runCatching {
            signer().deriveSigningKeyChain(ByteArray(0), "20261006", "us-east-1", "s3") { _, _ ->
                throw IllegalStateException("provider unavailable")
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
    }

    /** 在第 [failAt] 步抛异常，返回此前已分配的数组引用 */
    private fun observeFailure(failAt: Int): List<ByteArray> {
        val allocated = mutableListOf<ByteArray>()
        var call = 0
        runCatching {
            signer().deriveSigningKeyChain(kSecret.copyOf(), "20261006", "us-east-1", "s3") { _, _ ->
                call++
                if (call == failAt) throw IllegalStateException("hmac failure at step $failAt")
                ByteArray(32) { (0x11 * call).toByte() }.also { allocated += it }
            }
        }.onSuccess { fail("第 $failAt 步注入的异常必须上浮") }
        return allocated
    }

    private fun assertWiped(array: ByteArray, label: String) {
        assertTrue("$label 在异常出口必须就地填零，实际: ${array.toList()}", array.all { it == 0.toByte() })
    }

    private fun signer() = S3RequestSigner(
        accessKeyId = "AKIDEXAMPLE".toCharArray(),
        secretAccessKey = "secret".toCharArray(),
        region = "us-east-1"
    )
}
