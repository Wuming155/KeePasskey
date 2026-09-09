package com.keepasskey.app.di

import com.keepasskey.app.sync.SyncCacheEvictor
import com.keepasskey.database.session.DatabaseSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
     */
    @Provides
    @Singleton
    fun provideDatabaseSession(cacheEvictor: SyncCacheEvictor): DatabaseSession {
        return DatabaseSession().apply { addLockObserver(cacheEvictor) }
    }
}
