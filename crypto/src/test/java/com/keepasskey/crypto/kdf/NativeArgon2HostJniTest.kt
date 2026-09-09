package com.keepasskey.crypto.kdf

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test

/**
 * Rust Argon2 原生内核 · **宿主侧 JNI 运行时验证**（Rust 秘密飞地 PoC · Batch 4）
 *
 * 为什么需要本用例：Rust 侧 `cargo test` 只能覆盖纯函数 `derive()` 与导出符号的**静态**签名，
 * 无法验证真正的 JNI 调用通路（`JNIEnv` 转换、`byte[]` 拷贝、null 语义、返回数组构造）。
 * 计划原定把该验证放 androidTest，但本机无可用设备/模拟器；Batch 4 改为**宿主侧替代方案**：
 * Gradle `cargoHostBuild` 产出宿主 cdylib（Windows `keepasskey_argon2.dll`，
 * Unix `libkeepasskey_argon2.so`），经 `-Djava.library.path` 注入单测 JVM，使
 * `NativeArgon2.available` 在桌面上同样走 Rust 原生路径 —— 于是桌面单测即获得
 * 与 androidTest 等价的运行时覆盖（风险 R6 缓解）。
 *
 * 降级语义：未安装 cargo / 宿主库构建失败时，`NativeArgon2.available == false`，
 * 整个类经 `Assume` 跳过（与 `LiveSyncServersTest` 同策略），不阻断 `gradlew test`。
 */
class NativeArgon2HostJniTest {

    /** 一条等价性对照参数（输入固定为 [PASSWORD]/[SALT]，可选 secret/AD）。 */
    private data class Args(
        val name: String,
        val type: Int,
        val version: Int,
        val iterations: Int,
        val memoryKib: Int,
        val parallelism: Int,
        val secret: ByteArray? = null,
        val ad: ByteArray? = null
    )

    companion object {
        private const val OUT_LEN = 32

        // 固定合成输入（非真实凭据）
        private val PASSWORD = ByteArray(32) { (it * 7 + 1).toByte() }
        private val SALT = ByteArray(32) { (0xFF - it).toByte() }
        private val SECRET = ByteArray(32) { (it * 3 + 5).toByte() }
        private val AD16 = ByteArray(16) { it.toByte() }
        private val AD32 = ByteArray(32) { (it xor 0x5A).toByte() }
        /** R2 探针：33B 越界（RustCrypto AssociatedData::MAX_LEN = 32） */
        private val AD33 = ByteArray(33) { it.toByte() }

        /** KDBX4 全参数域：d/id × 0x10/0x13 × 含/不含 secret + AD × p=1/4。 */
        private val EQUIVALENCE_MATRIX = listOf(
            Args("id_v13_p2", 2, 0x13, 2, 256, 2),
            Args("id_v10_p2", 2, 0x10, 2, 256, 2),
            Args("d_v13_p2", 0, 0x13, 2, 256, 2),
            Args("d_v10_p2", 0, 0x10, 2, 256, 2),
            Args("id_v13_secret", 2, 0x13, 2, 256, 2, secret = SECRET),
            Args("id_v13_ad16", 2, 0x13, 2, 256, 2, ad = AD16),
            Args("id_v13_ad32", 2, 0x13, 2, 256, 2, ad = AD32),
            Args("id_v13_secret_ad32", 2, 0x13, 3, 256, 2, secret = SECRET, ad = AD32),
            Args("d_v10_secret_ad16", 0, 0x10, 2, 512, 2, secret = SECRET, ad = AD16),
            Args("id_v13_p1", 2, 0x13, 3, 256, 1),
            Args("id_v13_p4", 2, 0x13, 2, 512, 4),
            Args("id_v13_realistic_m4096", 2, 0x13, 3, 4096, 2)
        )

        /** 性能对照档位（R1 决策闸门）：现实内存档位 × 并行度梯度。 */
        private val PERF_CASES = listOf(
            Args("argon2id_v13_m16M_p1", 2, 0x13, 2, 16 * 1024, 1),
            Args("argon2id_v13_m16M_p2", 2, 0x13, 2, 16 * 1024, 2),
            Args("argon2id_v13_m16M_p4", 2, 0x13, 2, 16 * 1024, 4)
        )

        /**
         * R1 决策闸门阈值：任一档位原生耗时不得超过 BouncyCastle 的 `NATIVE_VS_BC_MAX_RATIO` 倍。
         * 目的不是「原生必须更快」，而是**禁止迁移引入数量级性能回退**（真机对照见 ISSUE-P2-14）。
         */
        private const val NATIVE_VS_BC_MAX_RATIO = 2.0

        @JvmStatic
        @BeforeClass
        fun assumeNativeAvailable() {
            Assume.assumeTrue(
                "宿主 Rust 动态库不可用（未安装 cargo / 构建失败 / 加载失败），跳过原生运行时验证",
                NativeArgon2.available
            )
        }
    }

    // ============ 1) 跨实现等价：原生 Rust ≡ BouncyCastle（KDBX4 全参数域）============

    @Test
    fun `原生内核与 BouncyCastle 在 KDBX4 全参数域逐字节一致`() {
        for (a in EQUIVALENCE_MATRIX) {
            val native = NativeArgon2.deriveKey(
                PASSWORD, SALT, a.secret, a.ad,
                a.iterations, a.memoryKib, a.parallelism, a.version, a.type
            )
            assertNotNull("[${a.name}] 原生派生返回 null（参数合法却失败）", native)
            requireNotNull(native)
            assertEquals("[${a.name}] 原生输出长度应为 ${OUT_LEN}B", OUT_LEN, native.size)
            assertArrayEquals("[${a.name}] 原生输出与 BC 不一致", deriveBc(a), native)
            assertFalse("[${a.name}] 原生输出不应为全零", native.all { it == 0.toByte() })
        }
    }

    // ============ 2) JNI 边界：参数闸门归一为 null（对齐 C 桥失败返回 NULL）============

    @Test
    fun `非法参数在 JNI 边界归一为 null`() {
        // 合法基线必须成功（闸门不过度收紧）
        assertNotNull(
            "合法参数应派生成功",
            NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 2, 256, 2, 0x13, 2)
        )

        // KDBX 不使用 Argon2i(1)；type 越界
        assertNull(typeGate(1))
        assertNull(typeGate(3))
        // 非法 version
        assertNull(NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 2, 256, 2, 0x11, 2))
        // iterations / parallelism 下界（含负值：不得经 as u32 变巨值绕过）
        assertNull(NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 0, 256, 2, 0x13, 2))
        assertNull(NativeArgon2.deriveKey(PASSWORD, SALT, null, null, -1, 256, 2, 0x13, 2))
        assertNull(NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 2, 256, 0, 0x13, 2))
        assertNull(NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 2, 256, -1, 0x13, 2))
        // memoryKib 下界：m < 8×p（p=4 需 ≥32）
        assertNull(NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 2, 31, 4, 0x13, 2))
        assertNotNull(NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 2, 32, 4, 0x13, 2))
        // R2：AD 恰好 32B 合法，33B 越界 → fail-closed null（由 Argon2KdfEngine 路由 BC 兜底）
        assertNotNull(NativeArgon2.deriveKey(PASSWORD, SALT, null, AD32, 2, 256, 2, 0x13, 2))
        assertNull(NativeArgon2.deriveKey(PASSWORD, SALT, null, AD33, 2, 256, 2, 0x13, 2))
    }

    private fun typeGate(type: Int) =
        NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 2, 256, 2, 0x13, type)

    // ============ 3) 确定性 / 非直通 ============

    @Test
    fun `原生派生确定且 secret 与 AD 参与运算`() {
        val base = requireNotNull(
            NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 2, 256, 2, 0x13, 2)
        )
        val again = NativeArgon2.deriveKey(PASSWORD, SALT, null, null, 2, 256, 2, 0x13, 2)
        assertArrayEquals("同参数重复派生应逐字节一致", base, again)
        assertFalse("派生结果不得等于输入口令（非直通）", PASSWORD.contentEquals(base))

        val withSecret = NativeArgon2.deriveKey(PASSWORD, SALT, SECRET, null, 2, 256, 2, 0x13, 2)
        val withAd = NativeArgon2.deriveKey(PASSWORD, SALT, null, AD16, 2, 256, 2, 0x13, 2)
        assertFalse("secret 必须参与 H0 运算", base.contentEquals(withSecret))
        assertFalse("AD 必须参与 H0 运算", base.contentEquals(withAd))
    }

    // ============ 4) 性能对照（R1 决策闸门）============

    @Test
    fun `原生内核相对 BouncyCastle 的性能对照`() {
        val rows = PERF_CASES.map { a ->
            val nativeNs = bestOf {
                requireNotNull(
                    NativeArgon2.deriveKey(
                        PASSWORD, SALT, null, null,
                        a.iterations, a.memoryKib, a.parallelism, a.version, a.type
                    )
                )
            }
            val bcNs = bestOf { deriveBc(a) }
            Row(a.name, a.parallelism, nativeNs / 1_000_000.0, bcNs / 1_000_000.0)
        }

        println("[Argon2 性能对照 · 宿主 x86_64] m=16MiB t=2, 取 warmup 后 3 次最优")
        rows.forEach {
            println(
                "  ${it.name}: native=${"%.1f".format(it.nativeMs)}ms " +
                    "bc=${"%.1f".format(it.bcMs)}ms 加速比=${"%.2f".format(it.bcMs / it.nativeMs)}x"
            )
        }

        rows.forEach {
            assertTrue(
                "[${it.name}] 原生耗时 ${"%.1f".format(it.nativeMs)}ms 超过 BC " +
                    "${"%.1f".format(it.bcMs)}ms 的 ${NATIVE_VS_BC_MAX_RATIO} 倍（R1 性能回退闸门触发）",
                it.nativeMs <= it.bcMs * NATIVE_VS_BC_MAX_RATIO
            )
        }
    }

    private data class Row(
        val name: String,
        val parallelism: Int,
        val nativeMs: Double,
        val bcMs: Double
    )

    /** 预热 1 次后取 3 次最优，抑制 JIT/调度抖动。 */
    private fun bestOf(block: () -> ByteArray): Long {
        block()
        var best = Long.MAX_VALUE
        repeat(3) {
            val start = System.nanoTime()
            block()
            best = minOf(best, System.nanoTime() - start)
        }
        return best
    }

    // ============ BC 对照实现（镜像 Argon2KdfEngine.transformJvm 的参数装配）============

    private fun deriveBc(a: Args): ByteArray {
        val bcType = if (a.type == NativeArgon2.TYPE_ARGON2D) {
            Argon2Parameters.ARGON2_d
        } else {
            Argon2Parameters.ARGON2_id
        }
        val builder = Argon2Parameters.Builder(bcType)
            .withSalt(SALT)
            .withParallelism(a.parallelism)
            .withMemoryAsKB(a.memoryKib)
            .withIterations(a.iterations)
            .withVersion(a.version)
        if (a.secret != null && a.secret.isNotEmpty()) builder.withSecret(a.secret)
        if (a.ad != null && a.ad.isNotEmpty()) builder.withAdditional(a.ad)

        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(OUT_LEN)
        generator.generateBytes(PASSWORD, out)
        return out
    }
}
