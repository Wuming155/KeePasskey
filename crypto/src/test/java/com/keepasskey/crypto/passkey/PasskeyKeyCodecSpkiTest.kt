package com.keepasskey.crypto.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.exception.CryptoException
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/**
 * 注册响应 `response.publicKey` 的 **SPKI（DER）** 编码单测。
 *
 * 背景（`ISSUE-P2-265`）：W3C WebAuthn 把 `AuthenticatorAttestationResponse.getPublicKey()`
 * 定义为「**DER-encoded SubjectPublicKeyInfo**」。本仓此前在该字段下发 **COSE_Key CBOR**
 * （与 `attestationObject.authData.credentialPublicKey` 同一份），构成与参考实现 Monica
 * （`keyPair.public.encoded`，即 Java `PublicKey.getEncoded()` 的 SPKI）的实质分歧——
 * 在「同设备 / 同浏览器 / 同站点，Monica 成功而本仓失败」的对照中，这是唯一已知的
 * 响应材料差异点。
 *
 * 本文件锁定四件事：
 * 1. ES256 库内「未压缩点 `0x04 || X || Y`」导出 SPKI 后，**用独立解析器**（BC
 *    `SubjectPublicKeyInfo`）读回的公钥位串与原始点**逐字节一致**，且算法 OID 为
 *    `id-ecPublicKey`；
 * 2. Ed25519 库内 32 字节 raw 公钥同样包成合法 SPKI（OID `1.3.101.112`）；
 * 3. RS256 库内已是 SPKI → 导出**幂等原样返回**；
 * 4. **防混淆金标**：SPKI 输出**不得**等于 `coseKeyFor` 的 COSE_Key CBOR——
 *    两个字段是同一公钥的不同编码，不可互换。
 */
class PasskeyKeyCodecSpkiTest {

    @Test
    fun `ES256 未压缩点导出为可独立解析的 SPKI 且公钥位串一致`() {
        val data = PasskeyKeyGeneration.es256("example.com", "user")
        val rawPoint = Base64.getDecoder().decode(data.publicKeyBase64)
        assertEquals("库内 ES256 公钥应为未压缩点 0x04||X||Y", 65, rawPoint.size)

        val spki = PasskeyCryptoEngine.publicKeySubjectInfoFor(PasskeyData.ALGORITHM_ES256, rawPoint)

        val parsed = SubjectPublicKeyInfo.getInstance(spki)
        assertArrayEquals(
            "SPKI 内的公钥位串必须与库内未压缩点逐字节一致",
            rawPoint,
            parsed.publicKeyData.bytes
        )
        assertEquals(
            "ES256 的 SPKI 算法标识必须是 id-ecPublicKey",
            OID_EC_PUBLIC_KEY,
            parsed.algorithm.algorithm.id
        )
    }

    @Test
    fun `Ed25519 原始公钥导出为合法 SPKI`() {
        val data = PasskeyKeyGeneration.ed25519("example.com", "user")
        val rawPub = Base64.getDecoder().decode(data.publicKeyBase64)
        assertEquals("库内 Ed25519 公钥应为 32 字节 raw", 32, rawPub.size)

        val spki = PasskeyCryptoEngine.publicKeySubjectInfoFor(PasskeyData.ALGORITHM_ED25519, rawPub)

        val parsed = SubjectPublicKeyInfo.getInstance(spki)
        assertArrayEquals("SPKI 内公钥位串应为原始 32 字节种子公钥", rawPub, parsed.publicKeyData.bytes)
        assertEquals("Ed25519 的 SPKI 算法标识必须是 1.3.101.112", OID_ED25519, parsed.algorithm.algorithm.id)
    }

    @Test
    fun `RS256 库内已是 SPKI 故导出幂等`() {
        val data = PasskeyKeyGeneration.rs256("example.com", "user")
        val stored = Base64.getDecoder().decode(data.publicKeyBase64)

        val spki = PasskeyCryptoEngine.publicKeySubjectInfoFor(PasskeyData.ALGORITHM_RS256, stored)
        assertArrayEquals("RS256 库内已存 SPKI，导出必须原样返回", stored, spki)

        // 仍须是可被独立解析的合法 SPKI，且 OID 为 rsaEncryption
        val parsed = SubjectPublicKeyInfo.getInstance(spki)
        assertEquals("RS256 的 SPKI 算法标识必须是 rsaEncryption", OID_RSA, parsed.algorithm.algorithm.id)
    }

    @Test
    fun `SPKI 输出不得等于 COSE_Key 编码（防两字段再次混淆）`() {
        val data = PasskeyKeyGeneration.es256("example.com", "user")
        val rawPoint = Base64.getDecoder().decode(data.publicKeyBase64)

        val spki = PasskeyCryptoEngine.publicKeySubjectInfoFor(PasskeyData.ALGORITHM_ES256, rawPoint)
        val cose = PasskeyCryptoEngine.coseKeyFor(PasskeyData.ALGORITHM_ES256, rawPoint)

        assertFalse(
            "response.publicKey(SPKI) 与 attestationObject 内 COSE_Key 是不同编码，绝不可互换",
            spki.contentEquals(cose)
        )
    }

    @Test
    fun `不支持的算法标识 fail-closed 拒绝导出`() {
        assertThrows(CryptoException.InvalidKeyException::class.java) {
            PasskeyCryptoEngine.publicKeySubjectInfoFor(
                algorithmId = -9999,
                publicKeyBytes = ByteArray(65) { 1 }
            )
        }
    }

    private companion object {
        const val OID_EC_PUBLIC_KEY = "1.2.840.10045.2.1"
        const val OID_ED25519 = "1.3.101.112"
        const val OID_RSA = "1.2.840.113549.1.1.1"
    }
}
