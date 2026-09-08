package com.keepasskey.core.model

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * KDBX 标准 16 字节 UUID 实体，不可变值对象。
 * 用于全局唯一标识 Group、Entry、CustomIcon、Cipher、KDF 等。
 */
class KdbxUuid(
    bytes: ByteArray
) : Comparable<KdbxUuid> {

    private val data: ByteArray = bytes.clone()

    init {
        require(data.size == UUID_SIZE) {
            "KdbxUuid 字节长度必须为 $UUID_SIZE，实际为 ${data.size}"
        }
    }

    fun toByteArray(): ByteArray = data.clone()

    fun toHexString(): String {
        val sb = StringBuilder(UUID_SIZE * 2)
        for (b in data) {
            sb.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun toFormattedString(): String {
        val hex = toHexString()
        // 8-4-4-4-12 标准格式
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    override fun compareTo(other: KdbxUuid): Int {
        for (i in 0 until UUID_SIZE) {
            val a = data[i].toInt() and 0xFF
            val b = other.data[i].toInt() and 0xFF
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KdbxUuid) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = toFormattedString()

    companion object {
        const val UUID_SIZE = 16

        val ZERO = KdbxUuid(ByteArray(UUID_SIZE))

        private val secureRandom = SecureRandom()

        fun random(): KdbxUuid {
            val bytes = ByteArray(UUID_SIZE)
            secureRandom.nextBytes(bytes)
            return KdbxUuid(bytes)
        }

        fun fromJavaUuid(uuid: UUID): KdbxUuid {
            val bb = ByteBuffer.wrap(ByteArray(UUID_SIZE))
            bb.putLong(uuid.mostSignificantBits)
            bb.putLong(uuid.leastSignificantBits)
            return KdbxUuid(bb.array())
        }

        fun fromHexString(hex: String): KdbxUuid {
            val clean = hex.replace("-", "").trim()
            require(clean.length == UUID_SIZE * 2) {
                "Hex 长度非法: $hex"
            }
            val bytes = ByteArray(UUID_SIZE)
            for (i in 0 until UUID_SIZE) {
                val index = i * 2
                bytes[i] = clean.substring(index, index + 2).toInt(16).toByte()
            }
            return KdbxUuid(bytes)
        }
    }
}
