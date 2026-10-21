package com.keepasskey.app.sync

import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.s3.S3SyncProvider
import com.keepasskey.sync.webdav.WebDavSyncProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** 生产 Provider 构造与拥有实例的释放策略；测试可替换此接口并检查相同 CharArray 所有权边界。 */
interface SyncProviderFactory {
    fun createWebDav(
        serverUrl: String,
        username: String,
        passwordChars: CharArray,
        networkOptions: SyncNetworkOptions,
        transferOptions: SyncTransferOptions
    ): SyncProvider

    fun createS3(
        endpoint: String,
        bucketName: String,
        region: String,
        accessKeyId: CharArray,
        secretAccessKey: CharArray,
        usePathStyle: Boolean,
        networkOptions: SyncNetworkOptions,
        initialClockOffsetMillis: Long,
        clockOffsetUpdater: (Long) -> Unit
    ): SyncProvider

    fun releaseOwned(provider: SyncProvider)
}

@Singleton
class ProductionSyncProviderFactory @Inject constructor() : SyncProviderFactory {
    override fun createWebDav(
        serverUrl: String,
        username: String,
        passwordChars: CharArray,
        networkOptions: SyncNetworkOptions,
        transferOptions: SyncTransferOptions
    ): SyncProvider = WebDavSyncProvider(
        serverUrl = serverUrl,
        username = username,
        passwordChars = passwordChars,
        networkOptions = networkOptions,
        transferOptions = transferOptions
    )

    override fun createS3(
        endpoint: String,
        bucketName: String,
        region: String,
        accessKeyId: CharArray,
        secretAccessKey: CharArray,
        usePathStyle: Boolean,
        networkOptions: SyncNetworkOptions,
        initialClockOffsetMillis: Long,
        clockOffsetUpdater: (Long) -> Unit
    ): SyncProvider = S3SyncProvider(
        endpoint = endpoint,
        bucketName = bucketName,
        region = region,
        accessKeyId = accessKeyId,
        secretAccessKey = secretAccessKey,
        usePathStyle = usePathStyle,
        networkOptions = networkOptions,
        initialClockOffsetMillis = initialClockOffsetMillis,
        clockOffsetUpdater = clockOffsetUpdater
    )

    override fun releaseOwned(provider: SyncProvider) {
        (provider as? S3SyncProvider)?.clearCredentials()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncProviderFactoryBinding {
    @Binds
    abstract fun bindSyncProviderFactory(factory: ProductionSyncProviderFactory): SyncProviderFactory
}
