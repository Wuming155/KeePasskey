package com.keepasskey.app.ui.screens.unlock

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * 快速解锁封印凭据的**载荷编解码**（ISSUE-P2-23）。
 *
 * 历史背景：封印载荷最初只承载主密码（纯 UTF-8 字节），导致「主密码 + 密钥文件」复合密钥库
 * 一旦登记快速解锁，解封出的主密码永远无法独立完成解锁——登记侧只能对带密钥文件的库
 * 整体跳过封印（用户表现为：带密钥文件解锁后指纹永久不可用）。本编解码器把载荷升级为
 * **版本化帧格式**，将主密码与密钥文件因子一并封印：
 *
 * ```text
 * 魔数 "KPB1"(4B) | 版本 0x01(1B) | 标志位(1B, bit0=携带密钥文件)
 * | 主密码长度 UInt32 BE(4B) | 主密码 UTF-8 字节
 * | [密钥文件长度 UInt32 BE(4B) | 密钥文件字节]   ← 仅标志位置位时存在
 * ```
 *
 * **向后兼容（旧格式）**：解封时载荷不以魔数开头即按历史格式解析（整体 = 主密码 UTF-8）。
 * 误判可能性分析：旧载荷须恰好以 ASCII `"KPB1"` 开头**且**版本/标志/两段长度与总长完全自洽，
 * 概率在工程上可忽略；即使命中也仅是把一段以 `"KPB1…"` 开头的主密码误判为帧格式导致解锁失败
 * → 走既有「清陈旧凭据 → 重新封印」恢复通道，不产生安全问题。
 *
 * 敏感数据铁律：所有中间缓冲（UTF-8 字节、解码产物）仅以 `ByteArray`/`CharArray` 驻留，
 * 编码用毕由调用方在 `finally` 中 `fill(0)` 显式清零；解码产物自带 [Decrypted.wipe]。
 * 本对象为纯 JVM 逻辑，可脱离 Android 直接单测。
 */
internal object BiometricSealedPayloadCodec {

    /** 帧格式魔数：ASCII "KPB1"（KeePasskey Biometric payload v1） */
    private val MAGIC = byteArrayOf(0x4B, 0x50, 0x42, 0x31)

    private const val FORMAT_VERSION = 1

    private const val FLAG_HAS_KEY_FILE = 0x01

    // 头部 = 魔数(4) + 版本(1) + 标志位(1)
    private const val HEADER_BYTES = 6

    /** 解码产物：主密码字符与（可选）密钥文件字节；持有方用毕必须 [wipe] */
    data class Decrypted(
        val passwordChars: CharArray,
        val keyFileData: ByteArray?
    ) {
        /** 显式清零全部敏感缓冲（密码字符 + 密钥文件字节） */
        fun wipe() {
            passwordChars.fill('0')
            keyFileData?.fill(0)
        }
    }

    /**
     * 编码封印载荷：主密码 + 可选密钥文件因子。
     * 返回的数组属敏感明文，调用方用毕（封印 `doFinal` 之后）必须 `fill(0)` 清零。
     */
    fun encode(passwordChars: CharArray, keyFileData: ByteArray?): ByteArray {
        val passwordBytes = StandardCharsets.UTF_8.encode(
            CharBuffer.wrap(passwordChars)
        )
        try {
            val pwdBytes = ByteArray(passwordBytes.remaining()).also { passwordBytes.get(it) }
            val hasKeyFile = keyFileData != null && keyFileData.isNotEmpty()
            val total = HEADER_BYTES + INT_BYTES + pwdBytes.size +
                if (hasKeyFile) INT_BYTES + keyFileData.size else 0
            val buffer = ByteBuffer.allocate(total)
            buffer.put(MAGIC)
            buffer.put(FORMAT_VERSION.toByte())
            buffer.put((if (hasKeyFile) FLAG_HAS_KEY_FILE else 0).toByte())
            buffer.putInt(pwdBytes.size)
            buffer.put(pwdBytes)
            if (hasKeyFile) {
                buffer.putInt(keyFileData.size)
                buffer.put(keyFileData)
            }
            return buffer.array()
        } finally {
            // UTF-8 中间缓冲显式清零（堆缓冲才有后备数组；直接缓冲 fill 后丢弃即可）
            if (passwordBytes.hasArray()) {
                passwordBytes.array().fill(0)
            } else {
                passwordBytes.clear()
            }
        }
    }

    /**
     * 解码封印载荷：优先按 v1 帧格式解析；不带魔数即回落历史格式（纯主密码 UTF-8）。
     * 帧结构不自洽（版本未知 / 标志非法 / 长度越界）时抛 [IllegalStateException]，
     * 由解封调用方按「凭据陈旧 / 损坏」清除并引导重新封印。
     */
    fun decode(payload: ByteArray): Decrypted {
        if (!startsWithMagic(payload)) {
            // 历史格式：整体为主密码 UTF-8，无密钥文件因子（P2-23 之前的存量封印凭据）
            return Decrypted(decodeUtf8Chars(payload), null)
        }
        val buffer = ByteBuffer.wrap(payload)
        buffer.position(MAGIC.size)
        val version = buffer.get().toInt() and 0xFF
        check(version == FORMAT_VERSION) { "未知的封印载荷版本: $version" }
        val flags = buffer.get().toInt() and 0xFF
        check(flags == 0 || flags == FLAG_HAS_KEY_FILE) { "非法的封印载荷标志位: $flags" }
        val hasKeyFile = flags == FLAG_HAS_KEY_FILE

        val pwdLength = readLength(buffer)
        check(pwdLength <= buffer.remaining()) { "主密码段长度越界: $pwdLength" }
        val pwdBytes = ByteArray(pwdLength).also { buffer.get(it) }
        try {
            val passwordChars = decodeUtf8Chars(pwdBytes)
            val keyFileData = if (hasKeyFile) {
                val kfLength = readLength(buffer)
                check(kfLength == buffer.remaining()) { "密钥文件段长度不自洽: $kfLength" }
                ByteArray(kfLength).also { buffer.get(it) }
            } else {
                null
            }
            return Decrypted(passwordChars, keyFileData)
        } finally {
            pwdBytes.fill(0)
        }
    }

    private fun startsWithMagic(payload: ByteArray): Boolean {
        if (payload.size < MAGIC.size) return false
        for (i in MAGIC.indices) {
            if (payload[i] != MAGIC[i]) return false
        }
        return true
    }

    private const val INT_BYTES = 4

    private fun readLength(buffer: ByteBuffer): Int {
        check(buffer.remaining() >= INT_BYTES) { "长度前缀越界" }
        val value = buffer.int
        check(value >= 0) { "非法负长度: $value" }
        return value
    }

    /** P1-13 整改同款：精确按 CharBuffer.remaining() 拷贝字符，杜绝后备数组尾零残留 */
    private fun decodeUtf8Chars(bytes: ByteArray): CharArray {
        val charBuffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(charBuffer.remaining())
        charBuffer.get(chars)
        return chars
    }
}
