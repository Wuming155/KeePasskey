package com.keepasskey.core.model

import java.time.Instant

/**
 * KDBX 条目与分组的时间属性元数据
 */
data class KdbxTimes(
    val creationTime: Instant = Instant.now(),
    val lastModificationTime: Instant = creationTime,
    val lastAccessTime: Instant = creationTime,
    val expiryTime: Instant = creationTime,
    val expires: Boolean = false,
    val usageCount: Long = 0L,
    val locationChanged: Instant = creationTime
) {
    fun withModified(now: Instant = Instant.now()): KdbxTimes {
        return copy(
            lastModificationTime = now,
            lastAccessTime = now,
            usageCount = usageCount + 1
        )
    }

    fun withLocationChanged(now: Instant = Instant.now()): KdbxTimes {
        return copy(
            lastModificationTime = now,
            locationChanged = now
        )
    }
}
