package com.keepasskey.sync.network

/**
 * 同步网络配置契约（app 层组装传入，维持 sync 不依赖 app 的单向依赖）。
 *
 * Wave 14 传输安全语义：
 * - **明文 HTTP 已整体下线**：本契约不含任何明文开关字段，工厂恒定 TLS-only 连接规格，
 *   平台 Network Security Config 全局禁明文（cleartextTrafficPermitted="false"）为唯一行为——
 *   「允许明文流量」为不安全功能，按安全决策移除；
 * - **证书固定已整体移除（Wave 14）**：证书验证完全依赖 Android 系统默认 CA 链，不做任何
 *   SPKI 公钥锁定——锁定会阻碍云厂商常规证书轮换导致连接阻断，且本应用仅面向正规公网商业云服务
 *   （不支持自建服务器），无锁定必要。
 */
data class SyncNetworkOptions(
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
    // TASK-42 整改（P2-12）：全局调用超时（含 DNS），兜底封顶弱网悬挂
    val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        const val DEFAULT_READ_TIMEOUT_MS = 30_000L
        const val DEFAULT_WRITE_TIMEOUT_MS = 30_000L
        const val DEFAULT_CALL_TIMEOUT_MS = 300_000L
    }
}
