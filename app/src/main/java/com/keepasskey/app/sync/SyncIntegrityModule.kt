package com.keepasskey.app.sync

import com.keepasskey.sync.engine.SyncIntegrityMac
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 同步防回滚完整性认证的 Hilt 绑定（ISSUE-P2-18）。
 *
 * 将 `sync` 层定义的 [SyncIntegrityMac] 抽象绑定到 AndroidKeystore HMAC 实现
 * [KeystoreSyncIntegrityMac]（不可导出密钥，锁屏态后台同步亦可用）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncIntegrityModule {

    @Binds
    @Singleton
    abstract fun bindSyncIntegrityMac(
        impl: KeystoreSyncIntegrityMac
    ): SyncIntegrityMac
}
