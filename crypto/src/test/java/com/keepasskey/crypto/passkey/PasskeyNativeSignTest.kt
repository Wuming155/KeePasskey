package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * ISSUE-P3-153 / §146：Passkey 签名内核（原生）与 BC 生产路径的**对拍**用例（宿主侧）。
 *
 * 对拍成立的根据：两侧都是**确定性**签名——ES256 = RFC 6979（SHA-256）+ 规范 ASN.1 DER，
 * Ed25519 = RFC 8032——同一 (key, msg) 必须产出**逐字节相同**的签名，且签名可被对方验签。
 * 原生不可用（宿主库未注入）时经 `Assume` 显式跳过（设备侧硬断言补位）。
 */
class PasskeyNativeSignTest {

    private val random = SecureRandom()

    @Test
    fun `RFC官方向量经原生内核复现`() {
        assumeTrue("宿主原生库未注入", NativePasskeySign.available)

        // ES256 = RFC 6979 A.2.5（P-256 / SHA-256 / "sample"），规范 DER
        val esSig = NativePasskeySign.es256SignChecked(
            "c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721".hexToByteArray(),
            "sample".toByteArray(Charsets.US_ASCII)
        )
        val esExpected =
            ("3046022100efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716" +
                "022100f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8").hexToByteArray()
        assertArrayEquals("ES256 必须复现 RFC 6979 A.2.5", esExpected, esSig)

        // Ed25519 = RFC 8032 §7.1 TEST 1（空报文）
        val edSig = NativePasskeySign.ed25519SignChecked(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToByteArray(),
            ByteArray(0)
        )
        val edExpected =
            ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b").hexToByteArray()
        assertArrayEquals("Ed25519 必须复现 RFC 8032 §7.1 TEST 1", edExpected, edSig)
    }

    @Test
    fun `ES256原生签名与BC逐字节一致且可被BC验签`() {
        assumeTrue("宿主原生库未注入", NativePasskeySign.available)
        val ec = SECNamedCurves.getByName("secp256r1")
        val domain = ECDomainParameters(ec.curve, ec.g, ec.n, ec.h)

        repeat(20) { round ->
            val scalar = ByteArray(32).also { random.nextBytes(it) }
            val priv = ECPrivateKeyParameters(BigInteger(1, scalar), domain)
            val data = ByteArray(64).also { random.nextBytes(it) }

            // 生产路径（现路由到原生）
            val native = PasskeyCryptoEngine.signAssertion(PasskeyData.ALGORITHM_ES256, scalar, data)

            // BC 参照实现（与既有生产兜底同口径）
            val digest = SHA256Digest().apply { update(data, 0, data.size) }
            val hash = ByteArray(32).also { digest.doFinal(it, 0) }
            val bcSigner = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
            bcSigner.init(true, priv)
            val rs = bcSigner.generateSignature(hash)
            val bcSig = encodeDer(rs[0], rs[1])

            assertArrayEquals("ES256 原生与 BC 必须逐字节一致（round=$round）", bcSig, native)

            // BC 验签（语义有效性）
            val pub = ec.g.multiply(priv.d).normalize()
            bcSigner.init(false, org.bouncycastle.crypto.params.ECPublicKeyParameters(pub, domain))
            assert(bcSigner.verifySignature(hash, rs[0], rs[1]))
        }
    }

    @Test
    fun `Ed25519原生签名与BC逐字节一致且可被BC验签`() {
        assumeTrue("宿主原生库未注入", NativePasskeySign.available)

        repeat(20) { round ->
            val seed = ByteArray(32).also { random.nextBytes(it) }
            val data = ByteArray(64).also { random.nextBytes(it) }

            val native = PasskeyCryptoEngine.signAssertion(PasskeyData.ALGORITHM_ED25519, seed, data)

            val priv = Ed25519PrivateKeyParameters(seed, 0)
            val bcSigner = Ed25519Signer().apply {
                init(true, priv)
                update(data, 0, data.size)
            }
            val bcSig = bcSigner.generateSignature()
            assertArrayEquals("Ed25519 原生与 BC 必须逐字节一致（round=$round）", bcSig, native)

            val verifier = Ed25519Signer().apply {
                init(false, priv.generatePublicKey())
                update(data, 0, data.size)
            }
            assert(verifier.verifySignature(native))
        }
    }

    /** 与生产 [PasskeyAssertionSigner] 相同的 R,S → DER 组装（对拍参照）。 */
    private fun encodeDer(r: BigInteger, s: BigInteger): ByteArray {
        val rBytes = r.toByteArray()
        val sBytes = s.toByteArray()
        val inner = 2 + rBytes.size + 2 + sBytes.size
        val der = ByteArray(2 + inner)
        der[0] = 0x30
        der[1] = inner.toByte()
        der[2] = 0x02
        der[3] = rBytes.size.toByte()
        System.arraycopy(rBytes, 0, der, 4, rBytes.size)
        val off = 4 + rBytes.size
        der[off] = 0x02
        der[off + 1] = sBytes.size.toByte()
        System.arraycopy(sBytes, 0, der, off + 2, sBytes.size)
        return der
    }
}
