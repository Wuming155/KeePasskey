package com.keepasskey.sync.network

/**
 * 同步网络配置契约（app 层组装传入，维持 sync 不依赖 app 的单向依赖）。
 *
 * Wave 12 传输加固语义：
 * - **明文 HTTP 已整体下线**：本契约不含任何明文开关字段，工厂恒定 TLS-only 连接规格，
 *   平台默认禁明文（targetSdk 28+）成为唯一行为——「允许明文流量」为不安全功能，按安全决策移除；
 * - **证书锁定为可选防御纵深**：遵循 OkHttp 官方警示（锁定会限制服务端证书轮换，
 *   勿未经服务端 TLS 管理员同意强制启用），仅在用户显式配置时生效。
 */
data class SyncNetworkOptions(
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
    /** 可选证书锁定：host（小写） -> SPKI SHA-256 pin 列表（形如 "sha256/Base64=="） */
    val pinnedHosts: Map<String, List<String>> = emptyMap()
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_READ_TIMEOUT_MS = 30_000L
        const val DEFAULT_WRITE_TIMEOUT_MS = 30_000L
    }
}
