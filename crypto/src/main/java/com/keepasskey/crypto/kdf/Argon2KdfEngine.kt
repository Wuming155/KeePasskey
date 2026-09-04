package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2 (Argon2d / Argon2id) 密钥派生引擎（KDBX 4 现代化标准 KDF）
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
        } catch (e: Exception) {
            throw CryptoException.KdfException("Argon2 ($name) 密钥派生失败", e)
        }
    }
}
