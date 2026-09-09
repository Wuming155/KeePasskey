package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2 (Argon2d / Argon2id) 密钥派生引擎（KDBX 4 现代化标准 KDF）
 *
 * TASK-50 性能整改：优先走自维护原生 JNI 实现 `NativeArgon2`（PHC 官方参考实现，
 * 对齐 KeePassDX 的 native libargon2 架构，速度远快于 BouncyCastle 纯 Java 实现）；
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

        if (NativeArgon2.available && versionSupported) {
            return NativeArgon2.derive(
                password = compositeKey,
                salt = argonParams.salt,
                secret = argonParams.secretKey,
                associatedData = argonParams.associatedData,
                iterations = argonParams.iterations.toInt(),
                memoryKib = (argonParams.memoryInBytes / 1024L).toInt(),
                parallelism = argonParams.parallelism,
                version = argonParams.version,
                type = nativeType
            )
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
         * JVM 兜底实现的内存预检：最大堆预留 40% 余量后能否容纳整段内存块。
         */
        fun isMemoryParamFeasible(memoryInBytes: Long): Boolean {
            val maxHeap = Runtime.getRuntime().maxMemory()
            return memoryInBytes > 0 && memoryInBytes <= maxHeap * 0.6
        }
    }
}
