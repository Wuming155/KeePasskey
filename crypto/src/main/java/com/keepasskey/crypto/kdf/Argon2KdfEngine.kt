package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2 (Argon2d / Argon2id) 密钥派生引擎（KDBX 4 现代化标准 KDF）
 *
 * TASK-50 性能整改：优先走自维护原生 JNI 实现 `NativeArgon2`（ISSUE-P2-14 起为 **Rust 内核**：
 * RustCrypto `argon2` + `zeroize` 确定性擦除，对齐 KeePassDX 的 native libargon2 架构；
 * Batch 4 宿主侧实测较 BouncyCastle 快 2.2~5.4 倍，p=4 多核收益约 3×）；
 * JVM 实现仅作兜底——
 * 1. 桌面 JVM（单元测试）无法加载 Android .so 时；
 * 2. Argon2 版本非 0x10/0x13 时；
 * 3. 原生探活失败（个别机型）时。
 */
class Argon2KdfEngine(
    val type: KdfParameters.Argon2.Argon2Type
) : KdfEngine {

    override val kdfUuid: KdbxUuid = when (type) {
        KdfParameters.Argon2.Argon2Type.ARGON2D -> KdbxConstants.Kdf.ARGON2D
        KdfParameters.Argon2.Argon2Type.ARGON2ID -> KdbxConstants.Kdf.ARGON2ID
    }

    override val name: String = when (type) {
        KdfParameters.Argon2.Argon2Type.ARGON2D -> "Argon2d"
        KdfParameters.Argon2.Argon2Type.ARGON2ID -> "Argon2id"
    }

    override fun transform(compositeKey: ByteArray, parameters: KdfParameters): ByteArray {
        val argonParams = parameters as? KdfParameters.Argon2
            ?: throw CryptoException.KdfException("参数类型错误，期望 KdfParameters.Argon2")

        require(argonParams.type == type) { "Argon2 类型不匹配: 期望 $type, 实际 ${argonParams.type}" }

        val nativeType = when (type) {
            KdfParameters.Argon2.Argon2Type.ARGON2D -> NativeArgon2.TYPE_ARGON2D
            KdfParameters.Argon2.Argon2Type.ARGON2ID -> NativeArgon2.TYPE_ARGON2ID
        }
        val versionSupported = argonParams.version == KdfParameters.Argon2.ARGON2_VERSION_13 ||
            argonParams.version == KdfParameters.Argon2.ARGON2_VERSION_10

        // R2（ISSUE-P2-14）：原生 Rust Argon2 内核（RustCrypto argon2 0.6.0）的 AssociatedData 上限为 32B，
        // AD 超过则 derive() fail-closed 返回 null。为保持与旧 C 内核 / BouncyCastle 任意长度 AD 的行为一致，
        // AD>32 时强制走 BC 兜底（真实 KeePass/KeePassXC 生成库不设 KDF 的 A 字段，此路径极罕见）。
        val adExceedsNativeLimit = argonParams.associatedData?.let { it.size > NATIVE_MAX_AD_LEN } == true

        // ISSUE-P2-59（审计 RUST-05）AC②：可表达性预检**先于**一切派生 / 兜底判定——
        // 原实现 `(memoryInBytes / 1024).toInt()` / `iterations.toInt()` 在超 Int 范围时**静默窄化**
        // （如回绕为负数），随后或被 JNI 有符号闸门当作非法参数、或在 BC 侧拼出错误参数。
        // 现改为显式越界抛异常（fail-closed），杜绝「参数被静默改写」这一类不可观测失效。
        val memoryKib = requireExpressibleAsInt(argonParams.memoryInBytes, "内存", 1024L)
        val nativeIterations = requireExpressibleAsInt(argonParams.iterations, "迭代")

        // ISSUE-P2-59 AC①：原生路径参数上界镜像（与 `KdbxKdfParameterCodec` 同值）。
        // 越界即**不**进入原生路径（回落到既有 BC 兜底，其自带 `isMemoryParamFeasible` 堆预检），
        // 语义与原「原生 derive 返回 null → KdfException」一致，且不放松任何既有拒绝。
        val withinKdfBounds = isWithinKdfBounds(
            argonParams.memoryInBytes,
            argonParams.iterations,
            argonParams.parallelism
        )

        if (NativeArgon2.available && versionSupported && !adExceedsNativeLimit && withinKdfBounds) {
            return NativeArgon2.derive(
                password = compositeKey,
                salt = argonParams.salt,
                secret = argonParams.secretKey,
                associatedData = argonParams.associatedData,
                iterations = nativeIterations,
                memoryKib = memoryKib,
                parallelism = argonParams.parallelism,
                version = argonParams.version,
                type = nativeType
            )
        }

        return transformJvm(compositeKey, argonParams, memoryKib, nativeIterations)
    }

    /**
     * BouncyCastle 纯 JVM 兜底实现（桌面单测 / 原生不可用场景）。
     *
     * [memoryKib] / [iterations] 为 [transform] 已完成可表达性预检的窄化结果
     * （ISSUE-P2-59 AC②：不再在本方法内以 `toInt()` 静默截断）。
     */
    private fun transformJvm(
        compositeKey: ByteArray,
        argonParams: KdfParameters.Argon2,
        memoryKib: Int,
        iterations: Int
    ): ByteArray {
        if (!isMemoryParamFeasible(argonParams.memoryInBytes)) {
            // P0 防闪退预检：JVM 实现内存块为整段 long[]，堆上限不足时直接失败而非 OOM
            throw CryptoException.KdfException(
                "数据库 KDF 内存参数（约 ${argonParams.memoryInBytes / (1024 * 1024)} MB）超出本机可用内存，无法解锁"
            )
        }

        val bcType = when (type) {
            KdfParameters.Argon2.Argon2Type.ARGON2D -> Argon2Parameters.ARGON2_d
            KdfParameters.Argon2.Argon2Type.ARGON2ID -> Argon2Parameters.ARGON2_id
        }

        return try {
            val builder = Argon2Parameters.Builder(bcType)
                .withSalt(argonParams.salt)
                .withParallelism(argonParams.parallelism)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withVersion(argonParams.version)

            // ISSUE-P2-60：secretKey 为 var（clearSensitive 可置 null）——取局部快照避免并发清零
            // 与派生交错时读到中间态；null（已清零）按「无 secret」跳过
            val kdfSecret = argonParams.secretKey
            if (kdfSecret != null && kdfSecret.isNotEmpty()) {
                builder.withSecret(kdfSecret)
            }
            if (argonParams.associatedData != null && argonParams.associatedData.isNotEmpty()) {
                builder.withAdditional(argonParams.associatedData)
            }

            val generator = Argon2BytesGenerator()
            generator.init(builder.build())

            val derivedKey = ByteArray(32)
            generator.generateBytes(compositeKey, derivedKey)
            derivedKey
        } catch (e: OutOfMemoryError) {
            // P0 防闪退：桌面端创建的高内存参数库在移动端堆上限下直接 OOM，
            // 兜底为友好失败（KeePassDX Limits 模式），避免裸 Error 逃逸
            throw CryptoException.KdfException(
                "数据库 KDF 内存参数（约 ${argonParams.memoryInBytes / (1024 * 1024)} MB）超出本机可用内存，无法解锁",
                e
            )
        } catch (e: Exception) {
            throw CryptoException.KdfException("Argon2 ($name) 密钥派生失败", e)
        }
    }

    companion object {
        /**
         * R2（ISSUE-P2-14）：原生 Rust Argon2 内核（RustCrypto argon2 0.6.0）的 AssociatedData 上限，
         * 与 Rust 侧 `MAX_AD_LEN` 保持一致；AD 超过此长度时原生内核 fail-closed 返回 null，
         * 由 [transform] 改走 BouncyCastle 兜底路径。
         */
        const val NATIVE_MAX_AD_LEN = 32

        /**
         * JVM 兜底实现的内存预检：最大堆预留 40% 余量后能否容纳整段内存块。
         */
        fun isMemoryParamFeasible(memoryInBytes: Long): Boolean {
            val maxHeap = Runtime.getRuntime().maxMemory()
            return memoryInBytes > 0 && memoryInBytes <= maxHeap * 0.6
        }

        /**
         * ISSUE-P2-59（审计 RUST-05）AC①：原生路径参数上界**镜像**。
         *
         * 与 `KdbxKdfParameterCodec.validateArgon2Bounds` **同值**——模块依赖单向
         * （`database → crypto`），crypto 侧不可反向引用 database 常量，故此处同值声明并
         * 以本 KDoc 与本仓单测双向锁定（值漂移即用例失败）。
         *
         * 语义：原生内核自身只做下界闸门（`memoryKib ≥ 8 × parallelism`），无逐项上界，
         * 故上界裁决须在引擎侧补齐；越界时**不进入**原生路径，回落 BC 兜底（其自带堆预检）。
         * 取值宽于一切合法用户配置（本仓 `KdfBenchmark` 自荐上限 ≤512 MiB × 20，远小于 4 GiB / 2²⁴）。
         */
        fun isWithinKdfBounds(memoryInBytes: Long, iterations: Long, parallelism: Int): Boolean =
            memoryInBytes >= ARGON2_MIN_MEMORY_BYTES &&
                memoryInBytes <= ARGON2_MAX_MEMORY_BYTES &&
                iterations in 1..ARGON2_MAX_ITERATIONS &&
                parallelism in 1..ARGON2_MAX_PARALLELISM

        /**
         * ISSUE-P2-59 AC②：受检窄化——超 [Int] 可表达范围时**抛异常**，绝不静默截断。
         *
         * 原实现 `(memoryInBytes / 1024).toInt()` 与 `iterations.toInt()` 在越界时回绕为
         * 任意值（含负数），使「被静默改写的参数」参与派生 / 被 JNI 有符号闸门拒绝——
         * 两类结果都不可从异常信息中辨识根因。本函数把该失效面收敛为可读的 fail-closed 异常。
         */
        internal fun requireExpressibleAsInt(value: Long, field: String, scale: Long = 1L): Int {
            val scaled = value / scale
            if (scaled > Int.MAX_VALUE) {
                throw CryptoException.KdfException(
                    "KDF $field 参数超出可表达范围: $value（上限 ${Int.MAX_VALUE.toLong() * scale}），拒绝静默截断"
                )
            }
            return scaled.toInt()
        }

        /** 镜像 `KdbxKdfParameterCodec.ARGON2_MIN_MEMORY_BYTES`（官方语义下界 8192 字节）。 */
        private const val ARGON2_MIN_MEMORY_BYTES = 8192L

        /** 镜像 `KdbxKdfParameterCodec.ARGON2_MAX_MEMORY_BYTES`（4 GiB 防 DoS 封顶）。 */
        private const val ARGON2_MAX_MEMORY_BYTES = 4L * 1024 * 1024 * 1024

        /** 镜像 `KdbxKdfParameterCodec.ARGON2_MAX_ITERATIONS`（2²⁴ 防 DoS 封顶）。 */
        private const val ARGON2_MAX_ITERATIONS = 1L shl 24

        /** 镜像 `KdbxKdfParameterCodec.ARGON2_MAX_PARALLELISM`。 */
        private const val ARGON2_MAX_PARALLELISM = 64
    }
}
