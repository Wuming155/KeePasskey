package com.keepasskey.database.crypto

import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * KDBX 4 VariantDictionary 序列化与反序列化器。
 * 用于承载 KDF 参数与文件扩展自定义数据。
 */
class VariantDictionary {

    private val map: MutableMap<String, Item> = LinkedHashMap()

    data class Item(val type: Byte, val value: Any)

    fun setUInt32(key: String, value: Long) {
        map[key] = Item(TYPE_UINT32, value.toInt())
    }

    fun getUInt32(key: String): Long? {
        val item = map[key] ?: return null
        // 类型宽容：UInt32 反序列化后可能以 Int 或 Long 形态驻留（UInt64 项亦可能被窄化存储），
        // 硬编码 as 强转会毁掉与官方 KeePass 2.x（Argon2 P/V 等以 UInt32 写出）的互操作性
        return when (val value = item.value) {
            is Int -> value.toLong() and 0xFFFFFFFFL
            is Long -> value and 0xFFFFFFFFL
            else -> throw KdbxCorruptFileException(
                "VariantDictionary 类型错误: $key 期望 UInt32，实际 ${typeNameOf(item.value)}"
            )
        }
    }

    fun setUInt64(key: String, value: Long) {
        map[key] = Item(TYPE_UINT64, value)
    }

    fun getUInt64(key: String): Long? {
        val item = map[key] ?: return null
        // 类型宽容：UInt32（Int）与 UInt64（Long）统一按无符号 64 位语义返回
        return when (val value = item.value) {
            is Long -> value
            is Int -> value.toLong() and 0xFFFFFFFFL
            else -> throw KdbxCorruptFileException(
                "VariantDictionary 类型错误: $key 期望 UInt64，实际 ${typeNameOf(item.value)}"
            )
        }
    }

    fun setBool(key: String, value: Boolean) {
        map[key] = Item(TYPE_BOOL, value)
    }

    fun getBool(key: String): Boolean? {
        val item = map[key] ?: return null
        // 类型宽容（P2-6）：数值项按非零即真自适应，杜绝 as Boolean 的 ClassCastException
        return when (val value = item.value) {
            is Boolean -> value
            is Int -> value != 0
            is Long -> value != 0L
            else -> throw KdbxCorruptFileException(
                "VariantDictionary 类型错误: $key 期望 Bool，实际 ${typeNameOf(item.value)}"
            )
        }
    }

    fun setInt32(key: String, value: Int) {
        map[key] = Item(TYPE_INT32, value)
    }

    fun getInt32(key: String): Int? {
        val item = map[key] ?: return null
        return when (val value = item.value) {
            is Int -> value
            is Long -> value.toInt()
            else -> throw KdbxCorruptFileException(
                "VariantDictionary 类型错误: $key 期望 Int32，实际 ${typeNameOf(item.value)}"
            )
        }
    }

    fun setInt64(key: String, value: Long) {
        map[key] = Item(TYPE_INT64, value)
    }

    fun getInt64(key: String): Long? {
        val item = map[key] ?: return null
        return when (val value = item.value) {
            is Long -> value
            is Int -> value.toLong()
            else -> throw KdbxCorruptFileException(
                "VariantDictionary 类型错误: $key 期望 Int64，实际 ${typeNameOf(item.value)}"
            )
        }
    }

    fun setString(key: String, value: String) {
        map[key] = Item(TYPE_STRING, value)
    }

    fun getString(key: String): String? {
        val item = map[key] ?: return null
        // P2-6：杜绝 as String 的 ClassCastException，类型不符按损坏文件裁决
        return when (val value = item.value) {
            is String -> value
            else -> throw KdbxCorruptFileException(
                "VariantDictionary 类型错误: $key 期望 String，实际 ${typeNameOf(item.value)}"
            )
        }
    }

    fun setByteArray(key: String, value: ByteArray) {
        map[key] = Item(TYPE_BYTE_ARRAY, value.clone())
    }

    fun getByteArray(key: String): ByteArray? {
        val item = map[key] ?: return null
        // P2-6：杜绝 as ByteArray 的 ClassCastException，类型不符按损坏文件裁决
        return when (val value = item.value) {
            is ByteArray -> value.clone()
            else -> throw KdbxCorruptFileException(
                "VariantDictionary 类型错误: $key 期望 ByteArray，实际 ${typeNameOf(item.value)}"
            )
        }
    }

    fun containsKey(key: String): Boolean = map.containsKey(key)

    fun serialize(outputStream: OutputStream) {
        LittleEndianUtil.writeShort(outputStream, VERSION)
        for ((key, item) in map) {
            val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
            outputStream.write(item.type.toInt())
            LittleEndianUtil.writeInt(outputStream, keyBytes.size)
            outputStream.write(keyBytes)

            when (item.type) {
                TYPE_UINT32 -> {
                    LittleEndianUtil.writeInt(outputStream, 4)
                    LittleEndianUtil.writeInt(outputStream, item.value as Int)
                }
                TYPE_UINT64 -> {
                    LittleEndianUtil.writeInt(outputStream, 8)
                    LittleEndianUtil.writeLong(outputStream, item.value as Long)
                }
                TYPE_BOOL -> {
                    LittleEndianUtil.writeInt(outputStream, 1)
                    outputStream.write(if (item.value as Boolean) 1 else 0)
                }
                TYPE_INT32 -> {
                    LittleEndianUtil.writeInt(outputStream, 4)
                    LittleEndianUtil.writeInt(outputStream, item.value as Int)
                }
                TYPE_INT64 -> {
                    LittleEndianUtil.writeInt(outputStream, 8)
                    LittleEndianUtil.writeLong(outputStream, item.value as Long)
                }
                TYPE_STRING -> {
                    val strBytes = (item.value as String).toByteArray(StandardCharsets.UTF_8)
                    LittleEndianUtil.writeInt(outputStream, strBytes.size)
                    outputStream.write(strBytes)
                }
                TYPE_BYTE_ARRAY -> {
                    val bytes = item.value as ByteArray
                    LittleEndianUtil.writeInt(outputStream, bytes.size)
                    outputStream.write(bytes)
                }
            }
        }
        outputStream.write(TYPE_NONE.toInt())
    }

    fun toByteArray(): ByteArray {
        val bos = ByteArrayOutputStream()
        serialize(bos)
        return bos.toByteArray()
    }

    companion object {
        const val VERSION: Short = 0x0100
        const val TYPE_NONE: Byte = 0x00
        const val TYPE_UINT32: Byte = 0x04
        const val TYPE_UINT64: Byte = 0x05
        const val TYPE_BOOL: Byte = 0x08
        const val TYPE_INT32: Byte = 0x0C
        const val TYPE_INT64: Byte = 0x0D
        const val TYPE_STRING: Byte = 0x18
        const val TYPE_BYTE_ARRAY: Byte = 0x42

        /**
         * 变体字典 key 长度安全上限：合法 key 均为短标识（如 "$UUID"、"S"、"M"、"I"），
         * 空或超长 key 视为损坏文件（P0-5：keyLen 未认证，严禁直接驱动分配）。
         */
        const val MAX_KEY_LENGTH = 256

        /** 变体字典 value 长度安全上限（1 MiB）：KDF 种子/盐值与自定义数据远小于此 */
        const val MAX_VALUE_LENGTH = 1024 * 1024

        fun deserialize(inputStream: InputStream): VariantDictionary {
            val dict = VariantDictionary()
            val version = LittleEndianUtil.readShort(inputStream)
            if ((version.toInt() and 0xFF00) > (VERSION.toInt() and 0xFF00)) {
                throw IOException("不支持的 VariantDictionary 版本: 0x${Integer.toHexString(version.toInt())}")
            }

            while (true) {
                val type = inputStream.read()
                if (type < 0 || type.toByte() == TYPE_NONE) {
                    break
                }

                // P0-5：keyLen / valLen 均来自未认证的文件字节，进入分配前必须先过边界裁决
                val keyLen = LittleEndianUtil.readInt(inputStream)
                if (keyLen < 1 || keyLen > MAX_KEY_LENGTH) {
                    throw KdbxCorruptFileException(
                        "变体字典 key 长度非法或超过安全上限: keyLen=$keyLen（允许 1 ~ $MAX_KEY_LENGTH）"
                    )
                }
                val keyBytes = LittleEndianUtil.readBytes(inputStream, keyLen, MAX_KEY_LENGTH)
                val key = String(keyBytes, StandardCharsets.UTF_8)

                val valLen = LittleEndianUtil.readInt(inputStream)
                if (valLen < 0 || valLen > MAX_VALUE_LENGTH) {
                    throw KdbxCorruptFileException(
                        "变体字典 value 长度非法或超过安全上限: key=$key, valLen=$valLen（允许 0 ~ $MAX_VALUE_LENGTH）"
                    )
                }
                val valBytes = LittleEndianUtil.readBytes(inputStream, valLen, MAX_VALUE_LENGTH)

                when (type.toByte()) {
                    TYPE_UINT32 -> {
                        requireFixedSizeValue(key, "UInt32", valBytes, 4)
                        dict.setUInt32(key, LittleEndianUtil.bytesToInt(valBytes).toLong() and 0xFFFFFFFFL)
                    }
                    TYPE_UINT64 -> {
                        requireFixedSizeValue(key, "UInt64", valBytes, 8)
                        dict.setUInt64(key, LittleEndianUtil.bytesToLong(valBytes))
                    }
                    TYPE_BOOL -> {
                        requireFixedSizeValue(key, "Bool", valBytes, 1)
                        dict.setBool(key, valBytes[0] != 0.toByte())
                    }
                    TYPE_INT32 -> {
                        requireFixedSizeValue(key, "Int32", valBytes, 4)
                        dict.setInt32(key, LittleEndianUtil.bytesToInt(valBytes))
                    }
                    TYPE_INT64 -> {
                        requireFixedSizeValue(key, "Int64", valBytes, 8)
                        dict.setInt64(key, LittleEndianUtil.bytesToLong(valBytes))
                    }
                    TYPE_STRING -> dict.setString(key, String(valBytes, StandardCharsets.UTF_8))
                    TYPE_BYTE_ARRAY -> dict.setByteArray(key, valBytes)
                    // 未知类型：按官方前向兼容语义消费并跳过（长度已受限，不构成分配风险）
                }
            }
            return dict
        }

        fun deserialize(bytes: ByteArray): VariantDictionary {
            return deserialize(ByteArrayInputStream(bytes))
        }

        /**
         * 定长类型（UInt32/Int32=4B、UInt64/Int64=8B、Bool=1B）的值长度校验：
         * 长度不符的项按损坏文件拒绝，杜绝 bytesToInt/bytesToLong 越界抛出未类型化异常。
         */
        private fun requireFixedSizeValue(key: String, typeName: String, value: ByteArray, expectedSize: Int) {
            if (value.size != expectedSize) {
                throw KdbxCorruptFileException(
                    "变体字典 $typeName 值长度非法: key=$key, length=${value.size}（期望 $expectedSize）"
                )
            }
        }
    }

    /** 类型名（用于类型错误消息，值类型受限于 set* 系列产生的封闭集合） */
    private fun typeNameOf(value: Any): String = value::class.simpleName ?: "未知类型"
}
