package com.keepasskey.app.sync

import com.keepasskey.sync.provider.SyncProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 测连只消费指定回执；取消原样传播，Provider 生命周期由 Resolver 收口。 */
internal suspend fun runSyncConnectionTest(
    providers: SyncProviderResolver,
    snapshot: SyncProviderConfigSnapshot?,
    testOverride: SyncProvider?,
    unavailableMessage: String
): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val result = if (snapshot == null) {
            providers.useProvider(testOverride) { it.testConnection() }
        } else {
            providers.useProviderAtSnapshot(snapshot, testOverride) { it.testConnection() }
        }
        result ?: Result.failure(IllegalStateException(unavailableMessage))
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    }
}
