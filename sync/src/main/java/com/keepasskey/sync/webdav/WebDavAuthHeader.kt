package com.keepasskey.sync.webdav

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * WebDAV Basic 认证头构造器（敏感数据铁律唯一落点）。
 *
 * 凭据拼接全程不经 String：password 以 CharBuffer 直转 UTF-8 字节参与 "user:pass"
 * 拼接，Base64 编码后中间字节数组立即擦除。
 */
internal object WebDavAuthHeader {

    /**
     * 手工构造 Basic 认证头，密码全程不经 String（敏感数据铁律）。
     *
     * TASK-25 整改：凭据统一按 UTF-8 编码（TASK-25 前对齐 OkHttp Credentials.basic 的
     * RFC 7617 默认 charset ISO-8859-1——非 ASCII（中文）密码被错误转码，主流 WebDAV
     * 服务端（Nextcloud/ownCloud/坚果云等 sabre 系实现按 UTF-8 解码）鉴权必然 401）。
     * username 属非机密标识符可走 String；password 以 CharBuffer 直转 UTF-8 字节
     * 参与 "user:pass" 凭据拼接，Base64 编码后中间字节数组立即擦除。
     * （RFC 7617 §2.1 的 charset 参数仅存在于服务端 WWW-Authenticate 挑战侧，
     * 请求侧 Authorization 头无声明机制，故仅改编码不附参数。）
     * 已声明限界：最终 Authorization 头以 Base64 形态驻留 Provider 生命周期
     * （OkHttp header API 以 String 承载），与 S3 侧 SigV4 管线的 String 边界声明一致。
     */
    fun build(username: String, passwordChars: CharArray): String {
        val passwordBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passwordChars))
        val encoded = try {
            val passwordBytes = ByteArray(passwordBuffer.remaining())
            passwordBuffer.get(passwordBytes)
            val combined = username.toByteArray(StandardCharsets.UTF_8) +
                    byteArrayOf(':'.code.toByte()) +
                    passwordBytes
            try {
                Base64.getEncoder().encodeToString(combined)
            } finally {
                combined.fill(0)
            }
        } finally {
            if (passwordBuffer.hasArray()) passwordBuffer.array().fill(0)
        }
        return "Basic $encoded"
    }
}
