package com.keepasskey.database.file

import com.keepasskey.database.io.LittleEndianUtil
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * KDBX HMAC 块摘要计算器（ISSUE-P3-37）。
 *
 * **本条的目的以「消除重复构造」为主，性能只是附带项，且收益不可测量**——如实声明：
 * 块尺寸恒为 1 MiB，每块 HMAC 自身就要做约 1 MiB 的 SHA-256 压缩，而本类省下的是
 * 「每块一次的 `Mac`/`MessageDigest` 获取与 4 个小数组分配」，相对前者属噪声量级。
 * 真正的价值在于：原先 `writeAll` / `readAll` / `loadNextBlock` / `flushBlock` / `close`
 * **五处各自手工拼装** `SHA-512(LE64(index) ‖ key)` + `HMAC(index ‖ LE32(size) ‖ data)`，
 * 字段顺序与长度写错不会有任何编译期或测试外的提示；收敛到本类后只有一份实现。
 *
 * 秘密治理：`blockKey` 为 SHA-512 派生中间量，复用会延长其驻留期，故提供 [wipe]，
 * 由持有它的流在 [java.io.OutputStream.close] / [java.io.InputStream.close] 时调用。
 * （`Mac` 对象内部的 ipad/opad 无公开 API 可主动清零，只能随 GC 释放——这一点与
 * 原先「每块新建 `Mac`」并无差别，不构成本次改动引入的退化。）
 *
 * ⚠️ **非线程安全**：`Mac` 与 `MessageDigest` 均非线程安全，本类实例必须与单个流实例一一对应，
 * 严禁跨线程共享或放入共享缓存。
 */
internal class BlockHmac(private val hmacKey64: ByteArray) {

    private val mac: Mac = Mac.getInstance(HMAC_ALGORITHM)
    private val blockKeyDigest: MessageDigest = MessageDigest.getInstance(BLOCK_KEY_ALGORITHM)

    /** LE64(块索引) 缓冲 */
    private val indexBytes = ByteArray(INDEX_SIZE)

    /** LE32(块长度) 缓冲 */
    private val sizeBytes = ByteArray(SIZE_SIZE)

    /** 块密钥 = SHA-512(LE64(index) ‖ hmacKey64) */
    private val blockKey = ByteArray(BLOCK_KEY_SIZE)

    init {
        // 官方规范：HMAC 密钥恒为 64 字节（KdbxFile 的 hmacKey64）
        require(hmacKey64.size == BLOCK_KEY_SIZE) {
            "KDBX HMAC 密钥长度必须为 $BLOCK_KEY_SIZE 字节，实际为 ${hmacKey64.size}"
        }
    }

    /**
     * 计算指定块的 HMAC-SHA256。
     *
     * 摘要输入 = `LE64(blockIndex) ‖ LE32(blockSize) ‖ data[offset, offset+length)`，
     * 密钥 = `SHA-512(LE64(blockIndex) ‖ hmacKey64)`（对齐 KeePass 2.x `GetBlockKey` 与 KeePassXC）。
     *
     * @param length 为 `0` 时表示空数据块（终止块场景）
     */
    fun compute(
        blockIndex: Long,
        blockSize: Int,
        data: ByteArray,
        offset: Int = 0,
        length: Int = 0
    ): ByteArray {
        LittleEndianUtil.writeLongTo8Bytes(indexBytes, 0, blockIndex)
        LittleEndianUtil.writeIntTo4Bytes(sizeBytes, 0, blockSize)

        blockKeyDigest.reset()
        blockKeyDigest.update(indexBytes)
        blockKeyDigest.update(hmacKey64)
        blockKeyDigest.digest(blockKey, 0, BLOCK_KEY_SIZE)

        mac.init(SecretKeySpec(blockKey, HMAC_ALGORITHM))
        mac.update(indexBytes)
        mac.update(sizeBytes)
        if (length > 0) {
            mac.update(data, offset, length)
        }
        return mac.doFinal()
    }

    /** 清零持有的派生中间量与辅助缓冲（幂等）。 */
    fun wipe() {
        blockKey.fill(0)
        indexBytes.fill(0)
        sizeBytes.fill(0)
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val BLOCK_KEY_ALGORITHM = "SHA-512"
        const val INDEX_SIZE = 8
        const val SIZE_SIZE = 4
        const val BLOCK_KEY_SIZE = 64
    }
}
