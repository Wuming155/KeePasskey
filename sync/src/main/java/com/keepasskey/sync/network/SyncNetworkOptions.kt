package com.keepasskey.sync.network

/**
 * 同步网络配置契约（app 层组装传入，维持 sync 不依赖 app 的单向依赖）。
 *
 * Wave 14 传输安全语义：
 * - **明文 HTTP 已整体下线**：本契约不含任何明文开关字段，工厂恒定 TLS-only 连接规格，
 *   平台 Network Security Config 全局禁明文（cleartextTrafficPermitted="false"）为唯一行为——
 *   「允许明文流量」为不安全功能，按安全决策移除；
 * - **证书固定已整体移除（Wave 14）**：证书验证完全依赖 Android 系统默认 CA 链，不做任何
 *   SPKI 公钥锁定——锁定会阻碍云厂商常规证书轮换导致连接阻断，无锁定必要。
 * - **端点口径（ISSUE-P2-425）**：仅 HTTPS（明文全拒）；自建 / 内网 HTTPS 端点默认可用，
 *   重定向 / 解析到的非预期内网地址受连接期 SSRF 守卫拦截（见 [SyncEndpointGuard]）。
 */
data class SyncNetworkOptions(
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
    // TASK-42 整改（P2-12）：全局调用超时（含 DNS），兜底封顶弱网悬挂
    val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    /**
     * ISSUE-P1-05（ZT-05）SSRF 防护显式白名单豁免（默认空 = 不豁免任何主机）。
     * ISSUE-P2-425 后的定位：**高级逃生通道**（如合法重定向落到自建内网等边缘情形）——
     * 自建 / 内网 HTTPS 端点经构造期放宽已默认可用，无需走本参数；命中的主机在连接期
     * 豁免 [SsrfGuardDns] 解析网段校验（豁免不延伸到云元数据红线）；生产默认恒为空。
     */
    val ssrfAllowedHosts: Set<String> = emptySet(),
    /**
     * ISSUE-P3-298 ③：请求级瞬时错误重试的最大尝试次数（含首次）。
     * 仅作用于读请求与携带服务器预条件的写请求（见 [TransientHttpRetry] 安全边界）。
     */
    val transientRetryAttempts: Int = DEFAULT_TRANSIENT_RETRY_ATTEMPTS,
    /** 请求级重试的指数退避基准延迟（毫秒）：`base × 2^attempt`，封顶 [TransientHttpRetry.DEFAULT_MAX_DELAY_MS] */
    val transientRetryBaseDelayMs: Long = DEFAULT_TRANSIENT_RETRY_BASE_DELAY_MS
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_READ_TIMEOUT_MS = 30_000L
        const val DEFAULT_WRITE_TIMEOUT_MS = 30_000L
        const val DEFAULT_CALL_TIMEOUT_MS = 300_000L
        const val DEFAULT_TRANSIENT_RETRY_ATTEMPTS = 3
        const val DEFAULT_TRANSIENT_RETRY_BASE_DELAY_MS = 500L
    }
}
