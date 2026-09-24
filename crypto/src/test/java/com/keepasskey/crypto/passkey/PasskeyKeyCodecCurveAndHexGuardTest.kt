package com.keepasskey.crypto.passkey

import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * ISSUE-P3-311 项 2 / 项 3 守卫用例：
 * - 项 2：v1 legacy 私钥 **DER 分支钉死曲线**——DER 自带域参数，非 P-256 域 fail-closed 拒绝；
 * - 项 3：hex 文本私钥**逐字节解析**，不再物化为不可擦 String（语义与旧 `BigInteger(hex, 16)`
 *   对合法输入等价）。
 */
class PasskeyKeyCodecCurveAndHexGuardTest {

    /** 用 BC 构造指定命名曲线的 PKCS#8 风格 EC 私钥 DER（v1 legacy 分支的真实输入形态） */
    private fun derPrivateKey(curveOid: ASN1ObjectIdentifier, d: BigInteger): ByteArray {
        val curve = org.bouncycastle.asn1.sec.SECNamedCurves.getByOID(curveOid)
        val params = ECNamedDomainParameters(curveOid, curve)
        return org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(
            ECPrivateKeyParameters(d, params)
        ).encoded
    }

    @Test
    fun `P-256 私钥 DER 照常解析（既有语义不变）`() {
        val d = BigInteger("2f5c1a9b3d7e0f11223344556677889900112233445566778899aabbccddeeff", 16)
        val parsed = PasskeyKeyCodec.parseEcPrivateKey(derPrivateKey(X9ObjectIdentifiers.prime256v1, d))
        assertEquals(d, parsed.d)
    }

    @Test
    fun `非 P-256 域的私钥 DER 被钉死曲线拒绝（fail-closed）`() {
        // secp256k1：合法曲线上的合法标量，但域不是 ES256/P-256
        val d = BigInteger("2f5c1a9b3d7e0f11223344556677889900112233445566778899aabbccddeeff", 16)
        val der = derPrivateKey(ASN1ObjectIdentifier("1.3.132.0.10"), d) // secp256k1
        val thrown = assertThrows(CryptoException.InvalidKeyException::class.java) {
            PasskeyKeyCodec.parseEcPrivateKey(der)
        }
        assertTrue(
            "诊断必须指明域参数问题: ${thrown.message}",
            thrown.message!!.contains("P-256")
        )
    }

    @Test
    fun `hex 文本私钥（64 字节 ASCII）解析值与 BigInteger 文本口径等价`() {
        val hexText = "2f5c1a9b3d7e0f11223344556677889900112233445566778899aabbccddeeff"
        val bytes = hexText.toByteArray(Charsets.UTF_8)
        assertEquals(64, bytes.size)
        val parsed = PasskeyKeyCodec.parseEcPrivateKey(bytes)
        // 语义等价：与旧实现 BigInteger(hexText, 16) 同值
        assertEquals(BigInteger(hexText, 16), parsed.d)
    }

    @Test
    fun `非 hex 形态的 64 字节输入走原始标量回退（回退语义不变）`() {
        // 64 字节任意内容（非 hex 文本）：回退按原始标量解读，仍须经标量有效域校验
        val bytes = ByteArray(64) { ((it * 7 + 1) and 0xFF).toByte() }
        val thrown = assertThrows(CryptoException.InvalidKeyException::class.java) {
            PasskeyKeyCodec.parseEcPrivateKey(bytes)
        }
        assertTrue(thrown.message!!.contains("越界"))
    }

    @Test
    fun `hex 解析对非法字符不越权接受（走回退而非错误赋值）`() {
        // 文本含非 hex 字符 → 逐字节解析抛 NumberFormatException → 回退原始标量；
        // 与旧实现差异仅在于不再物化 String，接受/拒绝的最终结论一致
        val text = "zz".repeat(32).toByteArray(Charsets.UTF_8)
        assertThrows(CryptoException.InvalidKeyException::class.java) {
            PasskeyKeyCodec.parseEcPrivateKey(text)
        }
    }
}
