package com.keepasskey.core.model

import java.time.Instant

/**
 * KDBX 删除对象墓碑记录。
 * 用于跨设备同步时识别单边删除对象，防止被对端错误复活。
 */
data class DeletedObject(
    val id: KdbxUuid,
    val deletionTime: Instant = Instant.now()
)
