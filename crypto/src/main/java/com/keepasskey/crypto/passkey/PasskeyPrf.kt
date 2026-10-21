package com.keepasskey.crypto.passkey

import com.keepasskey.core.security.ProtectedString
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64

/**
 * WebAuthn **PRF 扩展**（`prf`）的凭据秘密与取值计算（对齐 KeePassDX 实现口径）。
 *
 * ## 语义
 *
 * WebAuthn Level 3 §10.1 的 `prf` 扩展要求认证器持有一枚**每凭据独立的秘密**，并把
 * 客户端给定的 `eval` 输入映射为确定性输出：
 *
 * ```
 * prfOutput = HMAC-SHA-256(secret, SHA-256("WebAuthn PRF" || 0x00 || evalInput))
 * ```
 *
 * 其中 `SHA-256("WebAuthn PRF" || 0x00 || input)` 是规范定义的**客户端侧处理**
 * （域分隔，避免与其它 HMAC 用途互相干扰）；认证器只做后半段 HMAC。KeePassDX 的
 * `PasskeyHelper.derivePrfSalt` / `computePrfValue` 即同一口径——2026-10-07（`ISSUE-P3-522`）
 * 逐行对照 `参考项目/KeePassDX/app/src/main/java/com/kunzisoft/keepass/credentialprovider/passkey/util/PasskeyHelper.kt:277-296`
 * 核实：`SALT_PRF = "WebAuthn PRF"`、`derivePrfSalt = SHA-256(UTF8(SALT_PRF) || [0x00] || rawSalt)`、
 * `computePrfValue = HMAC-SHA-256(secret, derivePrfSalt(salt))`，与本对象逐字节一致，
 * 保证两端对同一凭据给出相同 PRF 输出。
 *
 * ## 存储与敏感纪律
 *
 * 秘密以 **Base64 文本**形态存于 `KPEX_PASSKEY_PRF`（受保护字段，KeePassDX 同键同保护口径），
 * 内存中的长期持有者是 [ProtectedString]（密文驻留）；计算时只经
 * `useUtf8` 字节通道解码，中间秘密副本在 `finally` 中显式清零。
 */
object PasskeyPrf {

    /** PRF 凭据秘密长度（字节） */
    const val SECRET_BYTES: Int = 32

    /**
     * Base64 秘密文本中可剔除的 ASCII 空白码位（[stripWhitespace] 用，**等值比较**——
     * 与 `app/passkey` 的 `ASCII_SPACE` 不同：那里 `<` 表控制字符、`<=` 表两侧可修剪空白，
     * 三种语义分工不可互相「统一」。与 core `PasskeyKeyText` 的同组常量同值，但该组为
     * private、跨模块不可见，故按 §168 口径在本模块就地命名（ISSUE-P3-196）。
     */
    private const val ASCII_LF = 0x0A
    private const val ASCII_CR = 0x0D
    private const val ASCII_SPACE = 0x20
    private const val ASCII_TAB = 0x09

    /** 域分隔前缀（WebAuthn Level 3 §10.1 规定的客户端侧处理） */
    private val DOMAIN_SEPARATION_PREFIX = "WebAuthn PRF".toByteArray(Charsets.UTF_8)

    private val secureRandom = SecureRandom()

    /** 生成一枚全新的 PRF 凭据秘密（32 字节随机 → Base64 文本），以受保护形态驻留 */
    fun newSecretProtected(): ProtectedString = newSecretProtectedObserved(null)

    /**
     * 同 [newSecretProtected]，额外把**中间工作数组**（随机原始字节、Base64 编码副本）
     * 交给 [observeBuffer] 观察。仅测试可见：生产调用方没有持有这些数组的理由，
     * 观察回调只用于保留引用，不得产生副作用。
     *
     * 存在理由：这些缓冲的生命周期只有几行，若没有观察入口，就只能断言「最终交付的
     * ProtectedString 正确」，无法区分「确实擦除」与「未擦除但已不可达」——后者正是
     * 等待 GC 的形态，契约不允许（敏感缓冲所有权契约 R3）。
     */
    internal fun newSecretProtectedObserved(
        observeBuffer: ((ByteArray) -> Unit)?
    ): ProtectedString {
        val secret = ByteArray(SECRET_BYTES)
        secureRandom.nextBytes(secret)
        observeBuffer?.invoke(secret)
        try {
            return secretToProtected(secret, observeBuffer)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * 计算单个 `eval` 输入的 PRF 输出（32 字节）。
     *
     * @param prfSecret 该凭据的 PRF 秘密（Base64 文本，受保护驻留）
     * @param evalInput 请求给出的 `eval.first` / `eval.second` 原始字节
     */
    fun computeValue(prfSecret: ProtectedString, evalInput: ByteArray): ByteArray =
        computeValueObserved(prfSecret, evalInput, null)

    /**
     * 同 [computeValue]，额外把**中间工作数组**（受保护文本的 UTF-8 副本、去空白工作区、
     * Base64 解码结果、域分隔盐）交给 [observeBuffer] 观察。仅测试可见。
     *
     * 交付的 HMAC 输出**不**进入观察面：它是本函数的返回值，所有权已转移给调用方
     * （敏感缓冲所有权契约 R2 的「转移」），在本函数内清零即为违例。
     */
    internal fun computeValueObserved(
        prfSecret: ProtectedString,
        evalInput: ByteArray,
        observeBuffer: ((ByteArray) -> Unit)?
    ): ByteArray {
        val secret = decodeSecret(prfSecret, observeBuffer)
        try {
            val salt = clientSideProcess(evalInput)
            observeBuffer?.invoke(salt)
            try {
                return hmacSha256(secret, salt)
            } finally {
                salt.fill(0)
            }
        } finally {
            secret.fill(0)
        }
    }

    /**
     * 客户端侧处理：`SHA-256("WebAuthn PRF" || 0x00 || input)`（WebAuthn Level 3 §10.1）。
     */
    fun clientSideProcess(evalInput: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(DOMAIN_SEPARATION_PREFIX, 0, DOMAIN_SEPARATION_PREFIX.size)
        digest.update(0x00)
        digest.update(evalInput, 0, evalInput.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    /** 秘密 Base64 文本（受保护驻留）→ 原始字节；调用方负责清零。文本非法一律 fail-closed */
    private fun decodeSecret(
        prfSecret: ProtectedString,
        observeBuffer: ((ByteArray) -> Unit)? = null
    ): ByteArray {
        val textBytes = prfSecret.readUtf8()
        observeBuffer?.invoke(textBytes)
        var ascii: ByteArray? = null
        try {
            ascii = stripWhitespace(textBytes, observeBuffer)
            val decoded = try {
                Base64.getDecoder().decode(ascii)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("PRF 秘密不是合法 Base64 文本", e)
            }
            observeBuffer?.invoke(decoded)
            if (decoded.size != SECRET_BYTES) {
                decoded.fill(0)
                throw IllegalArgumentException("PRF 秘密长度必须为 $SECRET_BYTES 字节，实际 ${decoded.size}")
            }
            return decoded
        } finally {
            ascii?.fill(0)
            textBytes.fill(0)
        }
    }

    private fun secretToProtected(
        secret: ByteArray,
        observeBuffer: ((ByteArray) -> Unit)? = null
    ): ProtectedString {
        val encoded = Base64.getEncoder().encode(secret)
        observeBuffer?.invoke(encoded)
        var chars: CharArray? = null
        try {
            chars = CharArray(encoded.size) { encoded[it].toInt().toChar() }
            return ProtectedString(chars, isProtected = true)
        } finally {
            encoded.fill(0)
            chars?.fill('0')
        }
    }

    /**
     * 复制并剔除空白。**所有权**：返回数组归调用方（[decodeSecret] / [computeValue] 的
     * `finally` 负责清零）；未返回的分支（缩短复制后的原数组、复制抛错时的半成品）在
     * 本函数 `finally` 就地清零——只让「已交出的那份」逃出清理域。
     */
    private fun stripWhitespace(
        bytes: ByteArray,
        observeBuffer: ((ByteArray) -> Unit)? = null
    ): ByteArray {
        val out = ByteArray(bytes.size)
        observeBuffer?.invoke(out)
        var transferred = false
        try {
            var n = 0
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v == ASCII_LF || v == ASCII_CR || v == ASCII_SPACE || v == ASCII_TAB) continue
                out[n++] = b
            }
            if (n == out.size) { transferred = true; return out }
            val shrunk = out.copyOf(n)
            observeBuffer?.invoke(shrunk)
            return shrunk
        } finally {
            if (!transferred) out.fill(0)
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    /** 仅测试用：原始秘密字节 → 受保护 Base64 文本 */
    internal fun secretFromBytesForTest(secret: ByteArray): ProtectedString {
        require(secret.size == SECRET_BYTES) { "PRF 秘密长度必须为 $SECRET_BYTES 字节" }
        val copy = secret.copyOf()
        try {
            return secretToProtected(copy)
        } finally {
            Arrays.fill(copy, 0.toByte())
        }
    }

    /** 仅测试用：把受保护秘密还原为原始字节（调用方负责清零） */
    internal fun secretBytesForTest(prfSecret: ProtectedString): ByteArray = decodeSecret(prfSecret)
}
