package com.keepasskey.app.di

import com.keepasskey.app.passkey.DalEndpointResolver
import com.keepasskey.app.passkey.DigitalAssetLinksVerifier
import com.keepasskey.app.passkey.MillisClock
import com.keepasskey.sync.network.SyncHttpClientFactory
import com.keepasskey.sync.network.SyncNetworkOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * DAL 出口 HTTP 客户端的限定符（ISSUE-P3-124）。
 *
 * 应用图里已有一个**无限定**的 `OkHttpClient` 绑定（`BreachCheckModule`，用途与信任边界都不同），
 * 故本出口必须自带限定符，避免两条出路的超时 / 守卫配置被混用。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DalHttpClient

/**
 * Digital Asset Links 校验器的策略注入模块（ISSUE-P3-125② / ISSUE-P3-124）。
 *
 * 该模块是这些「策略」在生产图中的**唯一**来源，且只提供**唯一实现**：
 * - [DalEndpointResolver] → 官方 well-known 路径；
 * - [MillisClock] → 系统时钟；
 * - `@DalHttpClient OkHttpClient` → **加固出口**（ISSUE-P3-124）。
 *
 * 由此，「把 DAL 拉取重定向到任意端点」在运行期**没有入口**——该能力只存在于
 * 单测的构造参数里（单测直接 `DigitalAssetLinksVerifier(fakeResolver, fakeClock, testClient)`，
 * 不经 Hilt）。对照旧实现：`@Singleton` 上的 `@Volatile internal var endpointOverride`
 * 可被同模块任意生产代码改写。
 */
@Module
@InstallIn(SingletonComponent::class)
object DalVerifierModule {

    @Provides
    @Singleton
    fun provideDalEndpointResolver(): DalEndpointResolver = DalEndpointResolver.Official

    @Provides
    @Singleton
    fun provideMillisClock(): MillisClock = MillisClock.SystemClock

    /**
     * DAL 出口客户端（**ISSUE-P3-124**：不再由校验器自建裸 `OkHttpClient`）。
     *
     * ## 为什么必须复用同步侧的加固工厂
     *
     * 本出口的目标 host 来自**调用方影响面**（rp.id / webDomain），属典型的 SSRF 关注面。
     * 整改前校验器自建 `OkHttpClient.Builder()` + 三个超时，缺两样纵深防御：
     * - **TLS-only 声明**：未固定 `connectionSpecs`，明文回退只能靠平台 Network Security Config
     *   兜底（单层防御）；现由 `RESTRICTED_TLS` + `MODERN_TLS` **显式排除 `CLEARTEXT`**；
     * - **SSRF / DNS 重绑定守卫**：不校验解析结果，`https://<域名>` 可解析到环回 / 链路本地
     *   （含 `169.254.169.254` 云元数据）/ RFC1918 / ULA 等内网地址；现由 `SsrfGuardDns` 在
     *   **连接期**拦截，并抵御「先公网、后内网」的重绑定。
     *
     * **`ssrfAllowedHosts` 保持默认空集**（刻意为之）：`ISSUE-P3-121` 的自建内网 WebDAV 逃生通道
     * **不适用**于 DAL——RP 的 `assetlinks.json` 只可能来自公网站点，放行内网目标纯属扩大攻击面。
     *
     * 超时沿用校验器既有的紧预算常量（注册流程有系统 5s 级约束），仅覆盖工厂默认值。
     */
    @Provides
    @Singleton
    @DalHttpClient
    fun provideDalHttpClient(): OkHttpClient = SyncHttpClientFactory.createSyncClient(
        SyncNetworkOptions(
            connectTimeoutMs = DigitalAssetLinksVerifier.CONNECT_TIMEOUT_MS,
            readTimeoutMs = DigitalAssetLinksVerifier.READ_TIMEOUT_MS,
            writeTimeoutMs = DigitalAssetLinksVerifier.READ_TIMEOUT_MS,
            callTimeoutMs = DigitalAssetLinksVerifier.CALL_TIMEOUT_MS
        )
    )
}
