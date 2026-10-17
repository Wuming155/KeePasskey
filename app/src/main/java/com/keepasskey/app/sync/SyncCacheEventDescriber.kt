package com.keepasskey.app.sync

import com.keepasskey.sync.engine.SyncCacheEvent

/**
 * `ICacheSupervisor` 六事件的可读描述（`ISSUE-P2-645`：自 `SyncCoordinator` 拆分）。
 *
 * 普通模式只记摘要行；详细模式追加事件类型与远端路径。所有输出都不含凭据/内容明文。
 */
internal object SyncCacheEventDescriber {

    fun describe(event: SyncCacheEvent): String = when (event) {
        is SyncCacheEvent.UpdatedCachedFileOnLoad -> "UpdatedCachedFileOnLoad path=${event.remotePath}"
        is SyncCacheEvent.UpdatedRemoteFileOnLoad -> "UpdatedRemoteFileOnLoad path=${event.remotePath}"
        is SyncCacheEvent.OpenedFromLocalDueToConflict -> "OpenedFromLocalDueToConflict path=${event.remotePath}"
        is SyncCacheEvent.LoadedFromRemoteInSync -> "LoadedFromRemoteInSync path=${event.remotePath}"
        is SyncCacheEvent.CouldntSaveToRemote -> "CouldntSaveToRemote path=${event.remotePath}"
        is SyncCacheEvent.CouldntOpenFromRemote -> "CouldntOpenFromRemote path=${event.remotePath}"
    }

    /** ISSUE-P3-03 (43f)：详细模式下追加事件类型与远端路径（普通模式只记摘要行）。 */
    fun describeVerbose(event: SyncCacheEvent): String = when (event) {
        is SyncCacheEvent.CouldntSaveToRemote ->
            "CouldntSaveToRemote path=${event.remotePath} cause=${event.cause?.javaClass?.simpleName}"
        is SyncCacheEvent.CouldntOpenFromRemote ->
            "CouldntOpenFromRemote path=${event.remotePath} cause=${event.cause?.javaClass?.simpleName}"
        else -> describe(event)
    }
}
