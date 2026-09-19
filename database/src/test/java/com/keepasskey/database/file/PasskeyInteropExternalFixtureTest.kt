package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import java.util.Base64

/**
 * 通行密钥 `KPEX_PASSKEY_*` **读取半边**互操作用例（`ISSUE-P2-210`；证据纪律见 `AGENTS.md` 规则 8）。
 *
 * ## 被验命题
 *
 * `PasskeyInteropProbeTest` 证明「本仓产物能被官方实现读取」；本用例证明**反方向**：
 * 由 `pykeepass` + `cryptography`（**均非本仓实现**）写出的 `.kdbx`，能被本仓**生产读路径**
 * （`KdbxFile.load` → `PasskeyData.fromCustomFields`）正确还原并**真正可用于签名**。
 *
 * ## 为什么 fixture 必须不含 `Passkey.*` 扩展键
 *
 * 真实 KeePassXC / KeePassDX 产出的条目**只有** `KPEX_PASSKEY_*`，没有本仓扩展键
 * （`Passkey.Algorithm` / `PublicKey` / `SignCount` …）。算法只有靠 PKCS#8
 * `AlgorithmIdentifier` 的 **OID 纯字节嗅探**（`core/.../PasskeyKeyText.kt`）得出。
 * 若 fixture 里塞了 `Passkey.Algorithm`，本用例就会退化成「自证自家扩展键」，
 * 完全测不到外部条目兼容路径——故生成器内建了「不得出现扩展键」的自检。
 *
 * ## fixture 出处（可重复生成）
 *
 * - 生成器：`tools/passkey-interop/make_external_fixture.py`（`pykeepass` + `cryptography`）
 * - 校验与期望值：`database/src/test/resources/fixtures/passkey-interop/FIXTURE.md`
 * - 私钥为**公开测试向量**（ES256 标量 `0102…1f20`；Ed25519 = RFC 8032 §7.1 TEST 1 种子），
 *   故期望值可硬编码在本类常量中，且第三方可独立复算。
 *
 * ## 断言分层
 *
 * 1. **形状**：外部文件确实不含扩展键、KPEX 键的保护位按外部实现写入的原样读回；
 * 2. **语义**：`rpId` / 用户名 / 凭据 ID / UserHandle / PRF 逐字段相等；
 * 3. **能力**：OID 嗅探出的算法与外部实现一致，且该 PEM 经生产通道还原后
 *    能产出**被外部实现的公钥验签通过**的签名（把「能读」升级为「能用」）。
 */
class PasskeyInteropExternalFixtureTest {

    private fun fixtureStream(): InputStream =
        requireNotNull(javaClass.getResourceAsStream(FIXTURE_RESOURCE_PATH)) {
            "缺少外部 fixture `$FIXTURE_RESOURCE_PATH`（重新生成：python tools/passkey-interop/make_external_fixture.py）"
        }

    private fun loadEntries(): Map<String, KdbxEntry> {
        val password = DB_PASSWORD.toCharArray()
        val database = fixtureStream().use { KdbxFile.load(it, password, keyFileData = null) }
        // 外部实现把条目放在子组里（真实管理器的组织方式），故按整树扁平化查找
        return database.rootGroup.allEntries().associateBy { it.title }
    }

    /**
     * 形状守卫：外部 fixture **必须**只有 `KPEX_PASSKEY_*`，不得含本仓扩展键。
     * 这条断言是「读取半边」的证据成立前提——它把「嗅探路径确实被走到」从约定升级为机检。
     */
    @Test
    fun `外部fixture不得含本仓扩展键且KPEX保护位按外部实现原样读回`() {
        val entries = loadEntries()
        assertEquals("fixture 应含两条通行密钥条目", 2, entries.size)

        entries.values.forEach { entry ->
            val extensionKeys = entry.customFields.map { it.key }.filter { it.startsWith("Passkey.") }
            assertTrue(
                "外部 fixture 不得含本仓扩展键，但 `${entry.title}` 有：$extensionKeys",
                extensionKeys.isEmpty()
            )
        }

        // 外部实现（pykeepass）写入的保护位必须原样读回：
        // 受保护 = UserHandle / CredentialId / 私钥 PEM / PRF；普通 = RP / 用户名 / 标志位
        val protectedKeys = setOf(
            PasskeyData.KPEX_FIELD_USER_HANDLE,
            PasskeyData.KPEX_FIELD_CREDENTIAL_ID,
            PasskeyData.KPEX_FIELD_PRIVATE_KEY,
            PasskeyData.KPEX_FIELD_PRF
        )
        entries.values.forEach { entry ->
            entry.customFields.forEach { field ->
                assertEquals(
                    "`${entry.title}` 的 `${field.key}` 保护位必须与外部写入一致",
                    field.key in protectedKeys,
                    field.isProtected
                )
            }
        }
    }

    @Test
    fun `外部写入的ES256条目经OID嗅探还原并可被外部公钥验签`() {
        assertExternalEntry(
            title = ES256_ENTRY_TITLE,
            expectedAlgorithmId = PasskeyData.ALGORITHM_ES256,
            expectedUserName = ES256_USER_NAME,
            expectedCredentialId = ES256_CREDENTIAL_ID,
            expectedUserHandle = ES256_USER_HANDLE,
            expectedPublicKeyHex = ES256_PUBLIC_KEY_HEX,
            expectedPrfBase64 = PRF_SECRET_BASE64
        )
    }

    @Test
    fun `外部写入的Ed25519条目经OID嗅探还原并可被外部公钥验签`() {
        assertExternalEntry(
            title = ED25519_ENTRY_TITLE,
            expectedAlgorithmId = PasskeyData.ALGORITHM_ED25519,
            expectedUserName = ED25519_USER_NAME,
            expectedCredentialId = ED25519_CREDENTIAL_ID,
            expectedUserHandle = ED25519_USER_HANDLE,
            expectedPublicKeyHex = ED25519_PUBLIC_KEY_HEX,
            expectedPrfBase64 = null
        )
    }

    private fun assertExternalEntry(
        title: String,
        expectedAlgorithmId: Int,
        expectedUserName: String,
        expectedCredentialId: String,
        expectedUserHandle: String,
        expectedPublicKeyHex: String,
        expectedPrfBase64: String?
    ) {
        val entry = requireNotNull(loadEntries()[title]) { "fixture 必须含条目 `$title`" }
        val passkey = PasskeyData.fromCustomFields(entry.customFields)
        assertNotNull("外部条目必须能还原为 PasskeyData", passkey)
        passkey!!

        assertEquals(RP_ID, passkey.relyingPartyId)
        assertEquals(expectedUserName, passkey.userName)
        assertEquals(expectedCredentialId, passkey.credentialId)
        assertEquals(expectedUserHandle, passkey.userHandle)

        // 关键：算法**不是**从 `Passkey.Algorithm` 读到的（fixture 无该键），
        // 而是 PKCS#8 `AlgorithmIdentifier` 的 OID 纯字节嗅探结果。
        assertEquals(
            "OID 嗅探出的算法必须与外部实现一致",
            expectedAlgorithmId,
            passkey.algorithmId
        )
        assertTrue(
            "外部条目不得携带 `Passkey.PublicKey`（公钥由私钥推导，产品路径不回读）",
            passkey.publicKeyBase64.isEmpty()
        )

        // PRF：外部写入的受保护秘密必须原样读回（字节通道比对，不物化 String）
        if (expectedPrfBase64 == null) {
            assertNull("未携带 prf 的外部条目不得解析出 PRF 秘密", passkey.prfSecret)
        } else {
            val prf = requireNotNull(passkey.prfSecret) { "外部条目应带 KPEX_PASSKEY_PRF" }
            val actual = prf.useUtf8 { it.copyOf() }
            try {
                assertTrue(
                    "PRF 秘密字节流必须与外部写入一致",
                    actual.contentEquals(expectedPrfBase64.toByteArray(Charsets.US_ASCII))
                )
            } finally {
                Arrays.fill(actual, 0.toByte())
            }
        }

        // 「能读」升级为「能用」：生产 PEM 通道还原 → 生产签名 → **外部公钥**验签
        val signingKey = passkey.usePrivateKeyBytes { raw ->
            requireNotNull(PasskeyCryptoEngine.decodePemPrivateKeyText(raw)) {
                "外部写入的 PKCS#8 PEM 必须能经生产 PEM 通道还原签名材料"
            }
        }
        try {
            assertEquals(
                "PEM 通道还原出的算法必须与 OID 嗅探结果一致",
                expectedAlgorithmId,
                signingKey.algorithmId
            )
            assertEquals("签名材料为定长 32 字节", SIGNING_MATERIAL_BYTES, signingKey.keyBytes.size)

            val dataToSign = PasskeyCryptoEngine.buildAuthenticatorData(RP_ID, PasskeyCryptoEngine.FLAG_UP, 1) +
                ByteArray(32) { 0x11 }
            val signature = PasskeyCryptoEngine.signAssertion(signingKey.algorithmId, signingKey.keyBytes, dataToSign)

            val verifier = Signature.getInstance(
                if (expectedAlgorithmId == PasskeyData.ALGORITHM_ES256) JCA_SHA256_ECDSA else JCA_ED25519
            )
            verifier.initVerify(jcePublicKey(expectedAlgorithmId, expectedPublicKeyHex))
            verifier.update(dataToSign)
            assertTrue(
                "外部实现生成的公钥必须能验证本仓签出的断言（否则该 fixture 只算「能读」，不算「能用」）",
                verifier.verify(signature)
            )
        } finally {
            Arrays.fill(signingKey.keyBytes, 0.toByte())
        }

        passkey.privateKey.clear()
        passkey.prfSecret?.clear()
    }

    /**
     * 把外部实现给出的**公开**公钥字节装成 JCE 公钥：
     * ES256 为未压缩点 `0x04||X||Y`（65 字节），Ed25519 为 32 字节原始公钥补 RFC 8410 SPKI 前缀。
     */
    private fun jcePublicKey(algorithmId: Int, publicKeyHex: String): java.security.PublicKey {
        val raw = hexToBytes(publicKeyHex)
        return when (algorithmId) {
            PasskeyData.ALGORITHM_ES256 -> {
                assertEquals(ES256_PUBLIC_KEY_BYTES, raw.size)
                val params = AlgorithmParameters.getInstance(JCA_EC).apply {
                    init(ECGenParameterSpec(JCA_SECP256R1))
                }
                KeyFactory.getInstance(JCA_EC).generatePublic(
                    ECPublicKeySpec(
                        ECPoint(
                            BigInteger(1, raw, 1, SCALAR_BYTES),
                            BigInteger(1, raw, 1 + SCALAR_BYTES, SCALAR_BYTES)
                        ),
                        params.getParameterSpec(ECParameterSpec::class.java)
                    )
                )
            }

            PasskeyData.ALGORITHM_ED25519 -> {
                assertEquals(ED25519_PUBLIC_KEY_BYTES, raw.size)
                KeyFactory.getInstance(JCA_ED25519).generatePublic(X509EncodedKeySpec(ED25519_SPKI_PREFIX + raw))
            }

            else -> throw AssertionError("本用例未覆盖的算法：$algorithmId")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        assertEquals("十六进制长度必须为偶数", 0, hex.length % 2)
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        const val FIXTURE_RESOURCE_PATH = "/fixtures/passkey-interop/pykeepass-kpex.kdbx"
        const val DB_PASSWORD = "passkey-ext-fixture-2026"

        const val RP_ID = "passkey-interop.example"
        const val ES256_ENTRY_TITLE = "External ES256 Passkey"
        const val ED25519_ENTRY_TITLE = "External Ed25519 Passkey"
        const val ES256_USER_NAME = "ext-es256@passkey-interop.example"
        const val ED25519_USER_NAME = "ext-ed25519@passkey-interop.example"
        const val ES256_CREDENTIAL_ID = "ZXh0LWtleS1lczI1Ni1jcmVkZW50aWFsLWlk"
        const val ED25519_CREDENTIAL_ID = "ZXh0LWtleS1lZDI1NTE5LWNyZWRlbnRpYWwtaWQ"
        const val ES256_USER_HANDLE = "ZXh0LXVzZXItaGFuZGxlLWVzMjU2"
        const val ED25519_USER_HANDLE = "ZXh0LXVzZXItaGFuZGxlLWVkMjU1MTk"

        /** ES256 私钥标量 `0102…1f20` 对应的公开公钥（未压缩点，由 `cryptography` 独立导出）。 */
        const val ES256_PUBLIC_KEY_HEX =
            "04515c3d6eb9e396b904d3feca7f54fdcd0cc1e997bf375dca515ad0a6c3b4035f" +
                "4536be3a50f318fbf9a5475902a221502bef0d57e08c53b2cc0a56f17d9f9354"

        /** RFC 8032 §7.1 TEST 1 种子对应的公开公钥（32 字节原始公钥）。 */
        const val ED25519_PUBLIC_KEY_HEX = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

        /** 固定 PRF 秘密 `0x00..0x1f`（生成器内同源常量）的 Base64 文本。 */
        const val PRF_SECRET_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

        const val SCALAR_BYTES = 32
        const val SIGNING_MATERIAL_BYTES = 32
        const val ES256_PUBLIC_KEY_BYTES = 65
        const val ED25519_PUBLIC_KEY_BYTES = 32

        const val JCA_EC = "EC"
        const val JCA_SECP256R1 = "secp256r1"
        const val JCA_SHA256_ECDSA = "SHA256withECDSA"
        const val JCA_ED25519 = "Ed25519"

        /** RFC 8410 §4 的 Ed25519 `SubjectPublicKeyInfo` 固定 DER 前缀。 */
        val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
        )
    }
}
