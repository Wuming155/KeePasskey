package com.keepasskey.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 进程内敏感字节驻留加密器（P3 整改，对齐 KeePassDX `ProtectedString.protectInMemory` 思路）。
 *
 * 作用：受保护字段（密码 / TOTP 种子 / Passkey 私钥等）在 JVM 堆中以**密文形态驻留**，
 * 内存 dump / Frida 字符串扫描不再能直接读出明文；仅在显式读取的瞬间解密。
 *
 * 设计要点（确定性加密，保持既有相等性语义）：
 * - IV = SHA-256(进程密钥 ‖ 明文) 前 16 字节，与密文一并驻留于实例内：
 *   1. **确定性**：相同明文恒得相同 IV 与密文 → [ProtectedString.equals]/hashCode 直接比较
 *      密文即可，同步变更检测与合并引擎的比较路径无需解密物化明文；
 *   2. **逐值密钥流**：不同明文 → 不同 IV → 不同密钥流，已知一对（明文, 密文）无法推导
 *      其他值的密钥流（对比全局固定 IV 的密钥流复用）；
 *   3. **无离线爆破预言机**：IV 依赖进程密钥，攻击者仅凭 dump 出的 (IV, 密文) 无法构造
 *      低熵值（如 PIN）的离线校验通道——验证猜测必须先取得进程密钥。
 * - 密钥为进程生命周期的 SecureRandom 32 字节，绝不落盘、不进日志。
 *
 * 如实声明的边界：本机制是纵深防御层，对抗的是堆扫描 / 崩溃转储 / 自动化凭据爬取中的
 * 明文暴露；拥有进程任意代码执行能力的攻击者可在读取瞬间 hook 拿到明文——
 * 取得进程密钥后亦同（KeePassDX 同级取舍）。
 */
internal object InMemoryCipher {

    /** 进程生命周期内随机对称密钥（AES-256），仅在内存中存活 */
    private val keyBytes: ByteArray = ByteArray(KEY_LENGTH_BYTES).also {
        SecureRandom().nextBytes(it)
    }

    private const val TRANSFORMATION = "AES/CTR/NoPadding"
    private const val KEY_LENGTH_BYTES = 32
    private const val IV_LENGTH_BYTES = 16

    /** 密封产物：IV 与密文一并交付 ProtectedString 驻留 */
    class Sealed internal constructor(internal val iv: ByteArray, internal val data: ByteArray)

    /** 明文 → (IV, 密文)。确定性映射：相同明文恒得相同产物。 */
    fun seal(plain: ByteArray): Sealed {
        if (plain.isEmpty()) return Sealed(ByteArray(0), plain.clone())
        // P2-3 整改：计算 IV 派生时分块 update 摘要，避免 keyBytes + plain 拼接数组驻留内存堆
        val md = MessageDigest.getInstance("SHA-256")
        md.update(keyBytes)
        md.update(plain)
        val hash = md.digest()
        val iv = hash.copyOfRange(0, IV_LENGTH_BYTES)
        java.util.Arrays.fill(hash, 0.toByte())

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            Sealed(iv, cipher.doFinal(plain))
        } catch (e: Exception) {
            // JCE 提供 AES/CTR 为 Android 平台硬保证；此处失败属平台级异常，响亮失败优于静默降级明文驻留
            throw IllegalStateException("内存驻留加密初始化失败", e)
        }
    }

    /** (IV, 密文) → 明文新副本。调用方用毕负责清零。 */
    fun unseal(iv: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.isEmpty()) return sealed.clone()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            throw IllegalStateException("内存驻留解密失败（数据可能已被外部破坏）", e)
        }
    }
}
