package com.keepasskey.crypto.cipher

/**
 * PKCS#7 / PKCS#5 块填充（ISSUE-P3-35）。
 *
 * **单一实现原则**：整型（`CipherEngine.encrypt/decrypt`）与流式（[CbcEncryptingOutputStream] /
 * [CbcDecryptingInputStream]）两条路径共用本对象，杜绝出现两份可能漂移的填充代码。
 * 原生侧（`twofish_cbc.rs`）**只做分组变换、不含填充**，即为此分工。
 *
 * 语义对齐 `javax.crypto` 的 `PKCS5Padding` 在 16 字节分组上的行为：
 * - 填充长度恒为 `1..=16`，**整块对齐时追加一整个填充块**（长度 16）；
 * - 去填充时长度必须在 `1..=16`，且末尾 `n` 字节必须全部等于 `n`，否则视为非法。
 */
internal object Pkcs7 {

    /** 分组长度（Twofish / AES 均为 16 字节）。 */
    const val BLOCK_SIZE = 16

    /**
     * 追加 PKCS#7 填充。
     *
     * @return 新数组（长度恒为 [BLOCK_SIZE] 的整数倍且严格大于 `data.size`）；
     *   调用方持有清零责任（原文属敏感数据）。
     */
    fun pad(data: ByteArray): ByteArray {
        val padLen = BLOCK_SIZE - (data.size % BLOCK_SIZE)
        val padded = data.copyOf(data.size + padLen)
        java.util.Arrays.fill(padded, data.size, padded.size, padLen.toByte())
        return padded
    }

    /**
     * 判定**任意长度**（分组整数倍）数据的 PKCS#7 填充是否合法，并返回去填充后的长度。
     *
     * 之所以提供「全量数据」形态而非仅单分组形态：整型解密拿到的是整段明文，
     * 只有**最后一个分组**承载填充——若按「必须是单分组」校验，任何超过 16 字节的明文
     * 都会被判为填充非法（本模块自测已锁定该负例）。
     *
     * @return 去填充后的字节数（`0..size - 1`）；填充非法返回 `-1`
     */
    fun unpaddedLength(data: ByteArray): Int {
        if (data.isEmpty() || data.size % BLOCK_SIZE != 0) return -1
        val padLen = data[data.size - 1].toInt() and 0xFF
        if (padLen < 1 || padLen > BLOCK_SIZE) return -1
        for (i in (data.size - padLen) until data.size) {
            if ((data[i].toInt() and 0xFF) != padLen) return -1
        }
        return data.size - padLen
    }

    /**
     * 去除 PKCS#7 填充（单分组形态）。
     *
     * @param block 长度必须为 [BLOCK_SIZE]
     * @return 去填充后的前缀副本；填充非法返回 `null`（fail-closed），
     *   由调用方决定是抛 `IOException`（流式，对齐 `CipherInputStream`）
     *   还是抛 `CipherException`（整型，对齐 `Cipher.doFinal` 的 `BadPaddingException`）。
     */
    fun unpad(block: ByteArray): ByteArray? {
        if (block.size != BLOCK_SIZE) return null
        val length = unpaddedLength(block)
        return if (length < 0) null else block.copyOf(length)
    }
}
