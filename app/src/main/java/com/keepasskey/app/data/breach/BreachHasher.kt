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

    /** HIBP 协议要求大写十六进制摘要 */
    private val UpperCaseHex = HexFormat { upperCase = true }

    /**
     * 计算密码明文字节的 SHA-1 十六进制（大写）。
     *
     * 借用语义：调用方持有 [data] 的独占所有权，本函数不缓存、不留存，用毕由调用方清零。
     */
    fun sha1HexUpper(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-1").digest(data).toHexString(UpperCaseHex)
    }

    /**
     * 将完整哈希拆分为「上送前缀 + 本地比对后缀」。
     * @throws IllegalArgumentException 哈希长度非法（fail-closed，绝不截断上送）
     */
    fun splitPrefixSuffix(hashHexUpper: String): Pair<String, String> {
        require(hashHexUpper.length == HASH_HEX_LENGTH) { "非法 SHA-1 十六进制长度：${hashHexUpper.length}" }
        return hashHexUpper.substring(0, PREFIX_LENGTH) to hashHexUpper.substring(PREFIX_LENGTH)
    }
}
