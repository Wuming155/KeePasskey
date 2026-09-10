package com.keepasskey.crypto.kdf

import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.hash.HashUtil
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-KDF 的 **JCE 参考实现**（ISSUE-P3-34 自 `AesKdfEngine` 原样抽出，行为零变更）。
 *
 * 抽出的两个理由：
 * 1. **单一实现**：既作原生不可用时的兜底路径，又作原生探活的**对照基准**
 *    （[NativeAesKdf.available] 用 1 轮真实派生与它逐字节比对，比「非空即通过」的浅探活更强）；
 * 2. **杜绝递归**：探活本身若经 `AesKdfEngine` 走一遍「先问原生可用性」的分派，会立刻自递归。
 *
 * 算法（对齐 KeePass 官方 `AesKdf.Transform`，注意方向）：seed 作 **AES 密钥**、
 * compositeKey 作**明文**，迭代 `rounds` 次后取 SHA-256。
 */
internal object AesKdfJce {

    /** 复合密钥与种子长度（KDBX AES-KDF 恒为 32 字节）。 */
    private const val KEY_LEN = 32

    // ECB 是 **KeePass AES-KDF 的规范定义**（对固定 32B 缓冲逐轮 ECB 加密并链式迭代），
    // 不是误用：换成 CBC/CTR 会直接破坏与官方 KeePass / KeePassXC 库的互操作。
    // 故显式抑制该 lint 规则，避免后人以「修复告警」为名改坏 KDF 兼容性。
    @Suppress("GetInstance")
    fun transform(compositeKey: ByteArray, seed: ByteArray, rounds: Long): ByteArray {
        val buffer = compositeKey.clone()
        try {
            val keySpec = SecretKeySpec(seed, "AES")
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)

            for (r in 0 until rounds) {
                cipher.update(buffer, 0, KEY_LEN, buffer, 0)
            }

            return HashUtil.sha256(buffer)
        } catch (e: Exception) {
            throw CryptoException.KdfException("AES-KDF 密钥派生失败", e)
        } finally {
            Arrays.fill(buffer, 0.toByte())
        }
    }
}
