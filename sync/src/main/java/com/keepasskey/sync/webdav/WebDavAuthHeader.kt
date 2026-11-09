package com.keepasskey.sync.webdav

import androidx.annotation.VisibleForTesting
import com.keepasskey.sync.model.SyncException
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * WebDAV Basic 认证头构造器（敏感数据铁律唯一落点）。
 *
 * 凭据拼接全程不经 String：password 以 CharBuffer 直转 UTF-8 字节参与 "user:pass"
 * 拼接；函数独占的编码缓冲与拼接缓冲均在 finally 中擦除。
 */
internal object WebDavAuthHeader {

    /**
     * 手工构造 Basic 认证头，密码全程不经 String（敏感数据铁律）。
     *
     * TASK-25 整改：凭据统一按 UTF-8 编码（TASK-25 前对齐 OkHttp Credentials.basic 的
     * RFC 7617 默认 charset ISO-8859-1——非 ASCII（中文）密码被错误转码，主流 WebDAV
     * 服务端（Nextcloud/ownCloud/坚果云等 sabre 系实现按 UTF-8 解码）鉴权必然 401）。
     * username 属非机密标识符可走 String；password 以 CharBuffer 直转 UTF-8 字节
     * 参与 "user:pass" 凭据拼接，不创建额外的密码字节副本。编码缓冲与拼接缓冲
     * 在 finally 中擦除。
     * （RFC 7617 §2.1 的 charset 参数仅存在于服务端 WWW-Authenticate 挑战侧，
     * 请求侧 Authorization 头无声明机制，故仅改编码不附参数。）
     * 驻留边界（ENG-20261028-42）：本函数只在调用瞬间构造头，不自行驻留；头的持有周期由持有方决定。
     * 配套载体 [WebDavAuthCredentials] 按请求现算头，并在完成、取消或配置撤销后确定性失效；
     * OkHttp header API 以 String 承载，单次请求作用域内仍存在不可清零副本——这是平台边界，
     * 不能当作已擦除状态。
     */
    fun build(username: String, passwordChars: CharArray): String {
        val encoded = withWipedPasswordBuffer(passwordChars) { passwordBuffer ->
            val usernameBytes = username.toByteArray(StandardCharsets.UTF_8)
            val passwordLength = passwordBuffer.remaining()
            val combinedLength = Math.addExact(Math.addExact(usernameBytes.size, 1), passwordLength)
            withWipedCredentialBytes(combinedLength) { combined ->
                System.arraycopy(usernameBytes, 0, combined, 0, usernameBytes.size)
                combined[usernameBytes.size] = ':'.code.toByte()
                passwordBuffer.get(combined, usernameBytes.size + 1, passwordLength)
                Base64.getEncoder().encodeToString(combined)
            }
        }
        return "Basic $encoded"
    }
}

/**
 * 以作用域约束 UTF-8 密码缓冲的所有权，正常返回和异常退出均清除其底层数组。
 * internal 可供同模块契约测试观察清除后的底层数组；不向调用方返回秘密缓冲。
 */
internal inline fun <T> withWipedPasswordBuffer(
    passwordChars: CharArray,
    block: (java.nio.ByteBuffer) -> T
): T {
    val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passwordChars))
    try {
        return block(buffer)
    } finally {
        if (buffer.hasArray()) {
            buffer.array().fill(0)
        } else {
            val writable = buffer.duplicate()
            writable.clear()
            while (writable.hasRemaining()) writable.put(0)
        }
    }
}

/**
 * 为拼接凭据分配独占字节数组，并在作用域退出时清除。分配器仅用于确定性测试分配失败路径。
 */
internal inline fun <T> withWipedCredentialBytes(
    size: Int,
    allocate: (Int) -> ByteArray = { ByteArray(it) },
    block: (ByteArray) -> T
): T {
    val bytes = allocate(size)
    try {
        return block(bytes)
    } finally {
        bytes.fill(0)
    }
}

/**
 * WebDAV Basic 认证凭据载体（ENG-20261028-42）。
 *
 * 替代「构造时算出一个 Basic 头并随 Provider 常驻」：本载体只保留口令的**独占副本**，
 * 认证头由 [authorizationHeader] 在每次请求前现算，不可擦的 String 因此只出现在单次请求
 * 作用域，而不是 Provider 的整个生命周期。
 *
 * 接入契约：持有方（[WebDavSyncProvider]）每请求取头，并在释放路径调用 [invalidate]——生产接线见
 * 该类 KDoc 的「凭据持有与释放周期」：作用域退出（`SyncProviderFactory.releaseOwned`）、
 * 会话终止 / 换引擎（`SyncEngine.releaseProviderCredentials`）与一次性用途的 `finally`。
 *
 * 所有权：构造时复制入参口令数组（独立副本），调用方仍可按既有约定在构造返回后立即
 * 擦除借用数组；副本由本对象唯一持有，[invalidate] 或 [close] 时确定性清零。
 *
 * 线程安全：Provider 实例可能被并发请求共享，取头与失效在同一临界区完成——失效后不会
 * 产生「用户名 + 空口令」之类的错误凭据头，而是抛 [SyncException.AuthenticationError]。
 */
internal class WebDavAuthCredentials(
    private val username: String,
    passwordChars: CharArray
) : AutoCloseable {

    private val lock = Any()

    /** 独占口令副本；失效后置 null，作为唯一失效信号。 */
    private var password: CharArray? = passwordChars.copyOf()

    /** 本次请求的 Basic 认证头；已失效时拒绝构造（fail-closed，不以空口令降级）。 */
    fun authorizationHeader(): String = synchronized(lock) {
        val owned = password ?: throw SyncException.AuthenticationError(
            "WebDAV 凭据已失效，拒绝继续构造认证头"
        )
        WebDavAuthHeader.build(username, owned)
    }

    /** 确定性失效：按全仓凭据数组口径填充并拒绝后续取头；幂等。 */
    fun invalidate() {
        synchronized(lock) {
            password?.fill('0')
            password = null
        }
    }

    override fun close() = invalidate()

    /**
     * 仅供同模块契约测试观察副本是否被确定性清零；返回本对象持有的同一数组，不复制。
     */
    @VisibleForTesting
    internal fun retainedPasswordBufferForTest(): CharArray? =
        synchronized(lock) { password }
}
