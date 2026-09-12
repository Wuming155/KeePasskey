package com.keepasskey.app.di

import android.content.Context
import com.keepasskey.app.data.binary.FileBinaryStore
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.sync.SyncCacheEvictor
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncRollbackGuard
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * F-23 整改：防回滚状态目录限定符。
 *
 * 存在的意义是把「跨会话安全状态目录」与「可丢弃缓存目录」在类型层面区分开——
 * 二者都是 [File]，但生命周期截然相反：`cacheDir` 下的内容锁定即清，
 * [SyncRollbackGuard] 的状态必须跨锁定保留。无限定符的裸 [File] 注入会在未来
 * 出现第二个目录时静默接错，重演 F-23（状态被锁库清理连带删除）。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RollbackStateDir

/**
 * 数据库模块依赖注入提供者
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * ISSUE-P1-07：装配期即把同步缓存销毁器注册为会话终止观察者——
     * 任何经 [DatabaseSession.lock] / [DatabaseSession.close] 的锁库路径
     * （手动锁定、熄屏熔断、后台超时、切换密码库）都会连带销毁 `cacheDir/sync` 的
     * KDBX 密文快照，使「锁定」在数据生命周期上真正闭环。
     *
     * ISSUE-P2-11 (ZT-16)：同时把持久化的「保存前创建 .bak 备份」偏好注入唯一会话实例，
     * 使该设置项真实生效。`ExtendedSettingsStore.load()` 为同步 API，装配期一次性下发；
     * 之后用户切换开关由 SettingsViewModel 实时同步到同一会话实例。
     */
    @Provides
    @Singleton
    fun provideDatabaseSession(
        cacheEvictor: SyncCacheEvictor,
        extendedSettingsStore: ExtendedSettingsStore,
        binaryStore: FileBinaryStore
    ): DatabaseSession {
        return DatabaseSession(binaryStore).apply {
            addLockObserver(cacheEvictor)
            // ISSUE-P2-24：大附件磁盘缓存同属会话派生敏态数据，锁定/关闭时一并清空
            addLockObserver(binaryStore)
            createBackupBeforeSave = extendedSettingsStore.load().createBackupBeforeSave
        }
    }

    /**
     * F-23 整改：防回滚高水位状态目录（`filesDir/<SyncRollbackGuard.STATE_DIR_NAME>`）。
     *
     * 该目录承载由 Keystore HMAC 认证的「已见内容摘要链」（仅 SHA-256 摘要 + MAC，**无明文**），
     * 是 Assume Breach（云端不可信）下唯一的重放防线，必须**跨会话锁定保留**。
     * 整改前它与 `cacheDir/sync` 同目录，被 [SyncCacheEvictor.onSessionLocked] →
     * `SyncCache.clearAll()` 在每次锁库时连根清除，云侧只需等用户锁定一次即可重放旧库。
     *
     * 交由 DI 单点提供（而非各消费方自拼字面量），确保 `SyncCycleRunner` 与任何未来的
     * 状态消费者共享同一落点，不会各自漂移回 `cacheDir`。
     */
    @Provides
    @Singleton
    @RollbackStateDir
    fun provideRollbackStateDir(@ApplicationContext context: Context): File =
        File(context.filesDir, SyncRollbackGuard.STATE_DIR_NAME)
}
