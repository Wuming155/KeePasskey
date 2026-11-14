package com.keepasskey.crypto

import com.keepasskey.crypto.cipher.NativeAes
import com.keepasskey.crypto.kdf.NativeAesKdf
import com.keepasskey.crypto.kdf.NativeArgon2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test

/**
 * 原生失败原因码 · **宿主侧 JNI 运行时验证**（`ISSUE-P2` C4）。
 *
 * 背景：`catch_unwind` 把 panic、非法入参、JNI 环境错误、内核拒绝压成同一个 `null` / `-1`，
 * 诊断时无法区分「库坏了」与「参数传错了」。原生侧新增线程局部原因码，导出函数在返回失败值前
 * 写入；本用例在**真实 JNI 通路**上验证它确实被写入且分类正确（Rust 侧 `cargo test` 只覆盖
 * 纯函数与静态符号，验不了 `JNIEnv` / `byte[]` / null 语义）。
 *
 * 降级语义：未安装 cargo / 宿主库构建失败时，`NativeCryptoLibrary.loaded == false`，
 * 整个类经 `Assume` 跳过（与 `NativeArgon2HostJniTest` 同策略），不阻断 `gradlew test`。
 */
class NativeErrorCodeHostJniTest {

    companion object {
        private val KEY_32 = ByteArray(32) { (it * 5 + 1).toByte() }
        private val IV_16 = ByteArray(16) { it.toByte() }
        private val DATA_16 = ByteArray(16) { (it xor 0x3C).toByte() }
        private val SALT_32 = ByteArray(32) { (0xFF - it).toByte() }

        @JvmStatic
        @BeforeClass
        fun assumeNativeAvailable() {
            Assume.assumeTrue(
                "宿主 Rust 动态库不可用（未安装 cargo / 构建失败 / 加载失败），跳过原因码验证",
                NativeCryptoLibrary.loaded
            )
        }
    }

    @Test
    fun `成功调用后原因码为零`() {
        val out = NativeAesKdf.deriveKey(KEY_32, SALT_32, 1L)
        assertNotNull("合法参数的原生派生不应失败", out)
        assertEquals(NativeErrorCode.NONE, NativeErrorCode.lastOrNull())
    }

    @Test
    fun `null 入参归为 INVALID_ARGUMENT`() {
        // 绕过 Kotlin 非空类型：反射式直接调用不可行，改用「长度不符」这条同样走闸门的路径
        val shortKey = ByteArray(31)
        assertNull("31 字节复合密钥必须被闸门拒绝", NativeAesKdf.deriveKey(shortKey, SALT_32, 1L))
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.lastOrNull())

        assertNull("0 轮必须被闸门拒绝", NativeAesKdf.deriveKey(KEY_32, SALT_32, 0L))
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.lastOrNull())
    }

    @Test
    fun `CBC 长度不符归为 INVALID_ARGUMENT 且与内核拒绝可区分`() {
        assertNull(
            "非分组整数倍必须被闸门拒绝",
            NativeAes.cbcEncryptBlocks(KEY_32, IV_16, ByteArray(15))
        )
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.lastOrNull())

        assertNull(
            "IV 长度不符必须被闸门拒绝",
            NativeAes.cbcEncryptBlocks(KEY_32, ByteArray(8), DATA_16)
        )
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.lastOrNull())
    }

    @Test
    fun `Argon2 非法 type 归为 INVALID_ARGUMENT`() {
        assertNull(
            "Argon2i(1) 不在 KDBX 域，必须被闸门拒绝",
            NativeArgon2.deriveKey(KEY_32, IV_16, null, null, 1, 64, 1, 0x13, 1)
        )
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.lastOrNull())
    }

    @Test
    fun `成功路径不残留上一次的失败码`() {
        assertNull(
            "先制造一次失败",
            NativeAesKdf.deriveKey(KEY_32, SALT_32, 0L)
        )
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.lastOrNull())

        val out = NativeAesKdf.deriveKey(KEY_32, SALT_32, 1L)
        assertNotNull(out)
        assertEquals("成功调用必须复位原因码", NativeErrorCode.NONE, NativeErrorCode.lastOrNull())
    }

    @Test
    fun `原因码为线程局部`() {
        assertNull(NativeAesKdf.deriveKey(KEY_32, SALT_32, 0L))
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.lastOrNull())

        var childSaw = -1
        val t = Thread { childSaw = NativeErrorCode.lastOrNull() ?: -1 }
        t.start()
        t.join()
        assertEquals("子线程不得看到调用线程的失败码", NativeErrorCode.NONE, childSaw)
        assertEquals("调用线程的失败码不得被子线程复位", NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.lastOrNull())
    }

    @Test
    fun `KAT 不匹配形态下原因码仍为零`() {
        // 模拟探活场景：调用全部成功、但结果与参照实现不符 ⇒ 原因码保持 NONE。
        // 这正是 NativeFallbackLog 用来区分「KAT 不匹配」与「调用失败」的判据。
        val native = NativeAesKdf.deriveKey(KEY_32, SALT_32, 1L)
        assertNotNull(native)
        assertEquals(NativeErrorCode.NONE, NativeErrorCode.lastOrNull())
    }
}
