package com.keepasskey.app.di

import com.keepasskey.app.data.breach.BreachRangeClient
import com.keepasskey.app.data.breach.BreachCheckCoordinator
import com.keepasskey.app.data.breach.HibpRangeClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 已泄露密码检测（TASK-47：HIBP k-匿名范围查询）依赖装配。
 *
 * 传输安全与 `SyncHttpClientFactory` 同策略（平台层 `network_security_config.xml` 禁明文 +
 * 本客户端 `ConnectionSpec` 排除 CLEARTEXT 的双层防御），信任锚仅系统 CA、不做证书固定。
 * 超时显式收口：泄露比对是用户主动触发的一次性扫描，弱网下不应无限悬挂。
 */
@Module
@InstallIn(SingletonComponent::class)
object BreachCheckModule {

    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val READ_TIMEOUT_MS = 20_000L
    private const val CALL_TIMEOUT_MS = 30_000L

    /** TLS-only：显式排除 CLEARTEXT，杜绝任何明文回退 */
    private val TLS_CONNECTION_SPECS = listOf(
        ConnectionSpec.RESTRICTED_TLS,
        ConnectionSpec.MODERN_TLS
    )

    @Provides
    @Singleton
    fun provideBreachHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(TLS_CONNECTION_SPECS)
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    fun provideBreachRangeClient(httpClient: OkHttpClient): BreachRangeClient =
        HibpRangeClient(httpClient, HibpRangeClient.DEFAULT_BASE_URL)

    @Provides
    @Singleton
    fun provideBreachCheckCoordinator(rangeClient: BreachRangeClient): BreachCheckCoordinator =
        BreachCheckCoordinator(rangeClient)
}
