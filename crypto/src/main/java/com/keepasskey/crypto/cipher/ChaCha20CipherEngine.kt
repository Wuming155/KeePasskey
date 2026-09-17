package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.security.Provider
import java.security.Security
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20 对称加密引擎（RFC 7539 / ChaCha7539）。
 *
 * **双路径分派**（对齐 [TwofishCipherEngine] 的原生优先范式，ISSUE-P3-153 / §145）：
 * - **原生路径**：RustCrypto `chacha20` 内核（[NativeChaCha20]）——BC 纯 Java 实现真机吞吐
 *   仅 2.6~2.7 MB/s，Rust 内核实测 ≈118 MB/s（≈44×，见 `docs/records/真机吞吐实测记录_2026-09-17.md`
 *   §2.1）；流包装为 ChaCha20 专用的无填充分块流（[NativeEncryptingOutputStream] /
 *   [NativeDecryptingInputStream]，64 KiB 分块 + 字节偏移推进）；
 * - **兜底路径**：BC JCE `ChaCha7539`（[bouncyCastleProvider] 持有实例，§143 解耦）——
 *   原生不可用（个别机型缺 ABI）时回退，语义与接线前逐字节一致。
 */
class ChaCha20CipherEngine : CipherEngine {

    init {
        ensureBouncyCastle()
    }

    override val cipherUuid: KdbxUuid = KdbxConstants.Cipher.CHACHA20
    override val name: String = "ChaCha20"
    override val ivLength: Int = KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH

    override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        validateNonceLength(iv)
        return if (NativeChaCha20.available) {
            try {
                NativeChaCha20.applyKeystreamChecked(key, iv, 0, data)
            } catch (e: CryptoException.CipherException) {
                throw e
            } catch (e: Exception) {
                throw CryptoException.CipherException("ChaCha20 加密失败", e)
            }
        } else {
            try {
                initCipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(data)
            } catch (e: Exception) {
                throw CryptoException.CipherException("ChaCha20 加密失败", e)
            }
        }
    }

    override fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        validateNonceLength(iv)
        return if (NativeChaCha20.available) {
            try {
                NativeChaCha20.applyKeystreamChecked(key, iv, 0, data)
            } catch (e: CryptoException.CipherException) {
                throw e
            } catch (e: Exception) {
                throw CryptoException.CipherException("ChaCha20 解密失败", e)
            }
        } else {
            try {
                initCipher(Cipher.DECRYPT_MODE, key, iv).doFinal(data)
            } catch (e: Exception) {
                throw CryptoException.CipherException("ChaCha20 解密失败", e)
            }
        }
    }

    override fun createEncryptingStream(
        outputStream: OutputStream,
        key: ByteArray,
        iv: ByteArray
    ): OutputStream {
        validateNonceLength(iv)
        return if (NativeChaCha20.available) {
            // 流**自持** key/nonce 副本并在 close 时擦除：原生密钥流按偏移惰性施加，
            // 若直接引用调用方数组，则在「建立流后立即擦除调用方密钥」的调用时序下
            // 会退化为全零密钥（契约与 §147 整改的 `KeyOwning*` 一致）。
            NativeEncryptingOutputStream(outputStream, key.copyOf(), iv.copyOf())
        } else {
            CipherOutputStream(outputStream, initCipher(Cipher.ENCRYPT_MODE, key, iv))
        }
    }

    override fun createDecryptingStream(
        inputStream: InputStream,
        key: ByteArray,
        iv: ByteArray
    ): InputStream {
        validateNonceLength(iv)
        return if (NativeChaCha20.available) {
            // 同上：流自持 key/nonce 副本，close 时擦除
            NativeDecryptingInputStream(inputStream, key.copyOf(), iv.copyOf())
        } else {
            CipherInputStream(inputStream, initCipher(Cipher.DECRYPT_MODE, key, iv))
        }
    }

    /**
     * P0-4 整改：RFC 7539 nonce 恒为 12 字节，长度不符立即失败。
     * 原实现把超长 IV 静默截断为前 12 字节，掩盖了 KdbxFile 侧恒生成 16 字节 IV 的上游缺陷，
     * 产出官方 KeePass（ChaCha20Cipher 构造器对 pbIV12.Length != 12 直接抛出）无法打开的文件；
     * 读取侧对称截断又令自读自写往返永远通过，互操作缺陷被结构性掩盖。
     */
    private fun validateNonceLength(iv: ByteArray) {
        if (iv.size != KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH) {
            throw IllegalArgumentException(
                "ChaCha20 nonce 必须为 " + KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH +
                    " 字节，实际为: " + iv.size
            )
        }
    }

    private fun initCipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("ChaCha7539", bouncyCastleProvider())
        cipher.init(mode, SecretKeySpec(key, "ChaCha7539"), IvParameterSpec(iv))
        return cipher
    }

    companion object {
        fun ensureBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        /**
         * 完整版 [BouncyCastleProvider] 的**持有实例**（ISSUE-P2-92）。
         *
         * **禁止**以 `Security.getProvider("BC")` 取用：Android 平台自带**剥离版** BC provider，
         * 注册名同为 `"BC"` 但不含 `ChaCha7539` / `Twofish` 等轻量算法——真机上
         * `ensureBouncyCastle()` 的「无则注册」判定恒为「已注册」，完整版永不生效，
         * 按注册表取用必然 `NoSuchAlgorithmException`（2026-09-17 Redmi 4X 实测）。
         * 本持有实例与注册表**完全解耦**，宿主 / 真机行为一致。
         */
        private val fullBouncyCastle: Provider by lazy { BouncyCastleProvider() }

        /**
         * 取得完整版 BouncyCastle [Provider] **实例**，供本包引擎以
         * `Cipher.getInstance(transformation, provider)` 形式取用。
         *
         * 相对按名取用（`Cipher.getInstance(transformation, "BC")`）的两个好处：
         * 1. 避开 Android Lint `DeprecatedProvider`（按名取用在 Android P+ 上会抛
         *    `NoSuchAlgorithmException`，官方建议改用 Provider 实例）；
         * 2. **与注册表解耦**（ISSUE-P2-92）：无论注册表中的 `"BC"` 是完整版还是平台剥离版，
         *    本函数恒返回完整版持有实例；BC 类缺失时在**这里**就 fail-fast，而不是在
         *    `Cipher.getInstance` 内部报出难以定位的 `NoSuchProviderException`。
         */
        fun bouncyCastleProvider(): Provider = fullBouncyCastle
    }

    // ================= 原生路径流包装（ChaCha20 无填充，语义比 CBC 简单） =================

    /**
     * 原生加密输出流：每次 `write` 调用即按当前字节偏移施加密钥流并写出
     * （密钥流是偏移的纯函数，调用粒度不影响正确性）；明文中转副本用毕即清零。
     */
    private class NativeEncryptingOutputStream(
        private val sink: OutputStream,
        private val key: ByteArray,
        private val nonce: ByteArray
    ) : OutputStream() {

        private var position = 0L
        private var closed = false

        override fun write(value: Int) {
            ensureOpen()
            val one = byteArrayOf(value.toByte())
            try {
                sink.write(NativeChaCha20.applyKeystreamChecked(key, nonce, position, one))
            } finally {
                Arrays.fill(one, 0)
            }
            position += 1
        }

        override fun write(data: ByteArray, off: Int, len: Int) {
            ensureOpen()
            val chunk = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
            try {
                sink.write(NativeChaCha20.applyKeystreamChecked(key, nonce, position, chunk))
            } finally {
                if (chunk !== data) Arrays.fill(chunk, 0)
            }
            position += len
        }

        override fun flush() = sink.flush()

        override fun close() {
            if (closed) return
            closed = true
            // key / nonce 为本流**自持**的副本（由引擎 `copyOf()` 移交所有权），必须擦除；
            // 调用方原数组不在本流所有权内，故不受影响（契约同 `KeyOwning*`）
            Arrays.fill(key, 0)
            Arrays.fill(nonce, 0)
            sink.close()
        }

        private fun ensureOpen() {
            if (closed) throw java.io.IOException("流已关闭")
        }
    }

    /**
     * 原生解密输入流：从底层流读入**实例级复用**的 64 KiB 缓冲，按当前字节偏移施加
     * 密钥流后交付（ISSUE-P3-177 的缓冲复用纪律；缓冲内为明文，close 时清零）。
     * ChaCha20 无填充、无分组对齐，无 fail-closed 收尾语义。
     */
    private class NativeDecryptingInputStream(
        private val source: InputStream,
        private val key: ByteArray,
        private val nonce: ByteArray
    ) : InputStream() {

        private val buffer = ByteArray(CHUNK_SIZE)
        private var position = 0L
        private var eofDone = false
        private var closed = false

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            if (count <= 0) return -1
            val value = single[0].toInt() and 0xFF
            Arrays.fill(single, 0)
            return value
        }

        override fun read(data: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            require(off >= 0 && len <= data.size - off) { "非法的读取区间 off=$off len=$len" }
            if (closed) throw java.io.IOException("流已关闭")
            if (eofDone) return -1

            // 尽量读满请求量（单次底层读可能短读），读多少解密多少
            var filled = 0
            while (filled < len) {
                val count = source.read(buffer, filled, minOf(len, buffer.size) - filled)
                if (count <= 0) break
                filled += count
            }
            if (filled == 0) {
                eofDone = true
                return -1
            }
            try {
                val out = NativeChaCha20.applyKeystreamChecked(key, nonce, position, buffer.copyOf(filled))
                try {
                    System.arraycopy(out, 0, data, off, filled)
                } finally {
                    Arrays.fill(out, 0)
                }
            } finally {
                Arrays.fill(buffer, 0, filled, 0)
            }
            position += filled
            return filled
        }

        override fun close() {
            if (closed) return
            closed = true
            Arrays.fill(buffer, 0)
            // key / nonce 为本流**自持**的副本（由引擎 `copyOf()` 移交所有权），必须擦除
            Arrays.fill(key, 0)
            Arrays.fill(nonce, 0)
            source.close()
        }

        private companion object {
            const val CHUNK_SIZE = 64 * 1024
        }
    }
}
