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
                // KDBX 3: SHA-256(streamKey)，IV 为 KeePass 官方固定 8 字节常量
                val key = HashUtil.sha256(streamKey)
                try {
                    val salsaIv = byteArrayOf(
                        0xE8.toByte(), 0x30.toByte(), 0x09.toByte(), 0x4B.toByte(),
                        0x97.toByte(), 0x61.toByte(), 0x98.toByte(), 0xB0.toByte()
                    )
                    val engine = Salsa20Engine()
                    engine.init(true, ParametersWithIV(KeyParameter(key), salsaIv))
                    cipher = engine
                } finally {
                    Arrays.fill(key, 0.toByte())
                }
            }
            else -> throw CryptoException.CipherException("不支持的内层随机流类型: $streamId")
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
