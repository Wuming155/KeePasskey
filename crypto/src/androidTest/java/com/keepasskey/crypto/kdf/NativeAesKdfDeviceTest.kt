package com.keepasskey.crypto.kdf

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AES-KDF 原生内核 · **设备侧真机验证**。
 *
 * 背景：`crypto/src/main/rust/src/aes_kdf.rs` 是 KDBX 解锁热路径上的**串行链式** KDF
 * （默认轮数 `KdfBenchmark.DEFAULT_AES_ROUNDS = 6_000_000`），其 JNI 绑定 [NativeAesKdf]
 * 此前**从未在真机上执行过**：宿主侧的 `AesKdfNativeParityTest` 全部带 `Assume`
 * （宿主库由 `cargoHostBuild` 用桌面工具链构建，缺失即整类跳过），
 * 而设备侧的 `.so` 由 `:crypto:cargoNdkBuild` 用 NDK 交叉编译为四个 ABI——
 * **两套二进制不是同一产物**。§147 的教训正是「宿主 100% 绿、仅真机失败」，
 * 故本用例与 `AesNativeDeviceTest` / `ChaCha20NativeDeviceTest` / `NativeArgon2InstrumentedTest`
 * 同等对待：探活失败一律硬失败，不使用 `Assume`。
 *
 * 本用例钉死的四件事：
 * 1. 冻结已知答案向量（Python `cryptography` 独立复算，与 `aes_kdf_tests.rs` 同源同值）
 *    在**真机 `.so`** 上逐字节复现；
 * 2. 原生 ⇄ **平台 JCE**（Android 的 Conscrypt / 平台 BC，与桌面 JVM 的 SunJCE 不同提供者）等价；
 * 3. FFI 闸门（长度、轮数上下界、**null 实参**）在真机上归一为 `null` 而非进程级 SIGSEGV；
 * 4. ART 的 JNI 实现下重复调用不破坏返回数组的独立性（结果别名到已释放缓冲会表现为
 *    「首调正确、后续垃圾」，只在真机上暴露）。
 *
 * 敏感数据：全部输入为**公开合成向量**（非真实凭据），仍按项目铁律以 `ByteArray` 承载并用后清零。
 */
@RunWith(AndroidJUnit4::class)
class NativeAesKdfDeviceTest {

    /** 冻结向量输入：seed = 0x00..0x1f（作 AES 密钥），compositeKey = 0x20..0x3f，rounds = 4。 */
    private val frozenSeed = ByteArray(32) { it.toByte() }
    private val frozenCompositeKey = ByteArray(32) { (it + 32).toByte() }

    @Test
    fun 原生内核探活_真机必须可用() {
        assertTrue(
            "真机上 NativeAesKdf.available 必须为 true（探活含与 AesKdfJce 的逐字节比对；" +
                "false = NDK 产物未加载 / 符号缺失 / 内核异常）",
            NativeAesKdf.available
        )
    }

    @Test
    fun 冻结已知答案向量在真机复现_原生与平台JCE与引擎三路径同值() {
        val expected = hexToBytes(FROZEN_EXPECTED_HEX)
        val native = NativeAesKdf.derive(frozenCompositeKey, frozenSeed, FROZEN_ROUNDS)
        val jce = AesKdfJce.transform(frozenCompositeKey, frozenSeed, FROZEN_ROUNDS)
        val engine = AesKdfEngine()
            .transform(frozenCompositeKey, KdfParameters.Aes(seed = frozenSeed, rounds = FROZEN_ROUNDS))
        try {
            assertArrayEquals(
                "真机原生内核必须复现独立复算的已知答案（不匹配 = NDK 产物与宿主产物行为不一致）",
                expected, native
            )
            assertArrayEquals("平台 JCE 必须命中同一已知答案", expected, jce)
            assertArrayEquals("生产引擎分路径必须命中同一已知答案", expected, engine)
            assertEquals("派生输出恒为 32 字节", NativeAesKdf.KEY_LEN, native.size)
        } finally {
            native.fill(0)
            jce.fill(0)
            engine.fill(0)
            expected.fill(0)
        }
    }

    @Test
    fun 原生与平台JCE在多组轮数下逐字节等价() {
        for (rounds in listOf(1L, 2L, 7L, 1_000L, 50_000L)) {
            for (salt in 1..3) {
                val compositeKey = pattern(32, salt)
                val seed = pattern(32, salt + 10)
                val native = NativeAesKdf.derive(compositeKey, seed, rounds)
                val reference = AesKdfJce.transform(compositeKey, seed, rounds)
                try {
                    assertArrayEquals(
                        "rounds=$rounds salt=$salt：真机上原生与平台 JCE 不一致",
                        reference, native
                    )
                } finally {
                    native.fill(0)
                    reference.fill(0)
                    compositeKey.fill(0)
                    seed.fill(0)
                }
            }
        }
    }

    @Test
    fun FFI闸门在真机归一为null_含null实参() {
        val ok = ByteArray(NativeAesKdf.KEY_LEN)

        // 长度非法：不得以截断 / 补齐的方式继续运算
        assertNull(NativeAesKdf.deriveKey(ByteArray(31), ok, 1L))
        assertNull(NativeAesKdf.deriveKey(ok, ByteArray(31), 1L))
        assertNull(NativeAesKdf.deriveKey(ByteArray(33), ok, 1L))
        assertNull(NativeAesKdf.deriveKey(ok, ByteArray(0), 1L))
        // 轮数下界与负数（有符号闸门必须先于 `rounds as u64` 窄化）
        assertNull(NativeAesKdf.deriveKey(ok, ok, 0L))
        assertNull(NativeAesKdf.deriveKey(ok, ok, -1L))
        assertNull(NativeAesKdf.deriveKey(ok, ok, Long.MIN_VALUE))
        // 轮数上界：`MAX_ROUNDS = 1 shl 28` 与数据库侧 `AES_KDF_MAX_ROUNDS` 同值。
        // 恰好越界即须拒绝——这是「恶意 .kdbx 以超大轮数把设备打成假死」的 DoS 闸门，
        // 拒绝路径耗时可忽略（未进入循环），故真机可安全实跑。
        assertNull(NativeAesKdf.deriveKey(ok, ok, AES_KDF_MAX_ROUNDS + 1L))
        assertNull(NativeAesKdf.deriveKey(ok, ok, Long.MAX_VALUE))
        // 合法边界
        assertNotNull(NativeAesKdf.deriveKey(ok, ok, 1L))

        // null 实参：Rust 侧 `is_null()` 闸门若被摘掉，真机上是 SIGSEGV（整个 instrumented
        // 进程被杀）而非测试报红——Kotlin 类型系统挡不住反射，故只能在此层钉住。
        val gate = NativeAesKdf::class.java.getDeclaredMethod(
            "deriveKey",
            ByteArray::class.java,
            ByteArray::class.java,
            Long::class.javaPrimitiveType
        )
        assertNull(gate.invoke(NativeAesKdf, null, ok, 1L) as ByteArray?)
        assertNull(gate.invoke(NativeAesKdf, ok, null, 1L) as ByteArray?)
        assertNull(gate.invoke(NativeAesKdf, null, null, 1L) as ByteArray?)
    }

    @Test
    fun 真机重复调用返回数组相互独立_不被后续调用污染() {
        val compositeKey = pattern(32, 3)
        val seed = pattern(32, 13)
        val first = NativeAesKdf.derive(compositeKey, seed, PARITY_ROUNDS)
        val later = ArrayList<ByteArray>(REPEAT_CALLS)
        try {
            repeat(REPEAT_CALLS) {
                val out = NativeAesKdf.derive(compositeKey, seed, PARITY_ROUNDS)
                assertArrayEquals(
                    "第 $it 次派生与首次不一致（ART JNI 引用表 / 返回缓冲被后续调用改写）",
                    first, out
                )
                later.add(out)
            }
            // 全部在途返回数组同时存活且互不别名：末次结果必须仍等于首次
            assertArrayEquals("在途数组集合的末次结果被改写", first, later.last())
        } finally {
            first.fill(0)
            later.forEach { it.fill(0) }
            compositeKey.fill(0)
            seed.fill(0)
        }
    }

    private fun pattern(size: Int, salt: Int): ByteArray =
        ByteArray(size) { ((it * salt + 7) and 0xFF).toByte() }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {

        /** 与 `AesKdfNativeParityTest` / `aes_kdf_tests.rs` 同源的冻结期望输出。 */
        private const val FROZEN_EXPECTED_HEX =
            "f836155ae7cb1d120da836de66f478ec1dbf042d041d9ca50b49f400d398eff5"

        private const val FROZEN_ROUNDS = 4L

        /** 轮数上界（Rust `aes_kdf::MAX_ROUNDS` 与 `KdbxKdfParameterCodec.AES_KDF_MAX_ROUNDS` 同值）。 */
        private const val AES_KDF_MAX_ROUNDS = 1L shl 28

        /** 重复调用稳定性用例的轮数与次数（真机上单次耗时可控，不触发满载）。 */
        private const val PARITY_ROUNDS = 20_000L
        private const val REPEAT_CALLS = 200

        /**
         * 先求值一次探活：`deriveKey` 是 `external fun`，而 `System.loadLibrary` 只写在
         * [NativeAesKdf.available] 的 `by lazy` 初始化器内——JUnit4 方法顺序不确定，
         * 未在探活前直接调用外部函数会先撞上 `UnsatisfiedLinkError`（`NativeArgon2InstrumentedTest`
         * 记载的同一条坑）。不可用时**硬失败**并给出可定位诊断。
         */
        @JvmStatic
        @BeforeClass
        fun requireNativeLibraryAvailable() {
            if (!NativeAesKdf.available) {
                fail(
                    "NativeAesKdf.available == false：设备侧 .so 未在运行时生效\n" +
                        "  Build.MODEL=${Build.MODEL}, SUPPORTED_ABIS=${Build.SUPPORTED_ABIS.joinToString()}\n" +
                        "  请核对 :crypto:cargoNdkBuild 产物与 aes_kdf 导出符号"
                )
            }
        }
    }
}
