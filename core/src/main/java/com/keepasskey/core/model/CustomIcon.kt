package com.keepasskey.core.model

import java.time.Instant

/**
 * KDBX 数据库自定义图标模型。
 *
 * [name] 与 [lastModificationTime] 为官方 KDBX 4.1 追加字段
 * （`CustomIcon/Name`、`CustomIcon/LastModificationTime`，见 KeePass 2.61.1
 * `KdbxFile.Write.cs:697-703` 与 `KdbxFile.Read.Streamed.cs:312-315`），
 * 缺省分别为空串与 `null`，表示文件未写出该字段。
 */
data class CustomIcon(
    val uuid: KdbxUuid,
    val data: ByteArray,
    /** 图标名（官方 KDBX 4.1 `CustomIcon/Name`）；未设置时为空串，写出时省略该元素 */
    val name: String = "",
    /** 图标最后修改时间（官方 KDBX 4.1 `CustomIcon/LastModificationTime`）；未设置时为 `null` */
    val lastModificationTime: Instant? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomIcon) return false
        if (uuid != other.uuid) return false
        if (name != other.name) return false
        if (lastModificationTime != other.lastModificationTime) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (lastModificationTime?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "CustomIcon(uuid=$uuid, size=${data.size}, name=$name, lastModificationTime=$lastModificationTime)"
    }
}
