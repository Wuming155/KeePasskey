package com.keepasskey.crypto.passkey

import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * `ISSUE-P3-654`：PRF 秘密的**中间工作数组**必须确定性擦除，且交付物不得被误擦。
 *
 * ## 为什么必须直接观察数组
 *
 * `PasskeyPrf` 的中间缓冲（`readUtf8()` 的 UTF-8 副本、去空白工作区、Base64 解码结果、
 * 域分隔盐、生成侧的原始随机字节与 Base64 编码副本）生命周期只有几行。只断言「最终
 * ProtectedString / HMAC 输出正确」无法区分两种实现：**确实在 finally 清零**，与
 * **什么都不做、等 GC**——后者正是敏感缓冲所有权契约 R3 明令禁止的形态。
 *
 * ## 口径（R1/R2）
 *
 * - 观察回调捕获的每个数组引用，在方法返回或抛出后都必须**逐字节为 0**（O 类就地擦除）；
 * - **负例**：`computeValue` 的返回值是 R2 的「转移」，调用方取走的内容必须仍是有效
 *   PRF 输出（不得被本函数清零）——本类以「返回值非全零且与独立复算一致」锁定；
 * - 生成侧同理：`newSecretProtected` 交出的 `ProtectedString` 必须仍能还原出同一秘密
 *   （证明擦除 `encoded` 未连带毁掉驻留值）。
 *
 * 观察回调同时记录**调用当时的快照**，用以证明用例真的观察到了承载明文的缓冲（非空跑）。
 */
class PasskeyPrfBufferWipeTest {

    private val observed = mutableListOf<ByteArray>()
    private val snapshotAtObservation = mutableListOf<ByteArray>()

    private val observer: (ByteArray) -> Unit = { buffer ->
        observed += buffer
        snapshotAtObservation += buffer.copyOf()
    }

    private fun assertAllObservedWiped(label: String, expectSensitiveContent: Boolean = true) {
        assertTrue("[$label] 用例必须真的观察到中间缓冲（否则断言空转）", observed.isNotEmpty())
        if (expectSensitiveContent) {
            assertTrue(
                "[$label] 观察到的缓冲中至少有一个原本承载非零内容",
                snapshotAtObservation.any { snapshot -> snapshot.any { it != 0.toByte() } }
            )
        }
        observed.forEachIndexed { index, buffer ->
            assertTrue(
                "[$label] 第 ${index + 1} 个中间缓冲（size=${buffer.size}）在方法返回后必须逐字节清零，" +
                    "实际仍含非零字节 ${buffer.count { it != 0.toByte() }} 个",
                buffer.all { it == 0.toByte() }
            )
        }
    }

    // ==================== 生成侧 ====================

    @Test
    fun `生成新秘密时原始随机字节与Base64编码副本在返回后清零`() {
        val protected = PasskeyPrf.newSecretProtectedObserved(observer)

        assertAllObservedWiped("newSecretProtected")

        // 交付物所有权（R2 转移）：驻留值必须仍可还原，未被观察面误擦
        val restored = PasskeyPrf.secretBytesForTest(protected)
        try {
            assertEquals(PasskeyPrf.SECRET_BYTES, restored.size)
            assertTrue("交出的 ProtectedString 必须仍承载秘密（擦除不得越界到交付物）", restored.any { it != 0.toByte() })
        } finally {
            restored.fill(0)
        }
    }

    @Test
    fun `两次生成的秘密不同且各自交出的驻留值互不影响`() {
        val first = PasskeyPrf.newSecretProtected()
        val second = PasskeyPrf.newSecretProtected()

        val firstBytes = PasskeyPrf.secretBytesForTest(first)
        val secondBytes = PasskeyPrf.secretBytesForTest(second)
        try {
            assertNotSame(first, second)
            assertFalse("随机生成不得退化为常量", firstBytes.contentEquals(secondBytes))
        } finally {
            firstBytes.fill(0)
            secondBytes.fill(0)
        }
    }

    // ==================== 计算侧：正常路径 ====================

    @Test
    fun `计算PRF值时文本副本_去空白工作区_解码结果与盐在返回后全部清零`() {
        val secretBytes = ByteArray(PasskeyPrf.SECRET_BYTES) { (it * 7 + 3).toByte() }
        val secret = PasskeyPrf.secretFromBytesForTest(secretBytes)
        try {
            val output = PasskeyPrf.computeValueObserved(secret, "user-salt".toByteArray(), observer)
            try {
                assertAllObservedWiped("computeValue 正常路径")

                // 负例：返回值是 R2 的「转移」——必须仍是有效 PRF 输出，不得被清零
                assertTrue("返回值所有权已转移，不得在本函数内清零", output.any { it != 0.toByte() })
                assertEquals(32, output.size)
                val expected = PasskeyPrf.computeValue(secret, "user-salt".toByteArray())
                try {
                    assertArrayEquals("观察面不得改变 PRF 取值", expected, output)
                } finally {
                    expected.fill(0)
                }
            } finally {
                output.fill(0)
            }
        } finally {
            secretBytes.fill(0)
            secret.close()
        }
    }

    @Test
    fun `秘密文本含空白时缩短复制后的原工作区与最终副本均清零`() {
        val raw = ByteArray(PasskeyPrf.SECRET_BYTES) { (it + 11).toByte() }
        val base64 = Base64.getEncoder().encodeToString(raw)
        // 每 8 字符插入一个换行 + 空格：走 stripWhitespace 的「缩短复制」分支
        val spaced = base64.chunked(8).joinToString("\n ")
        assertTrue("前置：秘密文本必须真的含空白（否则走的是整长转移分支）", spaced.length > base64.length)

        val secret = ProtectedString(spaced, isProtected = true)
        val compact = ProtectedString(base64, isProtected = true)
        try {
            val output = PasskeyPrf.computeValueObserved(secret, ByteArray(16) { 4 }, observer)
            val expected = PasskeyPrf.computeValue(compact, ByteArray(16) { 4 })
            try {
                assertAllObservedWiped("computeValue 去空白分支")
                assertArrayEquals(
                    "去空白分支的 PRF 结果必须与紧凑文本一致",
                    expected,
                    output
                )
            } finally {
                output.fill(0)
                expected.fill(0)
            }
        } finally {
            secret.close()
            compact.close()
            raw.fill(0)
        }
    }

    // ==================== 计算侧：异常路径 ====================

    @Test
    fun `非法Base64文本抛错后文本副本与工作区仍清零`() {
        val broken = ProtectedString("!!!not-base64!!!", isProtected = true)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                PasskeyPrf.computeValueObserved(broken, ByteArray(32), observer)
            }
            assertAllObservedWiped("非法 Base64")
        } finally {
            broken.close()
        }
    }

    @Test
    fun `长度不足的秘密抛错后解码结果与中间副本仍清零`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(16) { 9 })
        val secret = ProtectedString(short, isProtected = true)
        try {
            val thrown = assertThrows(IllegalArgumentException::class.java) {
                PasskeyPrf.computeValueObserved(secret, ByteArray(32), observer)
            }
            assertTrue(
                "必须以长度为由 fail-closed，实际: ${thrown.message}",
                thrown.message.orEmpty().contains("长度")
            )
            assertAllObservedWiped("长度不足")
        } finally {
            secret.close()
        }
    }

    @Test
    fun `空秘密文本抛错后工作区仍清零`() {
        val empty = ProtectedString("", isProtected = true)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                PasskeyPrf.computeValueObserved(empty, ByteArray(32), observer)
            }
            assertTrue(
                "空文本仍须经观察面（UTF-8 副本 + 去空白工作区），否则无法证明清理域存在",
                observed.size >= 2
            )
            assertAllObservedWiped("空秘密", expectSensitiveContent = false)
        } finally {
            empty.close()
        }
    }

    // ==================== 回归：原冻结向量与既有取值不变 ====================

    @Test
    fun `观察入口不改变既有解码与取值语义`() {
        val secretBytes = ByteArray(PasskeyPrf.SECRET_BYTES) { (it * 5 + 1).toByte() }
        val secret = PasskeyPrf.secretFromBytesForTest(secretBytes)
        try {
            val withObserver = PasskeyPrf.computeValueObserved(secret, ByteArray(32) { 2 }, observer)
            val plain = PasskeyPrf.computeValue(secret, ByteArray(32) { 2 })
            try {
                assertArrayEquals(withObserver, plain)
            } finally {
                withObserver.fill(0)
                plain.fill(0)
            }
        } finally {
            secretBytes.fill(0)
            secret.close()
        }
    }
}
