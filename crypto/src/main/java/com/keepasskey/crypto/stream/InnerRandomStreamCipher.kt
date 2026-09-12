package com.keepasskey.crypto.stream

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.hash.HashUtil
import org.bouncycastle.crypto.CipherParameters
import org.bouncycastle.crypto.StreamCipher
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.util.Arrays

/**
 * KDBX 内部内存流加密器（Inner Random Stream Cipher）。
 * 用于解密和加密 XML 中的 Protected="True" 密码字段，防止敏感密码以明文形式出现在流中。
 *
 * ## InnerRandomStreamID 取值
 * 官方 KDBX 4.0 规范（keepass.info `kdbx.html` §Inner Encryption）只定义了下表 4 个 ID，
 * 其中 **KDBX4 实际只允许 2/3**：
 *
 * | ID | 算法 | 出现场景 | 本仓行为 |
 * |----|------|----------|----------|
 * | 0 | None（无内层加密） | KDBX 3.1 / 4.0 均可 | ✅ 直通（[NoopStreamCipher]） |
 * | 1 | ArcFourVariant | **仅见于 KDBX 3.1**（官方 `CrsAlgorithm.ArcFourVariant` 遗留值） | ❌ 本仓不实现 |
 * | 2 | Salsa20 | KDBX 3.1，及 KeePass 2.x 写出的部分 KDBX4 兼容库 | ✅ [SALSA20_NONCE] |
 * | 3 | ChaCha20 | KDBX 4.0 标准 | ✅ SHA-512 前 32/12 字节 |
 *
 * ArcFourVariant 是 KeePass 早期版本的遗留算法，自 KDBX4 起官方一律写出 2 或 3。
 * 本类遇到 ID=1 或未知 ID **一律拒绝**，绝不退化为「以某个默认算法继续解密」——
 * 后者会产出静默乱码，并可能在下一次保存时以错误密钥流覆写原始密文，造成不可逆的数据损坏。
 *
 * ## 失败类型（跨模块协调项）
 * 本类属 `crypto` 模块，受模块依赖严格单向约束（`database → crypto`），**不能**依赖
 * `database` 模块的 `KdbxCorruptFileException`，故 ID=1 / 未知 ID 抛
 * [CryptoException.CipherException]（消息自解释，含合法取值集与 ID 文本）。
 * 上层读取路径 `database/.../file/KdbxFile.kt` 已在构造本类处 try/catch 该异常并包装为
 * `KdbxCorruptFileException`，使用户看到类型化的「不支持的内层随机流算法」而非泛化失败。
 */
class InnerRandomStreamCipher(
    streamId: Int,
    streamKey: ByteArray
) {
    private val cipher: StreamCipher

    init {
        when (streamId) {
            KdbxConstants.InnerRandomStream.NONE -> {
                // P3-1 整改：InnerRandomStreamID = 0 (None) 为合法取值，表示无内层流加密。
                // 受保护字段仅以 Base64 形态存储、不做 XOR 密钥流变换，直通透传即可；
                // 原实现落入 else 分支直接抛异常，导致此类合法 KDBX4 库被拒绝打开。
                cipher = NoopStreamCipher
            }
            KdbxConstants.InnerRandomStream.CHACHA20 -> {
                // KDBX 4: SHA-512(streamKey)，前 32 字节为 Key，随后 12 字节为 Nonce
                val sha512 = HashUtil.sha512(streamKey)
                try {
                    val key = sha512.copyOfRange(0, 32)
                    val nonce = sha512.copyOfRange(32, 44)
                    val engine = ChaCha7539Engine()
                    engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
                    cipher = engine
                    Arrays.fill(key, 0.toByte())
                    Arrays.fill(nonce, 0.toByte())
                } finally {
                    Arrays.fill(sha512, 0.toByte())
                }
            }
            KdbxConstants.InnerRandomStream.SALSA20 -> {
                // KDBX 3: SHA-256(streamKey) 为 Key，IV 为 KeePass 官方固定 8 字节常量（见 SALSA20_NONCE）
                val key = HashUtil.sha256(streamKey)
                try {
                    val engine = Salsa20Engine()
                    // 传副本：协议常量不得被引擎侧的任何写操作污染（常量非机密，无需清零）
                    engine.init(true, ParametersWithIV(KeyParameter(key), SALSA20_NONCE.copyOf()))
                    cipher = engine
                } finally {
                    Arrays.fill(key, 0.toByte())
                }
            }
            else -> throw CryptoException.CipherException(
                // D7 整改：异常类型受模块单向依赖约束不能改为 database 的异常（见类 KDoc），
                // 但消息必须自解释——把合法取值集与「ArcFourVariant 仅见于 KDBX3.1」写清楚，
                // 上层包装为类型化异常后用户/日志可直接定位原因。
                "不支持的内层随机流类型: $streamId" +
                    "（KDBX4 只允许 0=None / 2=Salsa20 / 3=ChaCha20；" +
                    "1=ArcFourVariant 仅见于 KDBX3.1，本仓不实现）"
            )
        }
    }

    /**
     * 生成指定长度的伪随机密钥流字节
     */
    @Synchronized
    fun getRandomBytes(length: Int): ByteArray {
        val zeros = ByteArray(length)
        val output = ByteArray(length)
        cipher.processBytes(zeros, 0, length, output, 0)
        return output
    }

    /**
     * 将输入字节数组与伪随机流执行 XOR 变换
     */
    @Synchronized
    fun processBytes(input: ByteArray): ByteArray {
        val output = ByteArray(input.size)
        cipher.processBytes(input, 0, input.size, output, 0)
        return output
    }

    private companion object {

        /**
         * KDBX 内层随机流 Salsa20 的固定 8 字节 nonce（协议常量，**非机密**，故无需清零）。
         *
         * ## 三方一致的权威取值（F-09 整改，原实现为 `E8 30 09 4B 97 61 98 B0`，属可复现的静默乱码缺陷）
         * 1. **规范**：keepass.info `kdbx.html` §Inner Encryption —
         *    "the nonce is (0xE8, 0x30, 0x09, 0x4B, 0x97, 0x20, 0x5D, 0x2A)"
         * 2. **官方 C# 实现（格式裁决者）**：KeePass 2.61.1
         *    `KeePassLib/Cryptography/CryptoRandomStream.cs:119-120`
         *    `m_pbIV = new byte[8] { 0xE8, 0x30, 0x09, 0x4B, 0x97, 0x20, 0x5D, 0x2A }; // Unique constant`
         * 3. **KeePassXC**：`src/format/KeePass2.cpp:35`
         *    `INNER_STREAM_SALSA20_IV("\xe8\x30\x09\x4b\x97\x20\x5d\x2a")`
         *
         * 原实现误用 `0x61, 0x98, 0xB0` 替换了第 6/7/8 字节，使同一 streamKey 派生出完全不同的
         * 密钥流：受保护字段解密为乱码，且下一次保存会以乱码值重新加密（保存即不可逆覆写）。
         * 回归锁见 `InnerRandomStreamCipherKatTest` 中「错误 nonce 必须产出不同密钥流」用例。
         */
        val SALSA20_NONCE: ByteArray = byteArrayOf(
            0xE8.toByte(), 0x30.toByte(), 0x09.toByte(), 0x4B.toByte(),
            0x97.toByte(), 0x20.toByte(), 0x5D.toByte(), 0x2A.toByte()
        )
    }
}

/**
 * 直通（noop）流密码实现，对应 InnerRandomStreamID = 0 (None)：
 * 不产生任何密钥流，processBytes 等价于返回输入字节数组的副本。
 */
private object NoopStreamCipher : StreamCipher {

    override fun getAlgorithmName(): String = "None (pass-through)"

    override fun init(forEncryption: Boolean, params: CipherParameters?) {
        // 无状态直通实现，无需初始化参数
    }

    override fun returnByte(inByte: Byte): Byte = inByte

    override fun processBytes(
        inBytes: ByteArray,
        inOff: Int,
        len: Int,
        outBytes: ByteArray,
        outOff: Int
    ): Int {
        System.arraycopy(inBytes, inOff, outBytes, outOff, len)
        return len
    }

    override fun reset() {
        // 无状态，无需重置
    }
}
