package com.keepasskey.app.security

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 运行完整性风险闸门的 Hilt 绑定（ISSUE-P2-08）。
 *
 * 将抽象 [RuntimeIntegrityGate] 绑定到唯一实现 [RuntimeIntegrityDetector]，
 * 使生物识别快速解锁与自动填充等高层模块只依赖抽象、可被 JVM 单测替换。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeIntegrityModule {

    @Binds
    @Singleton
    abstract fun bindRuntimeIntegrityGate(
        impl: RuntimeIntegrityDetector
    ): RuntimeIntegrityGate
}
