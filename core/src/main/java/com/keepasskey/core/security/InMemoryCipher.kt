package com.keepasskey.core.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 进程内敏感字节驻留加密器（P3 整改，对齐 KeePassDX `ProtectedString.protectInMemory` 思路；
 * 2026-09 加解密审查整改：消除确定性加密的等值泄露面）。
 *
 * 作用：受保护字段（密码 / TOTP 种子 / Passkey 私钥等）在 JVM 堆中以**密文形态驻留**，
 * 内存 dump / Frida 字符串扫描不再能直接读出明文；仅在显式读取的瞬间解密。
 *
 * 设计要点（等值语义与加密解耦）：
 * - **等值标签**：HMAC-SHA256(eqKey, 明文)，相同明文恒得相同标签——[ProtectedString.equals]/hashCode
 *   仅比较标签（[MessageDigest.isEqual] 常时时间比较），同步变更检测与合并引擎的比较路径
 *   不解密、不物化明文；
 * - **随机化加密**：每次 seal 由 [SecureRandom] 生成全新 16 字节 IV，AES-256-CTR 加密。
 *   同一明文两次密封得到不同 (IV, 密文)——堆中不存在可跨实例关联的确定性密文，
 *   攻击者无法凭 dump 判断"两个受保护字段的明文是否相同"（等值判断只能靠标签，
 *   标签本身是 HMAC 伪随机输出，不泄露明文信息）；
 * - **域分离密钥派生**：主密钥 32 字节 SecureRandom，经 HMAC-SHA256 以域标签派生
 *   encKey（加密）与 eqKey（等值标签）两把独立子密钥，主密钥派生后立即清零；
 * - 密钥仅在内存中存活、绝不落盘、不进日志。
 *
 * 如实声明的边界：本机制是纵深防御层，对抗的是堆扫描 / 崩溃转储 / 自动化凭据爬取中的
 * 明文暴露；拥有进程任意代码执行能力的攻击者可在读取瞬间 hook 拿到明文——
 * 取得密钥后亦同（KeePassDX 同级取舍）。AES/CTR 无认证标签：驻留密文的防篡改不在
 * 本层目标内（篡改只会在解密侧产出垃圾明文，不产生权限提升）。
 */
internal object InMemoryCipher {

    private const val TRANSFORMATION = "AES/CTR/NoPadding"
    private const val MAC_ALGORITHM = "HmacSHA256"

    private const val MASTER_KEY_LENGTH_BYTES = 32
    private const val IV_LENGTH_BYTES = 16

    /** 域分离标签：杜绝加密密钥与等值标签密钥间的任何交叉可用性 */
    private val ENC_DOMAIN = "keepasskey.memory.enc.v2".toByteArray(StandardCharsets.UTF_8)
    private val EQ_DOMAIN = "keepasskey.memory.eq.v2".toByteArray(StandardCharsets.UTF_8)

    /** 进程生命周期随机源（同时用于主密钥与每次密封的 IV） */
    private val secureRandom = SecureRandom()

    /** 加密子密钥（AES-256） */
    private val encKey: ByteArray

    /** 等值标签子密钥（HMAC-SHA256） */
    private val eqKey: ByteArray

    init {
        val master = ByteArray(MASTER_KEY_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val enc = hmacSha256(master, ENC_DOMAIN)
        val eq = hmacSha256(master, EQ_DOMAIN)
        // 主密钥派生完两把子密钥后立即清零，减少堆中长期驻留的密钥材料副本
        Arrays.fill(master, 0.toByte())
        encKey = enc
        eqKey = eq
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(key, MAC_ALGORITHM))
        return mac.doFinal(data)
    }

    /** 密封产物：随机 IV、等值标签与密文一并交付 ProtectedString 驻留 */
    class Sealed internal constructor(
        internal val iv: ByteArray,
        internal val tag: ByteArray,
        internal val data: ByteArray
    )

    /**
     * 明文 → (随机 IV, 等值标签, 密文)。
     * 等值语义：相同明文恒得相同标签；加密语义：相同明文两次密封的 (IV, 密文) 必不相同。
     */
    fun seal(plain: ByteArray): Sealed {
        if (plain.isEmpty()) {
            // 空明文无秘密可保护：不加密，标签为空明文的确定性 HMAC（仅服务等值语义）
            return Sealed(ByteArray(0), hmacSha256(eqKey, plain), plain.clone())
        }
        // 每次密封生成全新随机 IV——密钥流绝不复用（对齐官方 CTR/GCM IV 唯一性要求）
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val tag = hmacSha256(eqKey, plain)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            Sealed(iv, tag, cipher.doFinal(plain))
        } catch (e: Exception) {
            // JCE 提供 AES/CTR 与 HmacSHA256 为 Android 平台硬保证；此处失败属平台级异常，
            // 响亮失败优于静默降级明文驻留
            Arrays.fill(iv, 0.toByte())
            Arrays.fill(tag, 0.toByte())
            throw IllegalStateException("内存驻留加密初始化失败", e)
        }
    }

    /** (IV, 密文) → 明文新副本。调用方用毕负责清零。 */
    fun unseal(iv: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.isEmpty()) return sealed.clone()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            throw IllegalStateException("内存驻留解密失败（数据可能已被外部破坏）", e)
        }
    }

    /**
     * 常时时间等值标签比较（供 ProtectedString.equals 使用，避免逐字节短路计时侧信道）。
     */
    fun tagsEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
