package com.keepasskey.crypto

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-09 整改真值 KAT：KDBX 内层随机流（[InnerRandomStreamCipher]）密钥流的字节级锁定。
 *
 * ## 缺陷背景
 * 本仓原 Salsa20 分支误用 nonce `E8 30 09 4B 97 61 98 B0`，正确值为
 * `E8 30 09 4B 97 20 5D 2A`（三方一致，出处见下）。错误 nonce 使同一 `streamKey`
 * 派生出**完全不同**的密钥流：受保护字段解密为静默乱码，且下一次保存会以乱码值重新
 * 加密（`KdbxFile` 新建/保存恒用 ChaCha20），造成**保存即不可逆覆写**。
 * 本缺陷之所以长期逃逸，是因为既有用例（`CryptoTest.testInnerRandomStreamSymmetry`、
 * 各 KDBX 往返用例）只做**自读自写**往返——自洽即可通过，与官方互操作性无关。
 * 故此处锚定**独立于本仓实现的外部真值**，不依赖本仓任何往返路径。
 *
 * ## 向量来源（跨实现真值，非自洽往返）
 *
 * **主来源：pycryptodome 3.23.0**（Python 3.12.10，`from Crypto.Cipher import Salsa20, ChaCha20`）
 * ——与本仓 Kotlin/BouncyCastle 实现**完全独立**的第三方实现。
 *
 * 生成配方（可直接复现）：
 * ```python
 * import hashlib
 * from Crypto.Cipher import Salsa20, ChaCha20
 * # Salsa20（内层流 ID=2）：K32 = 00 01 .. 1F（32 字节）
 * K32 = bytes(range(32)); sk = hashlib.sha256(K32).digest()
 * nonce = bytes([0xE8,0x30,0x09,0x4B,0x97,0x20,0x5D,0x2A])
 * print(Salsa20.new(key=sk, nonce=nonce).encrypt(bytes(32)).hex())
 * # ChaCha20（内层流 ID=3）：K64 = 00 01 .. 3F（64 字节，与官方 64 字节 InnerRandomStreamKey 同长）
 * K64 = bytes(range(64)); H = hashlib.sha512(K64).digest()
 * print(ChaCha20.new(key=H[0:32], nonce=H[32:44]).encrypt(bytes(32)).hex())
 * ```
 * 该实现的 Salsa20 另经 Bernstein / ECRYPT 官方向量
 * （`key=80 00..00, nonce=0, counter=0`）校验：首 64 字节 =
 * `e3be8fdd8beca2e3ea8ef9475b29a6e7003951e1097a5c38d23b7a5fad9f6844`
 * `b22c97559e2723c7cbbd3fe4fc8d9a0744652a83e72a9c461876af4d7ef1a117`，证明工具本身可信。
 *
 * **交叉复核：Bouncy Castle `bcprov-jdk18on:1.85.2`（本仓生产引擎本体）**——
 * 直接以本仓依赖版本的 `Salsa20Engine` / `ChaCha7539Engine` 重算，与 pycryptodome
 * **逐字节一致**（`java -cp bcprov-jdk18on-1.85.2.jar KatProbe.java`，Java 21）。
 * 故下述期望值既独立于本仓代码，也确实是本仓引擎的正确输出；**未发现任何不一致**。
 *
 * **协议常量出处（Salsa20 nonce）**：
 * - 规范 keepass.info `kdbx.html` §Inner Encryption：
 *   "the nonce is (0xE8, 0x30, 0x09, 0x4B, 0x97, 0x20, 0x5D, 0x2A)"
 * - KeePass 2.61.1 `KeePassLib/Cryptography/CryptoRandomStream.cs:119-120`
 * - KeePassXC `src/format/KeePass2.cpp:35` `INNER_STREAM_SALSA20_IV("\xe8\x30\x09\x4b\x97\x20\x5d\x2a")`
 *
 * ## 错误 nonce 的密钥流（F-09 回归锁用）
 * 同一 K32 下，错误 nonce `E8 30 09 4B 97 61 98 B0` 的密钥流前 32 字节 =
 * `739a24411659762d97ba9107082efe718ee8f793295f3666b48d72cf62642fd5`，
 * 与正确值**无任何字节相同**。故下述 KAT 对 F-09 是强判据：回退 nonce 必然导致用例失败。
 */
class InnerRandomStreamCipherKatTest {

    /** Salsa20 测试输入（非敏感常量，绝不用真实 streamKey）：00 01 02 .. 1F（32 字节） */
    private val streamKey32: ByteArray = ByteArray(32) { it.toByte() }

    /**
     * ChaCha20 测试输入（非敏感常量）：00 01 02 .. 3F（64 字节）。
     * 取 64 字节以与官方 `InnerRandomStreamKey` 的实际长度一致（官方恒写 64 字节）。
     */
    private val streamKey64: ByteArray = ByteArray(64) { it.toByte() }

    // ===================== Salsa20（InnerRandomStreamID = 2）=====================

    /**
     * Salsa20 密钥流前 32 字节真值。
     * key = SHA-256(streamKey32)，nonce = E8 30 09 4B 97 20 5D 2A。
     */
    @Test
    fun `Salsa20 密钥流前 32 字节匹配外部真值 KAT`() {
        val cipher = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.SALSA20, streamKey32)

        assertArrayEquals(
            "Salsa20 内层流密钥流与规范/官方 C#/KeePassXC 不一致（F-09 回退？）",
            hex("f9beb52962838a2c3c8227ceed909273277197ffafe66de4599f4ad62da69c1d"),
            cipher.getRandomBytes(32)
        )
    }

    /**
     * 跨调用连续性：密钥流是单一连续流，分两次取 32 字节必须等于一次取 64 字节。
     * 防止「每次调用重置引擎」类实现缺陷（会静默产生重复密钥流，是经典的流密码复用漏洞）。
     */
    @Test
    fun `Salsa20 跨调用密钥流连续且等于 64 字节真值`() {
        val cipher = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.SALSA20, streamKey32)

        val first = cipher.getRandomBytes(32)
        val second = cipher.getRandomBytes(32)

        assertArrayEquals(
            hex("f9beb52962838a2c3c8227ceed909273277197ffafe66de4599f4ad62da69c1d"),
            first
        )
        assertArrayEquals(
            hex("1d257176ef84bf30437d0f072d1db132c70af1bf836ccc081918a236194ca6ae"),
            second
        )
        assertArrayEquals(
            hex(
                "f9beb52962838a2c3c8227ceed909273277197ffafe66de4599f4ad62da69c1d" +
                    "1d257176ef84bf30437d0f072d1db132c70af1bf836ccc081918a236194ca6ae"
            ),
            first + second
        )
    }

    /**
     * F-09 回归锁（负向）：错误 nonce `E8 30 09 4B 97 61 98 B0` 的密钥流必须**不等于**正确值。
     * 若有人把 nonce 改回历史错误值，本用例与上面两条 KAT 会同时失败。
     */
    @Test
    fun `Salsa20 错误 nonce 的密钥流与正确值不同（F-09 回归锁）`() {
        val wrongNonceKeystream = hex(
            "739a24411659762d97ba9107082efe718ee8f793295f3666b48d72cf62642fd5"
        )
        val actual = InnerRandomStreamCipher(
            KdbxConstants.InnerRandomStream.SALSA20, streamKey32
        ).getRandomBytes(32)

        assertFalse(
            "检测到历史错误 nonce（E8 30 09 4B 97 61 98 B0）的密钥流——F-09 已回退！",
            actual.contentEquals(wrongNonceKeystream)
        )
        assertArrayEquals(
            hex("f9beb52962838a2c3c8227ceed909273277197ffafe66de4599f4ad62da69c1d"),
            actual
        )
    }

    /**
     * Salsa20 XOR 变换语义：`processBytes(明文)` == 明文 XOR 密钥流，
     * 且同一 streamKey 的两个实例互为加解密（内层流是对合变换）。
     */
    @Test
    fun `Salsa20 processBytes 等价于与密钥流逐字节 XOR`() {
        val plaintext = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val expectedKeystream = hex("f9beb52962838a2c3c8227ceed909273277197ffafe66de4599f4ad62da69c1d")
        val expectedCiphertext = ByteArray(32) { (plaintext[it].toInt() xor expectedKeystream[it].toInt()).toByte() }

        val enc = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.SALSA20, streamKey32)
        val ciphertext = enc.processBytes(plaintext)
        assertArrayEquals(expectedCiphertext, ciphertext)

        // 对合：另一实例以同参数解密
        val dec = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.SALSA20, streamKey32)
        assertArrayEquals(plaintext, dec.processBytes(ciphertext))
    }

    // ===================== ChaCha20（InnerRandomStreamID = 3）=====================

    /**
     * ChaCha20 密钥流前 32 字节真值（输入 K64 = `00 01 .. 3F`，64 字节）。
     * key = SHA-512(K64)[0:32]，nonce = SHA-512(K64)[32:44]（KDBX4 官方语义）。
     *
     * 该分支经核实已与官方一致（`CryptoRandomStream.cs:101-115` 同取 SHA-512 前 32/12 字节），
     * 本用例仅补真值锁定以防未来无声回归。
     */
    @Test
    fun `ChaCha20 密钥流前 32 字节匹配外部真值 KAT`() {
        val cipher = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, streamKey64)

        assertArrayEquals(
            "ChaCha20 内层流密钥流与官方语义不一致（key=SHA-512[0:32], nonce=SHA-512[32:44]）",
            hex("8ce8bc610ac05ff2e3dd88b49a1404c2844f148037027476b83d58f5609adf65"),
            cipher.getRandomBytes(32)
        )
    }

    /**
     * ChaCha20 跨调用连续性：分两次取 32 字节等于一次取 64 字节真值。
     * 防「每次调用重置引擎」导致的密钥流复用（流密码致命缺陷）。
     */
    @Test
    fun `ChaCha20 跨调用密钥流连续且等于 64 字节真值`() {
        val cipher = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, streamKey64)

        val first = cipher.getRandomBytes(32)
        val second = cipher.getRandomBytes(32)

        assertArrayEquals(
            hex("8ce8bc610ac05ff2e3dd88b49a1404c2844f148037027476b83d58f5609adf65"),
            first
        )
        assertArrayEquals(
            hex("f8fa87d8b5f5287f6cfdbdb69adea2f942e4acf5a9ad9e860623477a73a24c4e"),
            second
        )
        assertArrayEquals(
            hex(
                "8ce8bc610ac05ff2e3dd88b49a1404c2844f148037027476b83d58f5609adf65" +
                    "f8fa87d8b5f5287f6cfdbdb69adea2f942e4acf5a9ad9e860623477a73a24c4e"
            ),
            first + second
        )
    }

    /** Salsa20 与 ChaCha20 是两个不同算法：同一 streamKey 的密钥流不得相同（分支串线的兜底判据）。 */
    @Test
    fun `Salsa20 与 ChaCha20 密钥流互不相同`() {
        val salsa = InnerRandomStreamCipher(
            KdbxConstants.InnerRandomStream.SALSA20, streamKey32
        ).getRandomBytes(32)
        val chacha = InnerRandomStreamCipher(
            KdbxConstants.InnerRandomStream.CHACHA20, streamKey64
        ).getRandomBytes(32)

        assertFalse("Salsa20 与 ChaCha20 分支疑似串线", salsa.contentEquals(chacha))
    }

    // ===================== ID = 0 / 1 / 未知 =====================

    /** ID=0（None）为合法取值：直通透传，不产生任何密钥流变换（P3-1 回归锁）。 */
    @Test
    fun `ID 为 0 时直通透传`() {
        val data = hex("0011223344556677889900aabbccddee")
        val cipher = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.NONE, streamKey32)

        assertArrayEquals(data, cipher.processBytes(data))
        assertArrayEquals(ByteArray(16), cipher.getRandomBytes(16))
    }

    /**
     * D7：ID=1（ArcFourVariant，仅见于 KDBX3.1）与未知 ID 必须**立即拒绝**。
     *
     * 受模块单向依赖约束（database → crypto），crypto 模块不能抛 database 的
     * `KdbxCorruptFileException`，故此处断言 [CryptoException.CipherException]；
     * 上层 `KdbxFile.load` 已在构造处捕获本异常并包装为 `KdbxCorruptFileException`
     * （`database/.../file/KdbxFile.kt` 中 `InnerRandomStreamCipher` 的 try/catch 块），
     * 使用户看到类型化的「不支持的内层随机流算法」而不是泛化失败。
     * 关键在于**绝不能**静默降级为某个默认算法——那会产出乱码并在保存时不可逆覆写。
     */
    @Test
    fun `ID 为 1 或未知时拒绝且消息含合法取值集`() {
        for (unsupported in listOf(KdbxConstants.InnerRandomStream.ARCFOUR, -1, 4, 99, Int.MAX_VALUE)) {
            val ex = assertThrows(CryptoException.CipherException::class.java) {
                InnerRandomStreamCipher(unsupported, streamKey32)
            }
            assertTrue(
                "异常消息应点名非法 ID: ${ex.message}",
                ex.message?.contains(unsupported.toString()) == true
            )
            // 消息须自解释，便于上层包装后用户/日志定位
            assertTrue(
                "异常消息应说明合法取值集: ${ex.message}",
                ex.message?.contains("Salsa20") == true && ex.message?.contains("ArcFourVariant") == true
            )
        }
    }

    // ===================== 辅助 =====================

    /** 十六进制字面量 → 字节数组（测试向量以 hex 书写，便于与文档/规范逐字节比对）。 */
    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex 字面量长度必须为偶数: ${s.length}" }
        return ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
        }
    }
}
