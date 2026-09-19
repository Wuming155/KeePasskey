package com.keepasskey.crypto.cipher

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
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
 *   [NativeDecryptingInputStream]，64 KiB 分块 + 字节偏移推进）。**解密流自 `ISSUE-P3-198`
 *   起走 direct `ByteBuffer` 直扣**（评估定案见 `records/JNI零拷贝评估_2026-09-19.md`）；
 *   加密流与整块 `byte[]` 路径维持拷贝桥（边界理由见各处 KDoc，§198 批次文档）；
 * - **兜底路径**：BC JCE `ChaCha7539`（[bouncyCastleProvider] 持有实例，§143 解耦）——
 *   原生不可用（个别机型缺 ABI）时回退，语义与接线前逐字节一致。
 *
 * **兜底分支的常态回归**（§147 追问的后续整改）：兜底路径原只在「原生库不可用」时才执行，
 * 即最需要它正确的时刻最缺回归；现经 `internal constructor(forceBcFallback = true)` 由
 * `CipherFallbackParityTest` 在常态构建中强制走通（生产恒用公开无参构造，行为不变）。
 */
class ChaCha20CipherEngine internal constructor(
    /** **仅供测试**：强制走 BC 兜底分支（生产恒为 `false`）。 */
    private val forceBcFallback: Boolean
) : CipherEngine {

    /** 公开无参构造：生产路径（[CipherFactory]）与设备侧用例一律使用本构造。 */
    constructor() : this(forceBcFallback = false)

    /** 本次调用是否走原生：生产由 [NativeChaCha20.available] 决定，测试可经 [forceBcFallback] 强制兜底。 */
    private val useNative: Boolean get() = !forceBcFallback && NativeChaCha20.available

    init {
        ensureBouncyCastle()
    }

    override val cipherUuid: KdbxUuid = KdbxConstants.Cipher.CHACHA20
    override val name: String = "ChaCha20"
    override val ivLength: Int = KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH

    override fun encrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        validateNonceLength(iv)
        return if (useNative) {
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
        return if (useNative) {
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
        return if (useNative) {
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
        return if (useNative) {
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
     *
     * **刻意不切 direct（`ISSUE-P3-198` 边界登记）**：本流的写粒度由调用方决定且可任意大
     * （旧形态把任意尺寸数组**单次**交给 JNI，边界拷贝总量与粒度无关）；若改用固定 direct
     * 缓冲，大写入必须分段循环，反而引入「每 64 KiB 一次 JNI 固定开销 + 堆外归零」的额外
     * 成本——拷贝次数不变（`OutputStream` 字节 API 两侧各留一次），仅省每次 write 的输出
     * 数组分配，收益不入。解密侧（[NativeDecryptingInputStream]）读粒度受本流 64 KiB 缓冲
     * 封顶，直扣才有净收益。
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
     * 原生解密输入流（`ISSUE-P3-198` 直扣形态）：从底层流读入堆内中转缓冲（`InputStream`
     * API 只收 `byte[]`，该次拷贝不可消），经**流实例自持的 direct 缓冲**就地施加密钥流后
     * 交付——每块仅「堆→堆外 put」与「堆外→调用方 get」两次拷贝、零分配（旧形态为
     * `copyOf` + JNI 入/出拷贝 + 交付 `arraycopy` 共 4 次拷贝，且每块分配 2 个 64 KiB 数组）。
     *
     * 敏感数据处理：明文区间**交付即归零**（堆外内存不受 GC 管辖，归零必须显式且确定性），
     * close 时整段兜底归零；`key` / `nonce` 为本流自持副本，close 时擦除。`ISSUE-P3-177`
     * 的缓冲复用纪律由「单 direct 缓冲整流复用」承接。ChaCha20 无填充、无分组对齐，
     * 无 fail-closed 收尾语义。
     */
    private class NativeDecryptingInputStream(
        private val source: InputStream,
        private val key: ByteArray,
        private val nonce: ByteArray
    ) : InputStream() {

        /** 密文中转缓冲（堆内）：`InputStream.read` 只收 `byte[]`，此一次拷贝受 API 限制。 */
        private val buffer = ByteArray(CHUNK_SIZE)

        /** 明文就地区（堆外，direct）：单次分配、整流复用、用毕就地归零（擦除责任在本流）。 */
        private val plain = ByteBuffer.allocateDirect(CHUNK_SIZE)

        /** `[0, plainLength)` 为待交付明文区间，`plainPos` 为已交付前缀（交付即归零）。 */
        private var plainLength = 0
        private var plainPos = 0
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
            while (true) {
                if (plainPos < plainLength) {
                    val count = minOf(len, plainLength - plainPos)
                    plain.position(plainPos)
                    plain.get(data, off, count)
                    // 交付即归零：明文区间用毕确定性擦除
                    plain.wipeRange(plainPos, plainPos + count)
                    plainPos += count
                    return count
                }
                if (eofDone || !refill(len)) return -1
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            wipePlain()
            Arrays.fill(buffer, 0)
            // 堆外明文区整段兜底归零（可能残留未交付明文）
            plain.wipeRange(0, plain.capacity())
            // key / nonce 为本流**自持**的副本（由引擎 `copyOf()` 移交所有权），必须擦除
            Arrays.fill(key, 0)
            Arrays.fill(nonce, 0)
            source.close()
        }

        /**
         * 读入一段密文并对 `[0, filled)` 的等容量视图就地变换；返回 `false` 表示流结束。
         * [target] 为本次调用方请求的上限（与旧形态一致：读满请求量即交付，不做过量预读）。
         */
        private fun refill(target: Int): Boolean {
            if (eofDone) return false
            wipePlain()

            // 尽量读满请求量（单次底层读可能短读），读多少解密多少——与旧形态读满语义一致
            val cap = minOf(target, CHUNK_SIZE)
            var filled = 0
            while (filled < cap) {
                val count = source.read(buffer, filled, cap - filled)
                if (count <= 0) break
                filled += count
            }
            if (filled == 0) {
                eofDone = true
                return false
            }

            // 堆→堆外一次拷贝后按 [0, filled) 视图就地变换（JNI 契约按 capacity 处理整区间）
            plain.clear()
            plain.put(buffer, 0, filled)
            plain.flip()
            NativeChaCha20.applyKeystreamDirectChecked(key, nonce, position, plain.slice())
            plainLength = filled
            plainPos = 0

            // 密文中转卫生清零（维持既有清零纪律；变换已发生在堆外，此处残留为密文）
            Arrays.fill(buffer, 0, filled, 0)
            position += filled
            return true
        }

        /** 归零未交付的明文残留并复位交付游标（refill 前置与 close 共用）。 */
        private fun wipePlain() {
            if (plainPos < plainLength) {
                plain.wipeRange(plainPos, plainLength)
            }
            plainLength = 0
            plainPos = 0
        }

        private companion object {
            const val CHUNK_SIZE = 64 * 1024
        }
    }
}
