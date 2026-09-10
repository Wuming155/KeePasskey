package com.keepasskey.app.di

import com.keepasskey.app.autofill.HmacFieldSignatureSource
import com.keepasskey.app.autofill.KeystoreHmacFieldSignatureSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 自动填充域依赖注入模块（ISSUE-P3-46）。
 *
 * 把字段签名密钥来源 [HmacFieldSignatureSource] 绑定到 Android Keystore 实现
 * [KeystoreHmacFieldSignatureSource]（密钥不可导出、抗离线枚举）；
 * JVM 单测直接注入假密钥来源构造 `AutofillFieldBlocklistStore`，无需真机 Keystore。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AutofillModule {

    @Binds
    @Singleton
    abstract fun bindHmacFieldSignatureSource(
        impl: KeystoreHmacFieldSignatureSource
    ): HmacFieldSignatureSource
}
