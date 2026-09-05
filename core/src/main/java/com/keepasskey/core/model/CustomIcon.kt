package com.keepasskey.core.model

/**
 * KDBX 数据库自定义图标模型。
 */
data class CustomIcon(
    val uuid: KdbxUuid,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomIcon) return false
        if (uuid != other.uuid) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "CustomIcon(uuid=$uuid, size=${data.size})"
    }
}
