package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import com.lambdapioneer.argon2kt.Argon2Exception
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2 (Argon2d / Argon2id) 密钥派生引擎（KDBX 4 现代化标准 KDF）
 *
 * TASK-50 性能整改：优先走 Argon2Kt 原生 JNI 实现（对齐 KeePassDX 的 libargon2 C 实现，
 * 速度远快于 BouncyCastle 纯 Java 实现）；JVM 实现仅作兜底——
 * 1. 桌面 JVM（单元测试）无法加载 Android .so 时；
 * 2. KDBX 头携带 KDF secret / associatedData 时（Argon2Kt 未暴露该参数）；
 * 3. Argon2 版本非 0x10/0x13 时。
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

        // Argon2Kt 不支持 secret/AD：该类库（官方极少生成）直接走 JVM 兜底
        val hasSecret = argonParams.secretKey != null && argonParams.secretKey.isNotEmpty()
        val hasAssociatedData = argonParams.associatedData != null && argonParams.associatedData.isNotEmpty()

        val native = if (!hasSecret && !hasAssociatedData) nativeArgon2 else null
        if (native != null) {
            val mode = when (type) {
                KdfParameters.Argon2.Argon2Type.ARGON2D -> Argon2Mode.ARGON2_D
                KdfParameters.Argon2.Argon2Type.ARGON2ID -> Argon2Mode.ARGON2_ID
            }
            val version = when (argonParams.version) {
                KdfParameters.Argon2.ARGON2_VERSION_13 -> Argon2Version.V13
                KdfParameters.Argon2.ARGON2_VERSION_10 -> Argon2Version.V10
                else -> null
            }
            if (version != null) {
                try {
                    val result = native.hash(
                        mode = mode,
                        password = compositeKey,
                        salt = argonParams.salt,
                        tCostInIterations = argonParams.iterations.toInt(),
                        mCostInKibibyte = (argonParams.memoryInBytes / 1024L).toInt(),
                        parallelism = argonParams.parallelism,
                        hashLengthInBytes = 32,
                        version = version
                    )
                    return result.rawHashAsByteArray()
                } catch (e: Argon2Exception) {
                    // 原生层失败（如内存分配失败）不回退 JVM——重算只会更糟，直接友好失败
                    throw CryptoException.KdfException("Argon2 ($name) 密钥派生失败: ${e.message}", e)
                } catch (e: OutOfMemoryError) {
                    throw CryptoException.KdfException(
                        "数据库 KDF 内存参数（约 ${argonParams.memoryInBytes / (1024 * 1024)} MB）超出本机可用内存，无法解锁",
                        e
                    )
                }
            }
        }

        return transformJvm(compositeKey, argonParams)
    }

    /**
     * BouncyCastle 纯 JVM 兜底实现（桌面单测 / 原生不可用场景）
     */
    private fun transformJvm(compositeKey: ByteArray, argonParams: KdfParameters.Argon2): ByteArray {
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
            val memoryKb = (argonParams.memoryInBytes / 1024L).toInt()
            val builder = Argon2Parameters.Builder(bcType)
                .withSalt(argonParams.salt)
                .withParallelism(argonParams.parallelism)
                .withMemoryAsKB(memoryKb)
                .withIterations(argonParams.iterations.toInt())
                .withVersion(argonParams.version)

            if (argonParams.secretKey != null && argonParams.secretKey.isNotEmpty()) {
                builder.withSecret(argonParams.secretKey)
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
         * 原生 Argon2 探活（懒加载一次）：桌面 JVM / 个别机型 .so 加载失败时返回 null，
         * 调用方降级 BouncyCastle。探活用极小参数试算，确保功能真正可用而非仅加载成功。
         */
        private val nativeArgon2: Argon2Kt? by lazy {
            try {
                val engine = Argon2Kt()
                engine.hash(
                    mode = Argon2Mode.ARGON2_ID,
                    password = ByteArray(32),
                    salt = ByteArray(16),
                    tCostInIterations = 1,
                    mCostInKibibyte = 8,
                    parallelism = 1,
                    hashLengthInBytes = 32,
                    version = Argon2Version.V13
                )
                engine
            } catch (t: Throwable) {
                null
            }
        }

        /**
         * JVM 兜底实现的内存预检：最大堆预留 40% 余量后能否容纳整段内存块。
         */
        fun isMemoryParamFeasible(memoryInBytes: Long): Boolean {
            val maxHeap = Runtime.getRuntime().maxMemory()
            return memoryInBytes > 0 && memoryInBytes <= maxHeap * 0.6
        }
    }
}
