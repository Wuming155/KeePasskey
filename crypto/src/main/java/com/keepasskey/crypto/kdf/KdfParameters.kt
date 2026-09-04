package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxUuid

/**
 * 结构化 KDF 参数容器，支持与 KDBX 4 VariantDictionary 序列化互相转换
 */
sealed class KdfParameters(val kdfUuid: KdbxUuid) {

    data class Aes(
        val seed: ByteArray,
        val rounds: Long
    ) : KdfParameters(com.keepasskey.core.model.KdbxConstants.Kdf.AES_KDF) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Aes) return false
            if (!seed.contentEquals(other.seed)) return false
            return rounds == other.rounds
        }

        override fun hashCode(): Int {
            var result = seed.contentHashCode()
            result = 31 * result + rounds.hashCode()
            return result
        }
    }

    data class Argon2(
        val type: Argon2Type,
        val salt: ByteArray,
        val parallelism: Int = 2,
        val memoryInBytes: Long = 64L * 1024 * 1024, // 64 MB
        val iterations: Long = 2L,
        val version: Int = ARGON2_VERSION_13,
        val secretKey: ByteArray? = null,
        val associatedData: ByteArray? = null
    ) : KdfParameters(
        if (type == Argon2Type.ARGON2D) com.keepasskey.core.model.KdbxConstants.Kdf.ARGON2D
        else com.keepasskey.core.model.KdbxConstants.Kdf.ARGON2ID
    ) {
        enum class Argon2Type {
            ARGON2D,
            ARGON2ID
        }

        companion object {
            const val ARGON2_VERSION_10 = 0x10
            const val ARGON2_VERSION_13 = 0x13
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Argon2) return false
            if (type != other.type) return false
            if (!salt.contentEquals(other.salt)) return false
            if (parallelism != other.parallelism) return false
            if (memoryInBytes != other.memoryInBytes) return false
            if (iterations != other.iterations) return false
            if (version != other.version) return false
            return true
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + parallelism
            result = 31 * result + memoryInBytes.hashCode()
            result = 31 * result + iterations.hashCode()
            result = 31 * result + version
            return result
        }
    }
}
