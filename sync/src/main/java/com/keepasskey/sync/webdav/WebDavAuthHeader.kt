package com.keepasskey.sync.webdav

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
     * 已声明限界：最终 Authorization 头以 Base64 形态驻留 Provider 生命周期
     * （OkHttp header API 以 String 承载），与 S3 侧 SigV4 管线的 String 边界声明一致。
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
