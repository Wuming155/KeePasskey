package com.keepasskey.app.data.breach

import java.security.MessageDigest

/**
 * k-匿名泄露比对用的本地哈希器（纯 JVM，无网络依赖）。
 *
 * HIBP Pwned Passwords 的 k-anonymity 协议要求：本地计算密码的 SHA-1，仅取前 5 位十六进制
 * 作为查询前缀上送，服务端返回该前缀下的全部后缀，由本地完成比对——密码明文与完整哈希
 * 均不出端。
 *
 * 已知答案向量（公开常量，非凭据）：SHA-1("password") = `5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8`
 * → 前缀 `5BAA6`，后缀 `1E4C9B93F3F0682250B6CF8331B7EE68FD8`。
 */
object BreachHasher {

    /** 上送的哈希前缀长度（十六进制字符数）——HIBP k-anonymity 协议规定值 */
    const val PREFIX_LENGTH = 5

    /** SHA-1 十六进制摘要长度 */
    private const val HASH_HEX_LENGTH = 40

    /** 十六进制大写字符表（HIBP 协议要求大写；逐半字节查表，不经 `HexFormat`） */
    private val UpperHexDigits = "0123456789ABCDEF".toCharArray()

    /**
     * 计算密码明文字节的 SHA-1，并按 k-anonymity 协议**就地**拆出「上送前缀 + 本地比对后缀」。
     *
     * ISSUE-P3-102（审计 L5）：此前实现先把**完整 40 位十六进制摘要**物化为 `String`
     * （`MessageDigest.digest(data).toHexString(...)`）再做 `substring` 拆分，且 `digest()` 产出的
     * 摘要**字节数组**从未清零——无盐 SHA-1 的全量摘要即口令等价物。
     * 现改为**字节态计算 + 就地拆分**：摘要只存在于可清零的字节数组与可覆写的 `StringBuilder`
     * 中间量中，**完整摘要不物化为 `String`**；摘要字节与十六进制中间量均在 `finally` 中擦除。
     *
     * **如实声明的边界**：协议本身要求把 5 位前缀上送、把 35 位后缀留存用于与响应比对，
     * 故这两个**分片**必须以 `String` 存在（二者拼接即完整摘要）。本函数消除的是
     * 「完整摘要的单一 `String` 物化」与「摘要字节数组不留存」，不声称消除分片本身。
     *
     * 借用语义：调用方持有 [data] 的独占所有权，本函数不缓存、不留存，用毕由调用方清零。
     */
    fun splitPrefixSuffixOfSha1(data: ByteArray): Pair<String, String> {
        val digest = MessageDigest.getInstance("SHA-1").digest(data)
        try {
            val hex = StringBuilder(HASH_HEX_LENGTH)
            try {
                for (byte in digest) {
                    val value = byte.toInt() and 0xFF
                    hex.append(UpperHexDigits[value ushr 4])
                    hex.append(UpperHexDigits[value and 0x0F])
                }
                // 长度由摘要宽度结构性保证（20 字节 → 恒 40 位），无需运行期 fail-closed 校验
                return hex.substring(0, PREFIX_LENGTH) to hex.substring(PREFIX_LENGTH)
            } finally {
                wipe(hex)
            }
        } finally {
            digest.fill(0)
        }
    }

    /** 逐字符覆写后清空——避免十六进制中间量以字符形式残留在 `StringBuilder` 的内部数组中 */
    private fun wipe(builder: StringBuilder) {
        for (index in 0 until builder.length) builder.setCharAt(index, '\u0000')
        builder.setLength(0)
    }
}
