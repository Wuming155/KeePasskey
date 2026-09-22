package com.keepasskey.app.passkey

import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * 注册响应**证明对象（attestationObject）互操作性对拍**。
 *
 * ## 为什么必须存在
 *
 * 仓库文档（`ISSUE-P2-254` AC①、限界 §4.3）如实记录：`buildAuthenticatorData` /
 * `coseKeyFor` / `CborEncoder` 在 `src/test` 与 `src/androidTest` 中命中数为 **0**，
 * 且端到端冒烟自 2026-09-19 起未执行 —— 即「注册材料是否真的能被外部实现解析」
 * **从未被任何独立实现验证过**。
 *
 * 在「同设备 / 同站点，Monica 与 KeePassDX 都能通过飞书校验、仅本仓失败」的对照中，
 * origin / 公钥编码 / `credProps` / AAGUID 均已逐一排除，**唯一剩下的就是这块盲区**。
 *
 * ## 本文件做什么
 *
 * 不复用生产侧的 CBOR 解析（那等于自证），而是：
 * 1. 调 [PasskeyRegistrationPayload.build] 产出真实响应 JSON；
 * 2. 用**手写的最小 CBOR 解析器**解出 `attestationObject` 的 `fmt` / `attStmt` / `authData`；
 * 3. 逐字段校验 `authData` 布局：`rpIdHash == SHA-256(rpId)`、flags 的 AT/UP/UV 位、
 *    `signCount`、`credentialIdLength` 与 `credentialId` 的**大端长度一致性**；
 * 4. 用 **JDK 标准 EC 设施**（`KeyFactory` + P-256 参数）从 COSE 里的 `x` / `y`
 *    **真实构造出公钥**（点必须落在曲线上，否则构造失败）；
 * 5. 反向校验 `attestationObject` 里的 credentialId 与响应 `id` / `rawId` 是同一条。
 */
class PasskeyRegistrationMaterialInteropTest {

    @Test
    fun `注册证明对象可被独立解析且公钥点落在P-256曲线上`() {
        val rpId = "feishu.cn"
        val passkey = PasskeyCryptoEngine.generateEs256KeyPair(
            relyingPartyId = rpId,
            userName = "interop-user",
            userHandle = "dXNlci1oYW5kbGU",
            userDisplayName = "Interop User"
        )

        val responseJson = PasskeyRegistrationPayload.build(
            passkeyData = passkey,
            challenge = CHALLENGE_B64,
            callerPackage = "com.example.caller",
            // UP | BE | BS | UV | AT —— 与真机 BiometricSucceeded 路径一致
            flags = (0x01 or 0x08 or 0x10 or 0x04 or 0x40).toByte(),
            prfEval = null,
            rpId = rpId,
            origin = "https://$rpId",
            credPropsRequested = false
        )

        val root = SimpleJson.asObject(SimpleJson.parse(responseJson))
        assertNotNull("响应必须是合法 JSON 对象", root)
        val response = SimpleJson.objectAt(root!!, WebAuthnJson.RESPONSE)
        assertNotNull("响应必须包含 response", response)

        // ---------------- 1. attestationObject 的 CBOR 结构 ----------------
        val attestationB64 = SimpleJson.string(response!!, WebAuthnJson.ATTESTATION_OBJECT)
        assertNotNull(attestationB64)
        val attestation = decodeB64Url(attestationB64!!)

        val cursor = CborCursor(attestation)
        val mapSize = cursor.readMapHeader()
        assertEquals("attestationObject 必须是 3 键 map", 3, mapSize)

        var fmt: String? = null
        var attStmtSize = -1
        var authData: ByteArray? = null
        repeat(mapSize) {
            // ⚠️ 此处**刻意使用规范字面量**而非 `WebAuthnJson` 常量：常量与被测代码同源，
            // 若常量本身写错，用常量断言就成了自证——本仓真实发生过：CBOR 键被写成
            // `authenticatorData`（响应 JSON 的字段名），而测试复用了同一个常量，
            // 于是实现与测试一起错，直到浏览器报
            // `field missing or invalid: attestationObject` 才暴露。规范字面量是唯一可靠的锚点。
            when (cursor.readTextString()) {
                "fmt" -> fmt = cursor.readTextString()
                "attStmt" -> attStmtSize = cursor.readMapHeader()
                "authData" -> authData = cursor.readByteString()
                else -> error("出现规范外的 attestationObject 键")
            }
        }
        assertEquals("fmt 必须是 none（本实现不伪造证明链）", WebAuthnJson.FORMAT_NONE, fmt)
        assertEquals("attStmt 必须是空 map", 0, attStmtSize)
        assertNotNull("authData 必须存在", authData)
        assertTrue("CBOR 必须被完整消费，不得有尾随字节", cursor.exhausted())

        // 最直接的防线：CBOR 原始字节里必须出现 ASCII 的 `authData`（即 `68 61 75 74 68 44 61 74 61`）。
        // 该断言不依赖任何共享常量，任何「键名被改成 authenticatorData 之类」的回归都会在此失败。
        assertTrue(
            "attestationObject 的 CBOR 必须含规范键 \"authData\"；" +
                "误用 \"authenticatorData\" 会让浏览器转换失败并抛 UnknownError",
            String(attestation, Charsets.ISO_8859_1).contains("authData")
        )
        assertTrue(
            "attestationObject 的 CBOR 不得出现 \"authenticatorData\" 键",
            !String(attestation, Charsets.ISO_8859_1).contains("authenticatorData")
        )

        // ---------------- 2. authData 布局 ----------------
        val auth = authData!!
        assertTrue("authData 至少包含 rpIdHash+flags+signCount+attestedCredentialData", auth.size > 55)

        val rpIdHash = auth.copyOfRange(0, 32)
        assertArrayEquals(
            "rpIdHash 必须是 SHA-256(rpId)",
            MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8)),
            rpIdHash
        )

        val flags = auth[32].toInt() and 0xFF
        assertEquals("UP 位必须置位", 0x01, flags and 0x01)
        assertEquals("UV 位必须置位（本次为强验证）", 0x04, flags and 0x04)
        assertEquals("AT 位必须置位（注册路径携带证明凭据数据）", 0x40, flags and 0x40)

        val signCount = ((auth[33].toInt() and 0xFF) shl 24) or
            ((auth[34].toInt() and 0xFF) shl 16) or
            ((auth[35].toInt() and 0xFF) shl 8) or
            (auth[36].toInt() and 0xFF)
        assertEquals("注册时 signCount 必须为 0", 0, signCount)

        // aaguid(16) + credIdLen(2)
        val aaguid = auth.copyOfRange(37, 53)
        assertEquals(
            "AAGUID 必须是本认证器登记的 UUIDv5 身份（非全零）",
            PasskeyCryptoEngine.DEFAULT_AAGUID.toList(),
            aaguid.toList()
        )

        val credIdLen = ((auth[53].toInt() and 0xFF) shl 8) or (auth[54].toInt() and 0xFF)
        val credIdStart = 55
        val credIdEnd = credIdStart + credIdLen
        assertTrue("credentialId 不得越界", credIdEnd <= auth.size)
        val credentialId = auth.copyOfRange(credIdStart, credIdEnd)

        // credentialId 必须与响应 id / rawId 指向同一条凭据
        val idField = SimpleJson.string(root, WebAuthnJson.ID)
        val rawIdField = SimpleJson.string(root, WebAuthnJson.RAW_ID)
        assertEquals("id 与 rawId 必须一致", idField, rawIdField)
        assertArrayEquals(
            "authData 内 credentialId 必须等于响应 rawId 的解码结果",
            decodeB64Url(rawIdField!!),
            credentialId
        )

        // ---------------- 3. COSE 公钥：用独立设施真实构造公钥 ----------------
        val coseBytes = auth.copyOfRange(credIdEnd, auth.size)
        val cose = CborCursor(coseBytes)
        val coseSize = cose.readMapHeader()
        var kty = 0
        var alg = 0
        var crv = 0
        var x: ByteArray? = null
        var y: ByteArray? = null
        repeat(coseSize) {
            when (cose.readIntKey()) {
                1 -> kty = cose.readIntValue()
                3 -> alg = cose.readIntValue()
                -1 -> crv = cose.readIntValue()
                -2 -> x = cose.readByteString()
                -3 -> y = cose.readByteString()
                else -> error("出现规范外的 COSE 键")
            }
        }
        assertTrue("COSE 必须被完整消费", cose.exhausted())
        assertEquals("kty 必须是 EC2(2)", 2, kty)
        assertEquals("alg 必须是 ES256(-7)", -7, alg)
        assertEquals("crv 必须是 P-256(1)", 1, crv)
        assertEquals("x 必须是 32 字节", 32, x!!.size)
        assertEquals("y 必须是 32 字节", 32, y!!.size)

        // 点必须落在 P-256 曲线上，否则此处会抛 InvalidKeySpecException —— 这是最强的一条断言
        val ecParams = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(
                ECPoint(BigInteger(1, x), BigInteger(1, y)),
                ecParams
            )
        )
        assertNotNull("COSE 内的 EC 公钥必须能被标准库构造出来", publicKey)

        // 与库内存储的公钥（同一份未压缩点）交叉核对
        val storedRaw = Base64.getDecoder().decode(passkey.publicKeyBase64)
        assertEquals("库内 ES256 公钥应为未压缩点", 65, storedRaw.size)
        assertArrayEquals("COSE 的 x 必须等于库内公钥点的 X", storedRaw.copyOfRange(1, 33), x)
        assertArrayEquals("COSE 的 y 必须等于库内公钥点的 Y", storedRaw.copyOfRange(33, 65), y)
    }

    @Test
    fun `响应体不包含超规范字段且clientDataJSON字段完整`() {
        val rpId = "feishu.cn"
        val passkey = PasskeyCryptoEngine.generateEs256KeyPair(rpId, "u", "aGFuZGxl", "U")
        val responseJson = PasskeyRegistrationPayload.build(
            passkeyData = passkey,
            challenge = CHALLENGE_B64,
            callerPackage = "com.example.caller",
            flags = 0x5D,
            prfEval = null,
            rpId = rpId,
            origin = "https://$rpId",
            credPropsRequested = false
        )

        val root = SimpleJson.asObject(SimpleJson.parse(responseJson))!!
        val response = SimpleJson.objectAt(root, WebAuthnJson.RESPONSE)!!

        assertEquals("type 必须是 public-key", WebAuthnJson.CREDENTIAL_TYPE_PUBLIC_KEY, SimpleJson.string(root, WebAuthnJson.TYPE))
        assertEquals(
            "authenticatorAttachment 必须是 platform",
            WebAuthnJson.ATTACHMENT_PLATFORM,
            SimpleJson.string(root, WebAuthnJson.AUTHENTICATOR_ATTACHMENT)
        )
        assertEquals(
            "publicKeyAlgorithm 必须是 COSE 算法号 -7（ES256）",
            PasskeyData.ALGORITHM_ES256,
            SimpleJson.int(response, WebAuthnJson.PUBLIC_KEY_ALGORITHM)
        )

        // transports 必须同时声明 internal 与 hybrid（对齐两个参考实现）
        val transports = SimpleJson.arrayAt(response, WebAuthnJson.TRANSPORTS).orEmpty()
        assertTrue("transports 必须含 internal", transports.contains(WebAuthnJson.TRANSPORT_INTERNAL))
        assertTrue("transports 必须含 hybrid", transports.contains(WebAuthnJson.TRANSPORT_HYBRID))

        // clientDataJSON：type/challenge/origin 三项必须存在且取值正确
        val clientData = SimpleJson.asObject(
            SimpleJson.parse(String(decodeB64Url(SimpleJson.string(response, WebAuthnJson.CLIENT_DATA_JSON)!!)))
        )!!
        assertEquals(WebAuthnJson.CLIENT_DATA_TYPE_CREATE, SimpleJson.string(clientData, WebAuthnJson.TYPE))
        assertEquals(CHALLENGE_B64, SimpleJson.string(clientData, WebAuthnJson.CHALLENGE))
        assertEquals("https://$rpId", SimpleJson.string(clientData, WebAuthnJson.ORIGIN))

        // 未请求 credProps ⇒ clientExtensionResults 必须是空对象（不得凭空回传）
        val extResults = SimpleJson.objectAt(root, WebAuthnJson.CLIENT_EXTENSION_RESULTS)
        assertNotNull("clientExtensionResults 必须存在", extResults)
        assertTrue(
            "未请求 credProps 时不得回传该扩展（实测飞书场景即如此）",
            extResults!!.isEmpty()
        )

        // response.publicKey 必须是 SPKI（独立解析成功即证明），不能用 COSE
        val spki = decodeB64Url(SimpleJson.string(response, WebAuthnJson.PUBLIC_KEY)!!)
        val parsedSpki = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.X509EncodedKeySpec(spki)
        )
        assertNotNull("response.publicKey 必须是可解析的 SPKI 公钥", parsedSpki)
    }

    // ================= 独立实现的最小 CBOR 解析器（不复用生产侧编码器） =================

    /** 只解析本场景需要的 major type：unsigned/negative int、byte string、text string、map */
    private class CborCursor(private val bytes: ByteArray) {
        private var pos = 0

        fun exhausted(): Boolean = pos == bytes.size

        fun readMapHeader(): Int {
            val major = readMajorAndArgument()
            check(major.first == MAJOR_MAP) { "期望 CBOR map，实际 major=${major.first}" }
            return major.second.toInt()
        }

        fun readTextString(): String {
            val major = readMajorAndArgument()
            check(major.first == MAJOR_TEXT_STRING) { "期望 CBOR text string，实际 major=${major.first}" }
            val len = major.second.toInt()
            val out = bytes.copyOfRange(pos, pos + len)
            pos += len
            return String(out, Charsets.UTF_8)
        }

        fun readByteString(): ByteArray {
            val major = readMajorAndArgument()
            check(major.first == MAJOR_BYTE_STRING) { "期望 CBOR byte string，实际 major=${major.first}" }
            val len = major.second.toInt()
            val out = bytes.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        /** COSE 的键：小整数（可能为负） */
        fun readIntKey(): Int = readIntValue()

        fun readIntValue(): Int {
            val (major, argument) = readMajorAndArgument()
            return when (major) {
                MAJOR_UNSIGNED -> argument.toInt()
                MAJOR_NEGATIVE -> (-1L - argument).toInt()
                else -> error("期望 CBOR 整数，实际 major=$major")
            }
        }

        /** 返回 (majorType, argument)，长度采用 RFC 8949 的定长形式 */
        private fun readMajorAndArgument(): Pair<Int, Long> {
            check(pos < bytes.size) { "CBOR 数据提前结束" }
            val initial = bytes[pos++].toInt() and 0xFF
            val major = initial ushr 5
            val additional = initial and 0x1F
            val argument = when {
                additional < 24 -> additional.toLong()
                additional == 24 -> readRaw(1)
                additional == 25 -> readRaw(2)
                additional == 26 -> readRaw(4)
                additional == 27 -> readRaw(8)
                else -> error("不支持的 CBOR 附加信息: $additional")
            }
            return major to argument
        }

        private fun readRaw(count: Int): Long {
            check(pos + count <= bytes.size) { "CBOR 数据提前结束（读 $count 字节）" }
            var value = 0L
            repeat(count) {
                value = (value shl 8) or (bytes[pos++].toLong() and 0xFF)
            }
            return value
        }

        private companion object {
            const val MAJOR_UNSIGNED = 0
            const val MAJOR_NEGATIVE = 1
            const val MAJOR_BYTE_STRING = 2
            const val MAJOR_TEXT_STRING = 3
            const val MAJOR_MAP = 5
        }
    }

    private companion object {
        /** 一个固定的 Base64URL challenge（长度 32 字节的等价形式） */
        const val CHALLENGE_B64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        fun decodeB64Url(text: String): ByteArray {
            val normalized = text.replace('-', '+').replace('_', '/')
            val padded = when (normalized.length % 4) {
                2 -> "$normalized=="
                3 -> "$normalized="
                0 -> normalized
                else -> error("非法 Base64URL 长度")
            }
            return Base64.getDecoder().decode(padded)
        }
    }
}
