package com.keepasskey.app.sync

import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.provider.CredentialReleasingProvider
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.s3.S3SyncProvider
import com.keepasskey.sync.webdav.WebDavSyncProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 生产 Provider 构造与拥有实例的释放策略；测试可替换此接口并检查相同 CharArray 所有权边界。
 *
 * ENG-20261028-42：[releaseOwned] 是**作用域所有权**的收口——生产实现按可选能力
 * [CredentialReleasingProvider] 释放，并对尚未迁移到该接口的协议（S3）保留显式类型分支，
 * 避免两套协议的凭据生命周期出现不对称。
 */
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
        // ENG-20261028-42：能力优先（WebDAV 的 WebDavAuthCredentials 失效载体即此路径）。
        // 尚未实现该可选能力的协议按既有显式类型收口——S3 的 clearCredentials 与作用域所有权
        // 语义一致，迁移到 CredentialReleasingProvider 后此处分支即可删除。
        when (provider) {
            is CredentialReleasingProvider -> provider.releaseCredentials()
            is S3SyncProvider -> provider.clearCredentials()
            else -> Unit
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncProviderFactoryBinding {
    @Binds
    abstract fun bindSyncProviderFactory(factory: ProductionSyncProviderFactory): SyncProviderFactory
}
