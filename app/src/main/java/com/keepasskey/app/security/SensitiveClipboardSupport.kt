package com.keepasskey.app.security

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * ISSUE-P2-15：受保护剪贴板的只读 [CharSequence] 视图。
 *
 * Android [android.content.ClipData] 只接受 CharSequence，而受保护剪贴板的调用方持有的是
 * CharArray 独占副本。本视图直接以**借用的** CharArray 作为字符源，避免应用侧先把明文物化为
 * 不可擦除 String；真正跨进程写入系统服务时由 Android 框架序列化（系统边界，不在本类责任范围）。
 *
 * 调用契约：视图不复制数组，持有者在视图被消费（`setPrimaryClip` 同步完成序列化与摘要计算）
 * 之后方可清零底层数组。
 */
internal class SensitiveCharSequence(private val chars: CharArray) : CharSequence {

    override val length: Int
        get() = chars.size

    override fun get(index: Int): Char = chars[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        SensitiveCharSequence(chars.copyOfRange(startIndex, endIndex))

    /** 仅系统边界（框架序列化 / 调试）需要时才物化 String；业务代码不得依赖 */
    override fun toString(): String = String(chars)
}

/**
 * ISSUE-P2-15：对 CharSequence 直接做 UTF-8 SHA-256，不经过 `toString()`。
 *
 * 与 `String.toByteArray(UTF_8)` 的替换语义一致（非法代理对以 '?' 替换），因此 CharArray 通道
 * 与 String 通道对同一内容得到相同摘要——剪贴板自动清空的「当前内容是否仍为先前复制值」比对
 * 不受通道差异影响。
 */
internal fun sensitiveTextSha256(text: CharSequence): ByteArray {
    val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(text))
    val bytes = ByteArray(encoded.remaining())
    encoded.get(bytes)
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes)
    } finally {
        bytes.fill(0)
        if (encoded.hasArray()) encoded.array().fill(0)
    }
}
