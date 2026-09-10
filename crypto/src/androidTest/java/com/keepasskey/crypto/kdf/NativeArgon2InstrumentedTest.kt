package com.keepasskey.crypto.kdf

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Rust Argon2 原生内核 · **设备侧 instrumented 运行时验证**（ISSUE-P3-11，承接 ISSUE-P2-14 遗留）
 *
 * 本用例补的是「打包期证据 → 运行时证据」的缺口：
 * Batch 4 只在宿主桌面 JVM 经 `-Djava.library.path` 加载宿主 cdylib 验证了 JNI 通路
 * （见 `crypto/src/test/java/com/keepasskey/crypto/kdf/NativeArgon2HostJniTest.kt`），
 * 而 Android 上 `.so` 是 **从 APK 内解包加载** 的（`System.loadLibrary` 走 APK native lib 目录、
 * ABI 选择、AGP strip 后的符号表），这条链路的运行时行为此前从未被真实执行（风险 R6）。
 *
 * 设计要点：
 * 1. `NativeArgon2.available == false` 时**必须硬失败**（[fail]，不用 `Assume`）——「静默跳过」正是
 *    本条目要消除的验证缺口；失败信息由 [nativeLoadDiagnostic] 给出可定位的诊断原文。
 * 2. 冻结向量字面量**逐字复制**自既有对照用例，不新造向量：
 *    - 期望输出 `expectedOutHex`：`crypto/src/test/resources/argon2-interop/argon2-bc-vectors.json`
 *      （BC 1.85.2 生成、由 `Argon2BcVectorTest` 防漂移锁守护的冻结真相源）；
 *    - 参数域等价矩阵（含 secret/AD 与 p=1/4）与 R2 长 AD 探针：`NativeArgon2HostJniTest.kt`。
 *    因上述来源的常量均为 `private`（且本轮禁止修改既有测试的可见性），故在本文内复制字面量并标注来源。
 * 3. 性能对照即 R1 决策闸门：原生耗时不得超过 BouncyCastle 的 [NATIVE_VS_BC_MAX_RATIO] 倍。
 *
 * 敏感数据：本用例全部输入均为**公开合成测试向量**（非真实凭据），仍按项目铁律以
 * `ByteArray` 承载并在用后清零，日志不打印任何派生结果明文。
 */
@RunWith(AndroidJUnit4::class)
class NativeArgon2InstrumentedTest {

    private companion object {
        // ---------- 基础常量 ----------

        /** 派生输出长度（KDBX / BC 冻结向量一致约定为 32B）。 */
        private const val OUT_LEN = 32

        /** `System.loadLibrary` 使用的库名（与 Rust 产物 `libkeepasskey_argon2.so` 对齐）。 */
        private const val NATIVE_LIB_NAME = "keepasskey_argon2"

        /** 探活试算迭代次数：极小规模，仅验证 JNI 调用通路，不用于任何断言性派生。 */
        private const val PROBE_ITERATIONS = 1

        /** 探活试算内存参数（KiB）：8 KiB = Argon2 允许的最小值。 */
        private const val PROBE_MEMORY_KIB = 8

        /** 探活试算并行度：单 lane。 */
        private const val PROBE_PARALLELISM = 1

        /**
         * 诊断用候选库名清单：`System.loadLibrary` 失败时逐个重试并记录 `UnsatisfiedLinkError` 原文，
         * 便于区分「名字写错」「ABI 未打包」「NDK 版本不兼容」等不同失败原因。
         */
        private val ATTEMPTED_LIB_NAMES = listOf(
            NATIVE_LIB_NAME,
            "keepasskey_argon2_v2",
            "argon2"
        )

        // ---------- 冻结向量输入（来源：crypto/src/test/resources/argon2-interop/argon2-bc-vectors.json
        //            与 Argon2BcVectorTest.kt / NativeArgon2HostJniTest.kt 的 private 常量）----------

        /** 32B 复合密钥风格口令（KDBX 规约的 SHA-256 摘要形态）。 */
        private const val PWD32 = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

        /** 16B 盐。 */
        private const val SALT16 = "0f0e0d0c0b0a09080706050403020100"

        /** 32B 盐（KDBX4 现实档位）。 */
        private const val SALT32 = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

        /** 32B KDF secret（罕见参数面）。 */
        private const val SECRET32 = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"

        /** 16B associatedData。 */
        private const val AD16 = "0123456789abcdef0123456789abcdef"

        /** 32B associatedData（恰好命中 RustCrypto `AssociatedData::MAX_LEN`）。 */
        private const val AD32 = "deadbeefcafebabe00112233445566778899aabbccddeeff0011223344556677"

        /** 64B 长 AD —— R2 探针：原生内核应 fail-closed 返回 null（BC 无长度上限）。 */
        private const val AD64 = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"

        // ---------- 冻结向量表（Argon2d/id × 版本 0x10/0x13 全覆盖）----------

        /**
         * 一条 BC 冻结向量：输入参数 + 期望输出（hex）。
         * 字段约定与 `NativeArgon2.deriveKey` 的 JNI 签名逐一对齐：type 0=Argon2d / 2=Argon2id，
         * version 16=0x10 / 19=0x13，memoryKib 以 KiB 计，输入均为原始字节。
         */
        private data class FrozenVector(
            val name: String,
            val type: Int,
            val version: Int,
            val iterations: Int,
            val memoryKib: Int,
            val parallelism: Int,
            val passwordHex: String,
            val saltHex: String,
            val secretHex: String?,
            val adHex: String?,
            val expectedOutHex: String
        )

        /**
         * 冻结语料全量（12 条，逐字复制自 `argon2-bc-vectors.json`）。
         * 说明：`argon2id_v13_ad64_probe` / `argon2d_v13_ad64_probe` 两条 64B 长 AD 向量**不在本表**——
         * 原生内核按设计 fail-closed 拒绝（见 [R2 长 AD 探针] 用例），故单列断言。
         */
        private val FROZEN_VECTORS: List<FrozenVector> = listOf(
            FrozenVector(
                "argon2id_v13_base", 2, 19, 2, 256, 2, PWD32, SALT32, null, null,
                "b6f9502021495f26cded7c77c976c0c89aaf28a9a73d035f51c70ded8be90ed8"
            ),
            FrozenVector(
                "argon2id_v10_base", 2, 16, 2, 256, 2, PWD32, SALT32, null, null,
                "5373c816e4aff2f1ab48a004af70c2cb4ce6629f8df8db8415709d191186e351"
            ),
            FrozenVector(
                "argon2d_v13_base", 0, 19, 2, 256, 2, PWD32, SALT32, null, null,
                "aa3f43d30766cef445e7d19d2664198dd2a611a8e657c87fc7f7e7eca4c1ac1d"
            ),
            FrozenVector(
                "argon2d_v10_base", 0, 16, 2, 256, 2, PWD32, SALT32, null, null,
                "0092772c1a8d692cd1a795da143897c459f27d7f288d4301542a3aed9317aef0"
            ),
            FrozenVector(
                "argon2id_v13_secret", 2, 19, 2, 256, 2, PWD32, SALT32, SECRET32, null,
                "0087bdaac3cb5f06fdb2cc9acec48602d7008fbbcc372405c585e894f793a061"
            ),
            FrozenVector(
                "argon2id_v13_ad16", 2, 19, 2, 256, 2, PWD32, SALT32, null, AD16,
                "4b77495bc0e76aa2af9c0c6073410c76b20dd236aa7dc3880dff29bc1e71a9ba"
            ),
            FrozenVector(
                "argon2id_v13_ad32", 2, 19, 2, 256, 2, PWD32, SALT32, null, AD32,
                "d0e1fdf31029d2e08e66dbdb61ceb5938eff1704d7529846faa8a228a413f2e9"
            ),
            FrozenVector(
                "argon2id_v13_secret_ad32", 2, 19, 3, 256, 2, PWD32, SALT32, SECRET32, AD32,
                "794ed733130742f99029f77dfd842241c09f589e8357c7c74c1f312c6eb2b23e"
            ),
            FrozenVector(
                "argon2d_v10_secret_ad16", 0, 16, 2, 512, 2, PWD32, SALT32, SECRET32, AD16,
                "e3aa9f271a2ee7cc20ff1856d1cc8d647dc96b191063b6aef6fa6b5077eb9310"
            ),
            FrozenVector(
                "argon2id_v13_p1_salt16", 2, 19, 3, 256, 1, PWD32, SALT16, null, null,
                "daff05a89415510a69f5333160b83c4e1217de7ac0569148330c496334756ca0"
            ),
            FrozenVector(
                "argon2id_v13_p4", 2, 19, 2, 512, 4, PWD32, SALT32, null, null,
                "333ae12546ca8e01d77e8179270d7568bef7b12c3b2c4f3444a647536eeab1bf"
            ),
            FrozenVector(
                "argon2id_v13_realistic_m4096", 2, 19, 3, 4096, 2, PWD32, SALT32, null, null,
                "1856c7ac0e467b00bf2513f899d6a598c152ccc407bb2b5d2a329f4d3506d65f"
            )
        )

        /** 两条 R2 长 AD 探针的期望 BC 输出（原生侧应拒绝，BC 侧应复现该值）。 */
        private const val AD64_PROBE_BC_OUT_ARGON2ID =
            "8530ffbabd2516a6cde793ac7d2bae848f18ef69774b19480fb7146d9e9ffb52"
        private const val AD64_PROBE_BC_OUT_ARGON2D =
            "179204c26b3d06b98d31fc7aaa7ebaa67efa17a5b07b21b632fdccc55bef345f"

        // ---------- 参数域等价矩阵（来源：NativeArgon2HostJniTest.kt 的 EQUIVALENCE_MATRIX）----------

        /** 32B 口令：`(i * 7 + 1) mod 256`（与冻结向量族不同的第二组输入，扩大覆盖面）。 */
        private val MATRIX_PASSWORD = ByteArray(32) { (it * 7 + 1).toByte() }

        /** 32B 盐：`0xFF - i`。 */
        private val MATRIX_SALT = ByteArray(32) { (0xFF - it).toByte() }

        /** 32B KDF secret：`(i * 3 + 5) mod 256`。 */
        private val MATRIX_SECRET = ByteArray(32) { (it * 3 + 5).toByte() }

        /** 16B AD：`i`。 */
        private val MATRIX_AD16 = ByteArray(16) { it.toByte() }

        /** 32B AD：`i xor 0x5A`。 */
        private val MATRIX_AD32 = ByteArray(32) { (it xor 0x5A).toByte() }

        /** 33B AD：越界探针（RustCrypto `AssociatedData::MAX_LEN = 32`）。 */
        private val MATRIX_AD33 = ByteArray(33) { it.toByte() }

        /** 一条矩阵参数（输入固定为 MATRIX_* 系列）。 */
        private data class MatrixArgs(
            val name: String,
            val type: Int,
            val version: Int,
            val iterations: Int,
            val memoryKib: Int,
            val parallelism: Int,
            val secret: ByteArray? = null,
            val ad: ByteArray? = null
        )

        /** KDBX4 全参数域：d/id × 0x10/0x13 × 含/不含 secret + AD × p=1/4。 */
        private val EQUIVALENCE_MATRIX = listOf(
            MatrixArgs("id_v13_p2", 2, 0x13, 2, 256, 2),
            MatrixArgs("id_v10_p2", 2, 0x10, 2, 256, 2),
            MatrixArgs("d_v13_p2", 0, 0x13, 2, 256, 2),
            MatrixArgs("d_v10_p2", 0, 0x10, 2, 256, 2),
            MatrixArgs("id_v13_secret", 2, 0x13, 2, 256, 2, secret = MATRIX_SECRET),
            MatrixArgs("id_v13_ad16", 2, 0x13, 2, 256, 2, ad = MATRIX_AD16),
            MatrixArgs("id_v13_ad32", 2, 0x13, 2, 256, 2, ad = MATRIX_AD32),
            MatrixArgs(
                "id_v13_secret_ad32", 2, 0x13, 3, 256, 2,
                secret = MATRIX_SECRET, ad = MATRIX_AD32
            ),
            MatrixArgs(
                "d_v10_secret_ad16", 0, 0x10, 2, 512, 2,
                secret = MATRIX_SECRET, ad = MATRIX_AD16
            ),
            MatrixArgs("id_v13_p1", 2, 0x13, 3, 256, 1),
            MatrixArgs("id_v13_p4", 2, 0x13, 2, 512, 4),
            MatrixArgs("id_v13_realistic_m4096", 2, 0x13, 3, 4096, 2)
        )

        // ---------- 性能对照（R1 决策闸门，验收标准 3）----------

        /** 性能档位迭代次数 t。 */
        private const val PERF_ITERATIONS = 2

        /** 性能档位内存参数：64 MiB（验收标准 3 指定档位）。 */
        private const val PERF_MEMORY_KIB = 64 * 1024

        /** 性能档位并行度：p=2 与 p=4（验收标准 3 指定两档）。 */
        private val PERF_PARALLELISM_CASES = listOf(2, 4)

        /** 预热次数（不计入统计），抑制首次调用的类加载 / rayon 线程池初始化抖动。 */
        private const val PERF_WARMUP_SAMPLES = 1

        /** 计入统计的采样次数，取中位数抑制调度抖动导致的单次假失败。 */
        private const val PERF_MEASURED_SAMPLES = 5

        /**
         * R1 决策闸门阈值：原生耗时不得超过 BouncyCastle 的该倍数。
         * 语义不是「原生必须更快」，而是**禁止 Rust 迁移引入数量级性能回退**
         * （与 NativeArgon2HostJniTest.NATIVE_VS_BC_MAX_RATIO 保持一致）。
         */
        private const val NATIVE_VS_BC_MAX_RATIO = 2.0

        // ==================================================================================
        // 前置：强制加载原生库（**顺序无关性**的关键）
        // ==================================================================================
        //
        // `NativeArgon2.deriveKey` 是 `external fun`，而 `System.loadLibrary` 只写在
        // `NativeArgon2.available` 这个 `by lazy` 初始化器内部（被测对象，本轮不得修改）。
        // 因此**任何直接调用 `deriveKey` 的用例，若在 `available` 首次求值之前执行，
        // 都会先撞上 `UnsatisfiedLinkError`**（JUnit4 方法执行顺序不确定，这是真实踩到的坑）。
        // 故在此以 @BeforeClass 统一求值一次：既消除顺序依赖，又保证 `available == false`
        // 时整类**硬失败**并给出诊断（绝不静默跳过）。
        @JvmStatic
        @BeforeClass
        fun requireNativeLibraryAvailable() {
            if (!NativeArgon2.available) {
                fail(nativeLoadDiagnostic())
            }
        }

        /**
         * 构造原生加载失败的诊断信息：逐项列出已尝试库名与 `UnsatisfiedLinkError` 原文，
         * 使失败可定位（区分「库名错」「ABI 未打包进 APK」「符号缺失」）。
         */
        private fun nativeLoadDiagnostic(): String {
            val sb = StringBuilder()
            sb.append("NativeArgon2.available == false：设备侧 .so 未在运行时生效")
            sb.append("（ISSUE-P3-11 验收标准 1 未满足）\n")
            sb.append("  Build.MODEL=").append(Build.MODEL)
            sb.append(", Build.SUPPORTED_ABIS=").append(Build.SUPPORTED_ABIS.joinToString())
            sb.append('\n')
            sb.append("  os.arch=").append(System.getProperty("os.arch")).append('\n')
            sb.append("  java.library.path=").append(System.getProperty("java.library.path")).append('\n')
            sb.append("  System.mapLibraryName(").append(NATIVE_LIB_NAME).append(")=")
                .append(System.mapLibraryName(NATIVE_LIB_NAME)).append('\n')

            for (name in ATTEMPTED_LIB_NAMES) {
                sb.append("  尝试 System.loadLibrary(\"").append(name).append("\"): ")
                val outcome = try {
                    System.loadLibrary(name)
                    "加载成功（说明失败点在 JNI 探活试算，而非动态库定位）"
                } catch (e: UnsatisfiedLinkError) {
                    "UnsatisfiedLinkError: ${e.message}"
                } catch (e: SecurityException) {
                    "SecurityException: ${e.message}"
                }
                sb.append(outcome).append('\n')
            }

            sb.append("  提示：请核对 APK 内 lib/<abi>/libkeepasskey_argon2.so 是否存在")
            sb.append("（cargo-ndk 交叉编译产物，见 :crypto:cargoNdkBuild），以及 abiFilters 是否覆盖本机 ABI。")
            return sb.toString()
        }
    }

    // ==================================================================================
    // 1) 原生库可用性（验收标准 1 前半）—— 不可用时硬失败并输出诊断
    // ==================================================================================

    @Test
    fun 原生库在设备运行时可用且JNI派生通路成立() {
        assertTrue("NativeArgon2.available 应为 true（设备侧原生路径生效）", NativeArgon2.available)

        // ---- 直接运行时证据：确认被加载的 .so 确实来自被测 APK 内的 lib/<abi>/ ----
        // 仅靠 `available == true` 只能说明「某个」原生库被加载；这里进一步证明三件事：
        //   1) 运行时实际选用的 ABI（nativeLibraryDir 末段，`ApplicationInfo.primaryCpuAbi` 是隐藏 API）；
        //   2) 被测 APK 内部确实存在 `lib/<abi>/libkeepasskey_argon2.so` 且字节数 > 0；
        //   3) AGP 默认 `extractNativeLibs=false` 时该 .so **不会**解包落盘（加载自 APK 内 mmap），
        //      故「磁盘上 exists()」不是有效判据——只作信息记录，不作断言。
        val appInfo = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        val nativeDir = File(appInfo.nativeLibraryDir)
        val loadedAbi = nativeDir.name
        val libFileName = System.mapLibraryName(NATIVE_LIB_NAME)
        val apkPath = appInfo.sourceDir
        val apkEntryName = "lib/$loadedAbi/$libFileName"
        val libBytesInApk = ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry(apkEntryName)
            assertNotNull("被测 APK（$apkPath）内应存在 $apkEntryName", entry)
            zip.getInputStream(requireNotNull(entry)).use { it.readBytes().size }
        }
        println(
            "[NativeArgon2 设备侧加载证据] loadedAbi=$loadedAbi" +
                ", supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}" +
                ", nativeLibraryDir=${nativeDir.absolutePath}" +
                ", apk=$apkPath" +
                ", $apkEntryName=$libBytesInApk bytes" +
                ", 磁盘解包副本=${File(nativeDir, libFileName).exists()}"
        )
        assertTrue("APK 内 $apkEntryName 字节数应大于 0，实际 $libBytesInApk", libBytesInApk > 0)
        assertTrue(
            "运行时实际选用的 ABI（$loadedAbi）应属于本机支持的 ABI 列表",
            Build.SUPPORTED_ABIS.contains(loadedAbi)
        )

        // 探活试算：极小参数，确认 JNI 调用通路而非仅 dlopen 成功
        val probe = requireNotNull(
            NativeArgon2.deriveKey(
                password = ByteArray(32),
                salt = ByteArray(16),
                secret = null,
                associatedData = null,
                iterations = PROBE_ITERATIONS,
                memoryKib = PROBE_MEMORY_KIB,
                parallelism = PROBE_PARALLELISM,
                version = 0x13,
                type = NativeArgon2.TYPE_ARGON2ID
            )
        ) { "JNI 探活试算应返回非 null（.so 已加载但派生失败）" }
        assertEquals("探活输出长度应为 ${OUT_LEN}B", OUT_LEN, probe.size)
        assertFalse("探活输出不应为全零", probe.all { it == 0.toByte() })
        probe.fill(0)
    }

    // ==================================================================================
    // 2) 与 BC 冻结向量逐字节一致（验收标准 1 后半；覆盖 Argon2d/id × 0x10/0x13）
    // ==================================================================================

    @Test
    fun 原生派生与BouncyCastle冻结向量逐字节一致() {
        // 覆盖度自检：确保本表确实含 Argon2d/id 两类型与 0x10/0x13 两版本
        val coveredTypeVersion = FROZEN_VECTORS.map { it.type to it.version }.toSet()
        for (type in listOf(NativeArgon2.TYPE_ARGON2D, NativeArgon2.TYPE_ARGON2ID)) {
            for (version in listOf(0x10, 0x13)) {
                assertTrue(
                    "冻结语料必须覆盖 type=$type version=0x${version.toString(16)}",
                    coveredTypeVersion.contains(type to version)
                )
            }
        }

        for (v in FROZEN_VECTORS) {
            val expected = hexToBytes(v.expectedOutHex)
            val native = NativeArgon2.deriveKey(
                hexToBytes(v.passwordHex),
                hexToBytes(v.saltHex),
                v.secretHex?.let { hexToBytes(it) },
                v.adHex?.let { hexToBytes(it) },
                v.iterations,
                v.memoryKib,
                v.parallelism,
                v.version,
                v.type
            )

            assertNotNull("[${v.name}] 原生派生返回 null（参数合法却失败）", native)
            requireNotNull(native)
            assertEquals("[${v.name}] 原生输出长度应为 ${OUT_LEN}B", OUT_LEN, native.size)
            assertArrayEquals(
                "[${v.name}] 原生输出与 BC 冻结向量不一致：" +
                    "native=${bytesToHex(native)} expected=${v.expectedOutHex}",
                expected,
                native
            )
            assertFalse("[${v.name}] 原生输出不应为全零", native.all { it == 0.toByte() })

            // 二次对照：同参数由设备侧 BC 现场重算，排除「冻结 JSON 本身被改坏」的可能
            val bc = deriveBc(
                hexToBytes(v.passwordHex),
                hexToBytes(v.saltHex),
                v.secretHex?.let { hexToBytes(it) },
                v.adHex?.let { hexToBytes(it) },
                v.iterations,
                v.memoryKib,
                v.parallelism,
                v.version,
                v.type
            )
            assertArrayEquals("[${v.name}] 设备侧 BC 重算与冻结向量不一致", expected, bc)
            assertArrayEquals("[${v.name}] 原生输出与设备侧 BC 重算不一致", bc, native)

            // 派生结果按项目铁律用后清零（公开合成向量，仍保持习惯一致性）
            native.fill(0)
            bc.fill(0)
            expected.fill(0)
        }
    }

    // ==================================================================================
    // 3) 参数域等价 + JNI 参数闸门（来源：NativeArgon2HostJniTest.kt 第 1/2/3 节）
    // ==================================================================================

    @Test
    fun 原生内核与BouncyCastle在KDBX4全参数域逐字节一致() {
        for (a in EQUIVALENCE_MATRIX) {
            val native = NativeArgon2.deriveKey(
                MATRIX_PASSWORD, MATRIX_SALT, a.secret, a.ad,
                a.iterations, a.memoryKib, a.parallelism, a.version, a.type
            )
            assertNotNull("[${a.name}] 原生派生返回 null（参数合法却失败）", native)
            requireNotNull(native)
            assertEquals("[${a.name}] 原生输出长度应为 ${OUT_LEN}B", OUT_LEN, native.size)

            val bc = deriveBc(
                MATRIX_PASSWORD, MATRIX_SALT, a.secret, a.ad,
                a.iterations, a.memoryKib, a.parallelism, a.version, a.type
            )
            assertArrayEquals("[${a.name}] 原生输出与 BC 不一致", bc, native)
            assertFalse("[${a.name}] 原生输出不应为全零", native.all { it == 0.toByte() })

            native.fill(0)
            bc.fill(0)
        }
    }

    @Test
    fun 非法参数在JNI边界归一为null() {
        // 合法基线必须成功（闸门不过度收紧）
        assertNotNull(
            "合法参数应派生成功",
            NativeArgon2.deriveKey(
                MATRIX_PASSWORD, MATRIX_SALT, null, null, 2, 256, 2, 0x13, 2
            )
        )

        // KDBX 不使用 Argon2i(1)；type 越界
        assertNull(typeGate(1))
        assertNull(typeGate(3))
        // 非法 version
        assertNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, 2, 256, 2, 0x11, 2)
        )
        // iterations / parallelism 下界（含负值：不得经 as u32 变巨值绕过）
        assertNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, 0, 256, 2, 0x13, 2)
        )
        assertNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, -1, 256, 2, 0x13, 2)
        )
        assertNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, 2, 256, 0, 0x13, 2)
        )
        assertNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, 2, 256, -1, 0x13, 2)
        )
        // memoryKib 下界：m < 8×p（p=4 需 ≥32）
        assertNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, 2, 31, 4, 0x13, 2)
        )
        assertNotNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, 2, 32, 4, 0x13, 2)
        )
        // R2：AD 恰好 32B 合法，33B 越界 → fail-closed null
        assertNotNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, MATRIX_AD32, 2, 256, 2, 0x13, 2)
        )
        assertNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, MATRIX_AD33, 2, 256, 2, 0x13, 2)
        )
    }

    private fun typeGate(type: Int) =
        NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, 2, 256, 2, 0x13, type)

    @Test
    fun 原生派生确定且secret与AD参与运算() {
        val base = requireNotNull(
            NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, 2, 256, 2, 0x13, 2)
        )
        val again = NativeArgon2.deriveKey(MATRIX_PASSWORD, MATRIX_SALT, null, null, 2, 256, 2, 0x13, 2)
        assertArrayEquals("同参数重复派生应逐字节一致", base, again)
        assertFalse("派生结果不得等于输入口令（非直通）", MATRIX_PASSWORD.contentEquals(base))

        val withSecret = NativeArgon2.deriveKey(
            MATRIX_PASSWORD, MATRIX_SALT, MATRIX_SECRET, null, 2, 256, 2, 0x13, 2
        )
        val withAd = NativeArgon2.deriveKey(
            MATRIX_PASSWORD, MATRIX_SALT, null, MATRIX_AD16, 2, 256, 2, 0x13, 2
        )
        assertFalse("secret 必须参与 H0 运算", base.contentEquals(withSecret))
        assertFalse("AD 必须参与 H0 运算", base.contentEquals(withAd))

        base.fill(0)
        again?.fill(0)
        withSecret?.fill(0)
        withAd?.fill(0)
    }

    // ==================================================================================
    // 4) R2 长 AD 探针：原生 fail-closed，BC 兜底仍可复现冻结输出
    // ==================================================================================

    @Test
    fun R2长AD探针在原生侧fail_closed且BC侧可复现冻结输出() {
        for (type in listOf(NativeArgon2.TYPE_ARGON2D, NativeArgon2.TYPE_ARGON2ID)) {
            val expectedHex = if (type == NativeArgon2.TYPE_ARGON2D) {
                AD64_PROBE_BC_OUT_ARGON2D
            } else {
                AD64_PROBE_BC_OUT_ARGON2ID
            }

            // 原生：64B AD 超 RustCrypto AssociatedData::MAX_LEN(32) → null
            assertNull(
                "type=$type 的 64B 长 AD 应由原生内核 fail-closed 返回 null",
                NativeArgon2.deriveKey(
                    hexToBytes(PWD32), hexToBytes(SALT32), null, hexToBytes(AD64),
                    2, 256, 2, 19, type
                )
            )

            // BC 兜底：复现冻结输出（Argon2KdfEngine.transform 在 AD>32 时改走 BC）
            val bc = deriveBc(
                hexToBytes(PWD32), hexToBytes(SALT32), null, hexToBytes(AD64),
                2, 256, 2, 19, type
            )
            assertArrayEquals(
                "type=$type 的 BC 兜底输出应与冻结向量一致",
                hexToBytes(expectedHex), bc
            )
            bc.fill(0)
        }
    }

    // ==================================================================================
    // 5) 性能对照（验收标准 3 / R1 决策闸门）
    // ==================================================================================

    @Test
    fun 原生内核相对BouncyCastle的性能对照满足R1闸门() {
        val password = hexToBytes(PWD32)
        val salt = hexToBytes(SALT32)

        println(
            "[Argon2 性能对照 · 设备 ${Build.SUPPORTED_ABIS.joinToString()}] " +
                "t=$PERF_ITERATIONS, m=${PERF_MEMORY_KIB / 1024}MiB, " +
                "warmup=$PERF_WARMUP_SAMPLES 次后取 $PERF_MEASURED_SAMPLES 次中位数"
        )

        for (parallelism in PERF_PARALLELISM_CASES) {
            val nativeNs = medianNanos {
                val out = requireNotNull(
                    NativeArgon2.deriveKey(
                        password, salt, null, null,
                        PERF_ITERATIONS, PERF_MEMORY_KIB, parallelism, 0x13,
                        NativeArgon2.TYPE_ARGON2ID
                    )
                ) { "p=$parallelism 原生派生返回 null" }
                out.fill(0)
            }
            val bcNs = medianNanos {
                deriveBc(
                    password, salt, null, null,
                    PERF_ITERATIONS, PERF_MEMORY_KIB, parallelism, 0x13,
                    NativeArgon2.TYPE_ARGON2ID
                ).fill(0)
            }

            val nativeMs = nativeNs / 1_000_000.0
            val bcMs = bcNs / 1_000_000.0
            println(
                "  t=$PERF_ITERATIONS m=${PERF_MEMORY_KIB / 1024}MiB p=$parallelism: " +
                    "native=${"%.1f".format(nativeMs)}ms " +
                    "bc=${"%.1f".format(bcMs)}ms " +
                    "加速比=${"%.2f".format(bcMs / nativeMs)}x"
            )

            assertTrue(
                "[t=$PERF_ITERATIONS m=${PERF_MEMORY_KIB / 1024}MiB p=$parallelism] " +
                    "原生耗时 ${"%.1f".format(nativeMs)}ms 超过 BC ${"%.1f".format(bcMs)}ms 的 " +
                    "$NATIVE_VS_BC_MAX_RATIO 倍（R1 性能回退闸门触发）",
                nativeMs <= bcMs * NATIVE_VS_BC_MAX_RATIO
            )
        }

        password.fill(0)
        salt.fill(0)
    }

    /** 预热 [PERF_WARMUP_SAMPLES] 次后采样 [PERF_MEASURED_SAMPLES] 次并返回中位数（纳秒）。 */
    private fun medianNanos(block: () -> Unit): Long {
        repeat(PERF_WARMUP_SAMPLES) { block() }
        val timings = LongArray(PERF_MEASURED_SAMPLES)
        for (i in 0 until PERF_MEASURED_SAMPLES) {
            val start = System.nanoTime()
            block()
            timings[i] = System.nanoTime() - start
        }
        val sorted = timings.clone()
        sorted.sort()
        val median = sorted[sorted.size / 2]
        timings.fill(0)
        sorted.fill(0)
        return median
    }

    // ==================================================================================
    // BC 对照实现（镜像 Argon2BcVectorTest / NativeArgon2HostJniTest 的参数装配约定）
    // ==================================================================================

    private fun deriveBc(
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray?,
        ad: ByteArray?,
        iterations: Int,
        memoryKib: Int,
        parallelism: Int,
        version: Int,
        type: Int
    ): ByteArray {
        val bcType = when (type) {
            NativeArgon2.TYPE_ARGON2D -> Argon2Parameters.ARGON2_d
            NativeArgon2.TYPE_ARGON2ID -> Argon2Parameters.ARGON2_id
            else -> throw IllegalArgumentException("非法 type=$type（仅 0/2）")
        }
        val builder = Argon2Parameters.Builder(bcType)
            .withSalt(salt)
            .withParallelism(parallelism)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withVersion(version)
        if (secret != null && secret.isNotEmpty()) builder.withSecret(secret)
        if (ad != null && ad.isNotEmpty()) builder.withAdditional(ad)

        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(OUT_LEN)
        generator.generateBytes(password, out)
        return out
    }

    // ---- hex 工具（与既有对照用例同约定）----

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "非法 hex 长度: ${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
