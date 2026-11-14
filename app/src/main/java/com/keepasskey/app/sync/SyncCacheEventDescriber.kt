package com.keepasskey.app.sync

import com.keepasskey.sync.engine.SyncCacheEvent

/**
 * `ICacheSupervisor` 六事件的可读描述（`ISSUE-P2-645`：自 `SyncCoordinator` 拆分）。
 *
 * 普通模式只记摘要行；详细模式追加事件类型与远端路径**摘要**。远端路径含目录、库名与主机，
 * 属日志脱敏盲区，故两类模式都不输出原文，只输出可关联同一目标的短摘要。
 */
internal object SyncCacheEventDescriber {

    fun describe(event: SyncCacheEvent): String = when (event) {
        is SyncCacheEvent.UpdatedCachedFileOnLoad -> "UpdatedCachedFileOnLoad path=${RemotePathDigest.of(event.remotePath)}"
        is SyncCacheEvent.UpdatedRemoteFileOnLoad -> "UpdatedRemoteFileOnLoad path=${RemotePathDigest.of(event.remotePath)}"
        is SyncCacheEvent.OpenedFromLocalDueToConflict -> "OpenedFromLocalDueToConflict path=${RemotePathDigest.of(event.remotePath)}"
        is SyncCacheEvent.LoadedFromRemoteInSync -> "LoadedFromRemoteInSync path=${RemotePathDigest.of(event.remotePath)}"
        is SyncCacheEvent.CouldntSaveToRemote -> "CouldntSaveToRemote path=${RemotePathDigest.of(event.remotePath)}"
        is SyncCacheEvent.CouldntOpenFromRemote -> "CouldntOpenFromRemote path=${RemotePathDigest.of(event.remotePath)}"
    }

    /** ISSUE-P3-03 (43f)：详细模式下追加事件类型与远端路径摘要（普通模式只记摘要行）。 */
    fun describeVerbose(event: SyncCacheEvent): String = when (event) {
        is SyncCacheEvent.CouldntSaveToRemote ->
            "CouldntSaveToRemote path=${RemotePathDigest.of(event.remotePath)} cause=${event.cause?.javaClass?.simpleName}"
        is SyncCacheEvent.CouldntOpenFromRemote ->
            "CouldntOpenFromRemote path=${RemotePathDigest.of(event.remotePath)} cause=${event.cause?.javaClass?.simpleName}"
        else -> describe(event)
    }
}

/**
 * 远端路径的日志摘要：同一路径在同一份导出日志内可关联，但不还原出目录、库名或主机。
 * 取 SHA-256 前 8 字节十六进制；仅用于日志，不参与任何同步判定。
 */
internal object RemotePathDigest {

    fun of(remotePath: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(remotePath.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            "${HEX_DIGITS[value shr 4]}${HEX_DIGITS[value and 0x0F]}"
        }
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
