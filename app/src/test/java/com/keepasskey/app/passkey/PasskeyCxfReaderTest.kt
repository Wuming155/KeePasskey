package com.keepasskey.app.passkey

import com.keepasskey.app.testutil.stripCommentsOnly
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.model.PasskeyKeyText
import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-337` 第 2 片 / AC②③：[PasskeyCxfReader] 的宿主离线用例。
 *
 * ## 夹具纪律（条目原文，本轮逐字遵守）
 *
 * **规范附录 A 的示例 JSON 本身即权威夹具**——原样收录在
 * `app/src/test/resources/passkey-import/cxf-appendix-a-example.json`（含 `FIXTURE.md` 出处与哈希），
 * 用例对它做**取值 / 改名 / 搬层**三种变形，**不**按臆想字段自造「规范示例」。
 * 其中那把 passkey 的 `key` 实测为合法 PKCS#8 DER 138 B（ecPublicKey + prime256v1），
 * 故 ES256 判据在官方样本上逐字节命中（口径 3 的实证依据）。
 *
 * ⚠️ 两处与条目正文的**读数差**（本轮实测校正，均已回写条目留痕）：
 * ① 附录 A 混装 **15 条凭据 / 15 种 `type`**（条目初记「14 种」）；
 * ② 裸 passkey 对象的紧凑 JSON 实测 **472 B**（条目初记 471 B）。
 * 两者都不改判据（混装是常态、上限远大于单凭据），仅读数如实。
 *
 * ## 与「未决项」的边界
 *
 * PRF 落库口径已由 `PD-48` 裁决二定为 (c′)，故本片不仅检出 `fido2Extensions` 双值，
 * **双值本身也一并解析出来**（供第 3 片写 `Passkey.PrfNoUv`）；`url` / 备注等展示层接线属第 4 片。
 */
class PasskeyCxfReaderTest {

    // ------------------------------------------------------------------
    // 权威夹具直取
    // ------------------------------------------------------------------

    /** 附录 A 全示例（规范原文，LF 结尾）。 */
    private fun appendixBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(APPENDIX_RESOURCE)) {
            "缺夹具资源 $APPENDIX_RESOURCE（测试资源目录是否被移动）"
        }.use { it.readBytes() }

    /** 附录 A 里那把 passkey 的对象树（可改后回序列化，作为各形态的正例内核）。 */
    private fun fixturePasskey(): MutableMap<String, Any?> {
        val root = SimpleJson.asObject(SimpleJson.parse(appendixBytes().decodeToString()))
            ?: error("附录 A 示例解析失败")
        val accounts = SimpleJson.arrayAt(root, "accounts").orEmpty()
        for (account in accounts) {
            for (item in SimpleJson.arrayAt(SimpleJson.asObject(account), "items").orEmpty()) {
                for (cred in SimpleJson.arrayAt(SimpleJson.asObject(item), "credentials").orEmpty()) {
                    val obj = SimpleJson.asObject(cred) ?: continue
                    if (SimpleJson.string(obj, "type") == "passkey") return obj.toMutableMap()
                }
            }
        }
        error("附录 A 示例里没有 passkey 凭据（夹具被改写？）")
    }

    private fun bare(payload: Map<String, Any?>): PasskeyCxfOutcome =
        PasskeyCxfReader.read(jsonOf(payload).toByteArray(Charsets.UTF_8))

    private fun parsed(outcome: PasskeyCxfOutcome): PasskeyCxfOutcome.Parsed = when (outcome) {
        is PasskeyCxfOutcome.Parsed -> outcome
        is PasskeyCxfOutcome.Rejected ->
            throw AssertionError("预期解析成功，实际被拒：${outcome.reason}")
    }

    // ------------------------------------------------------------------
    // 一、三级形态正例（裸对象 / 凭据数组 / 完整文档）
    // ------------------------------------------------------------------

    @Test
    fun `一 附录 A 的 passkey 对象逐字段取值（别名 PRF 亦被取出）`() {
        val keyText = SimpleJson.string(fixturePasskey(), "key").orEmpty()
        val outcome = bare(fixturePasskey())
        val p = parsed(outcome)
        assertEquals(PasskeyCxfSourceShape.BarePasskeyObject, p.notes.shape)
        assertEquals("webauthn.io", p.credential.relyingPartyId)
        assertEquals("johndoe", p.credential.userName)
        assertEquals("John Doe", p.credential.userDisplayName)
        assertEquals("规范形 Base64URL 无填充须原样归一", "Y3JlZGVudGlhbElkRXhhbXBsZQ", p.credential.credentialId)
        assertEquals("cnEzaNHWcYK3coWZjvoaV1Hj9gnI12mKe2dL2HZVFlY", p.credential.userHandle)
        assertEquals("DER 里嗅到 prime256v1 ⇒ ES256", PasskeyData.ALGORITHM_ES256, p.credential.algorithmId)
        assertTrue("PEM 驻留形态须与生成侧一致", String(p.credential.privateKeyPemChars).startsWith("-----BEGIN PRIVATE KEY-----"))
        assertEquals(
            "附录 A 用别名形 hmacSecret{secret}，PRF 仍须取出（PD-48 裁决二的前提）",
            "secret_key_data".toByteArray(Charsets.UTF_8).toList(),
            p.credential.prfWithUv?.toList()
        )
        assertNull("别名形天然只有一枚：禁止复制出第二枚假种子", p.credential.prfWithoutUv)
        assertEquals("别名单值不是丢弃项、也不是缺失项", emptyList<PasskeyCxfDropped>(), p.notes.dropped)
        assertEquals(
            "key 成员解码后就是附录 A 那把 138 字节 PKCS#8 DER",
            138,
            Base64.getUrlDecoder().decode(padBase64(keyText)).size
        )
        p.credential.privateKeyPemChars.fill('0')
    }

    @Test
    fun `二 凭据数组形态与完整文档形态都取到同一把凭据`() {
        val passkey = fixturePasskey()
        val arrayOutcome = PasskeyCxfReader.read("[${jsonOf(passkey)}]".toByteArray(Charsets.UTF_8))
        val array = parsed(arrayOutcome)
        assertEquals(PasskeyCxfSourceShape.CredentialArray, array.notes.shape)
        assertEquals("webauthn.io", array.credential.relyingPartyId)
        assertEquals(0, array.notes.additionalPasskeyCount)

        val document = linkedDocument(credentials = listOf(passkey))
        val docOutcome = PasskeyCxfReader.read(jsonOf(document).toByteArray(Charsets.UTF_8))
        val doc = parsed(docOutcome)
        assertEquals(PasskeyCxfSourceShape.Document, doc.notes.shape)
        assertEquals("两种形态取到同一把凭据", array.credential.credentialId, doc.credential.credentialId)
        array.credential.privateKeyPemChars.fill('0')
        doc.credential.privateKeyPemChars.fill('0')
    }

    /** §3.1~§3.2 的文档骨架：凭据挂在 `accounts[].items[].credentials[]`。 */
    private fun linkedDocument(
        credentials: List<Map<String, Any?>>,
        major: Long = 1L,
        minor: Long = 0L,
        collections: List<Any?>? = null
    ): Map<String, Any?> = mapOf(
        "version" to mapOf("major" to major, "minor" to minor),
        "exporterRpId" to "exporter.example.com",
        "exporterDisplayName" to "Example Exporter",
        "timestamp" to 1_738_368_000L,
        "accounts" to listOf(
            mapOf(
                "id" to "aWQ",
                "username" to "johndoe",
                "email" to "john@example.com",
                "items" to listOf(
                    mapOf("id" to "aXRlbQ", "title" to "webauthn.io", "credentials" to credentials)
                ),
                "collections" to (collections ?: emptyList<Map<String, Any?>>())
            )
        )
    )

    // ------------------------------------------------------------------
    // 二、路径与版本门（口径 2″）
    // ------------------------------------------------------------------

    @Test
    fun `三 凭据只挂在 collections 下的 LinkedItem 引用里时取不到`() {
        // LinkedItem = { item: b64url, ?account } 仅引用、不含凭据；把凭据塞进 collections.items
        // 是 PD-08 原表述的错误路径，必须证明本读取器**不**照它取值
        val embedded = mapOf("item" to "aXRlbQ", "credentials" to listOf(fixturePasskey()))
        val document = linkedDocument(credentials = emptyList(), collections = listOf(embedded))
        val outcome = PasskeyCxfReader.read(jsonOf(document).toByteArray(Charsets.UTF_8))
        val rejected = outcome as? PasskeyCxfOutcome.Rejected
        assertTrue("collections[].items[] 是 LinkedItem 引用，本读取器不该照它取凭据", rejected != null)
        assertEquals(PasskeyCxfReject.NoPasskeyCredential, rejected!!.reason)
    }

    @Test
    fun `四 版本门 major 非 1 即拒、minor 只增不改照常放行`() {
        val v2 = jsonOf(linkedDocument(credentials = listOf(fixturePasskey()), major = 2L))
            .toByteArray(Charsets.UTF_8)
        assertEquals(
            PasskeyCxfReject.DocumentVersionUnsupported,
            (PasskeyCxfReader.read(v2) as PasskeyCxfOutcome.Rejected).reason
        )
        val v19 = jsonOf(linkedDocument(credentials = listOf(fixturePasskey()), minor = 9L))
            .toByteArray(Charsets.UTF_8)
        assertTrue("§3.1.1：新增 minor 与未知成员都不算破坏性变更", PasskeyCxfReader.read(v19) is PasskeyCxfOutcome.Parsed)
    }

    @Test
    fun `五 未知成员一律忽略而非拒收`() {
        val payload = fixturePasskey().toMutableMap().apply {
            put("someFutureMember", mapOf("a" to 1L))
        }
        val p = parsed(bare(payload))
        assertEquals("webauthn.io", p.credential.relyingPartyId)
        p.credential.privateKeyPemChars.fill('0')
    }

    // ------------------------------------------------------------------
    // 三、扩展项账目（口径 2′ / AC②⑤⑥⑦⑧ / AC⑪②）
    // ------------------------------------------------------------------

    @Test
    fun `六 规范形 hmacCredentials 双值齐 ⇒ 两枚都取出且都不进丢弃清单`() {
        val p = parsed(bare(withHmac(fixturePasskey(), canonical("hmac-sha256"))))
        assertEquals(listOf<Byte>(1, 2, 3), p.credential.prfWithUv?.toList())
        assertEquals(listOf<Byte>(4, 5, 6), p.credential.prfWithoutUv?.toList())
        assertEquals(emptyList<PasskeyCxfDropped>(), p.notes.dropped)
        p.credential.privateKeyPemChars.fill('0')
    }

    @Test
    fun `七 未知 algorithm 只丢扩展项、凭据仍入库并点名`() {
        val payload = withHmac(fixturePasskey(), mapOf("hmacCredentials" to mapOf(
            "algorithm" to "hmac-sm3",
            "credWithUV" to "AQID",
            "credWithoutUV" to "BAME"
        )))
        val p = parsed(bare(payload))
        assertEquals("ignore this entry 不等于 ignore this credential", "webauthn.io", p.credential.relyingPartyId)
        assertEquals(listOf(PasskeyCxfDropped.HmacUnknownAlgorithm), p.notes.dropped)
        assertNull(p.credential.prfWithUv)
        assertNull(p.credential.prfWithoutUv)
        p.credential.privateKeyPemChars.fill('0')
    }

    @Test
    fun `八 规范形缺一枚才记 HmacIncomplete，别名形不记`() {
        val canonicalOnly = withHmac(fixturePasskey(), mapOf("hmacCredentials" to mapOf(
            "algorithm" to "hmac-sha256",
            "credWithUV" to "AQID"
        )))
        assertEquals(
            listOf(PasskeyCxfDropped.HmacIncomplete),
            parsed(bare(canonicalOnly)).notes.dropped
        )
        val alias = withHmac(fixturePasskey(), mapOf("hmacSecret" to mapOf(
            "algorithm" to "HS256",
            "secret" to "AQID"
        )))
        assertEquals(
            "别名形天然只有一枚，不该对用户报「缺失」",
            emptyList<PasskeyCxfDropped>(),
            parsed(bare(alias)).notes.dropped
        )
    }

    @Test
    fun `九 credBlob 与 largeBlob 与 payments 真值逐项点名、payments false 不点名`() {
        val payload = fixturePasskey().toMutableMap().apply {
            put("fido2Extensions", mapOf(
                "credBlob" to "AQID",
                "largeBlob" to mapOf("blob" to "BAME"),
                "payments" to true,
                "someUnknownExtension" to true
            ))
        }
        assertEquals(
            listOf(PasskeyCxfDropped.CredBlob, PasskeyCxfDropped.LargeBlob, PasskeyCxfDropped.Payments),
            parsed(bare(payload)).notes.dropped
        )
        val silent = fixturePasskey().toMutableMap().apply {
            put("fido2Extensions", mapOf("payments" to false))
        }
        assertEquals(emptyList<PasskeyCxfDropped>(), parsed(bare(silent)).notes.dropped)
    }

    @Test
    fun `十 PRF 长度不判不裁不拒收（规范 SHOULD be 32 bytes）`() {
        val short = withHmac(fixturePasskey(), mapOf("hmacCredentials" to mapOf(
            "algorithm" to "hmac-sha256",
            "credWithUV" to "AQID", // 3 字节
            "credWithoutUV" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
        )))
        val p = parsed(bare(short))
        assertEquals(3, p.credential.prfWithUv?.size)
        assertEquals("非 32 字节原样保真，不裁不拒", 64, p.credential.prfWithoutUv?.size)
        assertEquals("长度问题不进确认清单（用户无法行动）", emptyList<PasskeyCxfDropped>(), p.notes.dropped)
        p.credential.privateKeyPemChars.fill('0')
    }

    private fun canonical(algorithm: String): Map<String, Any?> = mapOf(
        "hmacCredentials" to mapOf(
            "algorithm" to algorithm,
            "credWithUV" to "AQID", // [1, 2, 3]
            "credWithoutUV" to "BAUG" // [4, 5, 6]
        )
    )

    private fun withHmac(passkey: MutableMap<String, Any?>, extensions: Map<String, Any?>): Map<String, Any?> =
        passkey.toMutableMap().apply { put("fido2Extensions", extensions) }

    // ------------------------------------------------------------------
    // 四、拒收路径（AC② 的 8 条以上，逐条静态错误码）
    // ------------------------------------------------------------------

    @Test
    fun `十一 仪式字段缺失与非法值逐条拒收`() {
        val cases = listOf(
            "缺 credentialId" to mutation { it.remove("credentialId") } to PasskeyCxfReject.MissingCeremonyField,
            "缺 userHandle" to mutation { it.remove("userHandle") } to PasskeyCxfReject.MissingCeremonyField,
            "缺 key" to mutation { it.remove("key") } to PasskeyCxfReject.MissingCeremonyField,
            "缺 rpId" to mutation { it.remove("rpId") } to PasskeyCxfReject.MissingCeremonyField,
            "rpId 全空白等同缺失" to mutation { it["rpId"] = "   " } to PasskeyCxfReject.MissingCeremonyField,
            "credentialId 非法 Base64" to mutation { it["credentialId"] = "!!!!" } to PasskeyCxfReject.InvalidBase64Url,
            "credentialId 长度为 1 mod 4 的非法形态" to mutation { it["credentialId"] = "AAAAA" } to PasskeyCxfReject.InvalidBase64Url,
            "credentialId 解出零字节" to mutation { it["credentialId"] = "" } to PasskeyCxfReject.InvalidBase64Url,
            "key 的 DER 无已知 OID" to mutation {
                it["key"] = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(20))
            } to PasskeyCxfReject.UnsupportedKeyAlgorithm,
            "裸单对象 type 非 passkey" to mutation { it["type"] = "totp" } to PasskeyCxfReject.NotPasskeyCredential,
            "既无 type 又无 KeePassXC 词表" to mutation {
                it.remove("type"); it.remove("rpId")
            } to PasskeyCxfReject.NotPasskeyCredential
        )
        for ((pair, expected) in cases) {
            val (label, payload) = pair
            val outcome = PasskeyCxfReader.read(jsonOf(payload).toByteArray(Charsets.UTF_8))
            val rejected = outcome as? PasskeyCxfOutcome.Rejected
            assertNotNullCase(label, rejected)
            assertEquals("$label ⇒ 错误码不符", expected, rejected!!.reason)
        }
    }

    @Test
    fun `十二 文档骨架层级不符与非法 JSON 一律静态拒收且不抛异常`() {
        val notArrayAccounts = jsonOf(mapOf("version" to mapOf("major" to 1L), "accounts" to "x"))
        val badVersion = jsonOf(mapOf("version" to "1.0", "accounts" to emptyList<Any>()))
        val truncated = "{\"type\":\"passkey\",\"rpId\":\"a\""
        val bareWord = "undefined"
        val controlInString = "{\"type\":\"passkey\",\"rpId\":\"a\u0007b\"}"
        val scalarRoot = "\"just a string\""
        val deep = "[".repeat(60) + "]".repeat(60)
        val payloads = listOf(
            "accounts 不是数组" to notArrayAccounts,
            "version 不是对象" to badVersion,
            "截断输入" to truncated,
            "裸词" to bareWord,
            "串内裸控制字符" to controlInString,
            "标量根" to scalarRoot,
            "超深嵌套" to deep
        )
        for ((label, text) in payloads) {
            val outcome = PasskeyCxfReader.read(text.toByteArray(Charsets.UTF_8))
            val rejected = outcome as? PasskeyCxfOutcome.Rejected
            assertNotNullCase(label, rejected)
            assertTrue(
                "$label ⇒ 应落在 MalformedJson / NestingMismatch 两个静态码之一",
                rejected!!.reason == PasskeyCxfReject.MalformedJson ||
                    rejected.reason == PasskeyCxfReject.NestingMismatch
            )
        }
    }

    @Test
    fun `十三 载荷上限边界 4096 放行、4097 即拒`() {
        val passkey = fixturePasskey()
        val base = jsonOf(passkey).toByteArray(Charsets.UTF_8)
        // 用未知成员把载荷精确填到边界值（未知成员按 §3.1.1 被忽略，不影响取值）
        val padTo: (Int) -> ByteArray = { size ->
            val filler = "\"pad\":\"" + "a".repeat(size - base.size - ",\"pad\":\"\"".length) + "\""
            (String(base.copyOfRange(0, base.size - 1), Charsets.UTF_8) + "," + filler + "}").toByteArray(Charsets.UTF_8)
        }
        assertEquals(
            PasskeyCxfReject.PayloadTooLarge,
            (PasskeyCxfReader.read(padTo(4097)) as PasskeyCxfOutcome.Rejected).reason
        )
        assertTrue("恰在上限内须放行", PasskeyCxfReader.read(padTo(4096)) is PasskeyCxfOutcome.Parsed)
    }

    // ------------------------------------------------------------------
    // 五、展示字段与混装文档（PD-48 裁决一 / PD-49 裁决三）
    // ------------------------------------------------------------------

    @Test
    fun `十四 展示字段缺失时容错补齐并如实登记，不回填 userHandle`() {
        val payload = mutation(fixturePasskey()) {
            it.remove("username")
            it.remove("userDisplayName")
        }
        val p = parsed(bare(payload))
        assertEquals("补齐只进展示字段", "webauthn.io", p.credential.userName)
        assertEquals("", p.credential.userDisplayName)
        assertEquals(
            listOf(PasskeyCxfDisplayField.UserName, PasskeyCxfDisplayField.UserDisplayName),
            p.notes.missingDisplayFields
        )
        assertEquals("仪式字段不得被补齐改写", "cnEzaNHWcYK3coWZjvoaV1Hj9gnI12mKe2dL2HZVFlY", p.credential.userHandle)
        p.credential.privateKeyPemChars.fill('0')
    }

    @Test
    fun `十五 混装类型文档跳过非 passkey 凭据并计数、取到第 k 把`() {
        val passkey = fixturePasskey()
        val mixed = listOf(
            mapOf("type" to "basic-auth", "username" to "u", "password" to "p"),
            mapOf("type" to "totp", "secret" to "JBSW", "period" to 30L, "digits" to 6L, "algorithm" to "sha1"),
            mapOf("type" to "note", "text" to mapOf("value" to "hi")),
            mapOf("type" to "credit-card", "cardholder" to mapOf("value" to "John")),
            passkey,
            mapOf("type" to "ssh-key", "privateKey" to "x"),
            mapOf("type" to "wifi", "ssid" to "ap")
        )
        val p = parsed(PasskeyCxfReader.read(jsonOf(linkedDocument(credentials = mixed)).toByteArray(Charsets.UTF_8)))
        assertEquals("取到的是混装里那把 passkey", "webauthn.io", p.credential.relyingPartyId)
        assertEquals("其余 6 条非 passkey 凭据逐条计数（第 5 位前 4 条、后 2 条）", 6, p.notes.skippedCredentialCount)
        assertEquals("passkey 只有一把 ⇒ 无「另有 N 把」点名", 0, p.notes.additionalPasskeyCount)
        p.credential.privateKeyPemChars.fill('0')
    }

    @Test
    fun `十六 文档内多把 passkey ⇒ 导第一把并点名其余`() {
        val second = fixturePasskey().toMutableMap().apply { this["rpId"] = "second.example" }
        val payload = jsonOf(linkedDocument(credentials = listOf(fixturePasskey(), second)))
            .toByteArray(Charsets.UTF_8)
        val p = parsed(PasskeyCxfReader.read(payload))
        assertEquals("webauthn.io", p.credential.relyingPartyId)
        assertEquals("PD-49 裁决三：不建逐把选择列表，但必须点名", 1, p.notes.additionalPasskeyCount)
        p.credential.privateKeyPemChars.fill('0')
    }

    // ------------------------------------------------------------------
    // 六、KeePassXC 兼容形（PD-08 第 1 项）
    // ------------------------------------------------------------------

    @Test
    fun `十七 KeePassXC passkey 单对象正例（标准 Base64 归一为规范形）`() {
        val rawId = byteArrayOf(0xFF.toByte(), 0x00, 0x7F, 'a'.code.toByte(), 'z'.code.toByte())
        val handle = ByteArray(32) { (it + 1).toByte() }
        val pem = String(fixturePasskeyPem(), Charsets.UTF_8)
        val payload = mapOf(
            "relyingParty" to "webauthn.io",
            "url" to "https://webauthn.io",
            "username" to "KPXC_USER",
            "credentialId" to Base64.getEncoder().encodeToString(rawId), // 标准 Base64：含 + / =
            "userHandle" to Base64.getEncoder().encodeToString(handle),
            "privateKey" to pem
        )
        val p = parsed(bare(payload))
        assertEquals(PasskeyCxfSourceShape.KeePassXCPasskeyFile, p.notes.shape)
        assertEquals("webauthn.io", p.credential.relyingPartyId)
        assertEquals("KPXC_USER", p.credential.userName)
        assertEquals("KeePassXC 形无 userDisplayName ⇒ 如实登记未提供", "", p.credential.userDisplayName)
        assertEquals(listOf(PasskeyCxfDisplayField.UserDisplayName), p.notes.missingDisplayFields)
        assertEquals(
            "标准 Base64 须归一为断言侧比对的规范形",
            Base64.getUrlEncoder().withoutPadding().encodeToString(rawId),
            p.credential.credentialId
        )
        assertEquals(
            Base64.getUrlEncoder().withoutPadding().encodeToString(handle),
            p.credential.userHandle
        )
        assertEquals(PasskeyData.ALGORITHM_ES256, p.credential.algorithmId)
        assertNull(p.credential.prfWithUv)
        p.credential.privateKeyPemChars.fill('0')
    }

    /** 同一把附录 A 私钥的 PEM 文本（KeePassXC 用 Botan PKCS8::PEM_encode，同为 PKCS#8 PEM）。 */
    private fun fixturePasskeyPem(): ByteArray {
        val key = SimpleJson.string(fixturePasskey(), "key").orEmpty()
        val der = Base64.getUrlDecoder().decode(padBase64(key))
        val chars = PasskeyKeyText.derToPemChars(der)
        return ByteArray(chars.size) { chars[it].code.toByte() } // PEM 恒 ASCII
    }

    @Test
    fun `十八 附录 A 全示例整块直读超上限即拒（文档级多凭据装不进单张二维码）`() {
        val bytes = appendixBytes()
        assertTrue(
            "夹具本身须大于上限，否则本例失去意义",
            bytes.size > PasskeyCxfReader.MAX_IMPORT_PAYLOAD_BYTES
        )
        assertEquals(
            "规范自己的完整示例（28 KB）在单张 QR 上物理不可达（口径 9：v1 不做多张装配）",
            PasskeyCxfReject.PayloadTooLarge,
            (PasskeyCxfReader.read(bytes) as PasskeyCxfOutcome.Rejected).reason
        )
    }

    @Test
    fun `十九 read 不改动调用方持有的载荷数组`() {
        val bytes = jsonOf(fixturePasskey()).toByteArray(Charsets.UTF_8)
        val snapshot = bytes.copyOf()
        val p = parsed(PasskeyCxfReader.read(bytes))
        assertArrayEquals(
            "读取器只清零自己分配的节点副本；载荷数组的擦除义务归扫码链上层（与 TOTP 现链同口径）",
            snapshot,
            bytes
        )
        p.credential.privateKeyPemChars.fill('0')
    }

    // ------------------------------------------------------------------
    // 七、AC③：私钥不经 String 的源码级守卫（本片这一层的可证形态）
    // ------------------------------------------------------------------

    @Test
    fun `二十 守卫 读取器与扫描器不得把私钥节点转成 String`() {
        val sources = listOf(READER_SOURCE, SCANNER_SOURCE)
        for (path in sources) {
            val file = File(repositoryRoot, path)
            assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
            val code = stripCommentsOnly(file.readText())
            assertFalse(
                "[$path] 私钥文本被物化为 String（AGENTS.md §3 铁律；ISSUE-P3-337 AC③）：" +
                    "asUtf8String 不得用于 key / privateKey / credWithUV / credWithoutUV / secret 节点",
                SECRET_TO_STRING.containsMatchIn(code)
            )
        }
        // 口径反校：判据必须能抓住「把私钥节点转 String」的坏形态（已知坏样本，必须命中）
        assertTrue(
            "守卫自身失效：坏样本未被抓住，等于没有守卫",
            SECRET_TO_STRING.containsMatchIn("val k = strNode(FIELD_KEY).asUtf8String()")
        )
        assertFalse(
            "守卫误报：非敏感文本（键名 / rpId）的正常转换被抓住",
            SECRET_TO_STRING.containsMatchIn("strNode(FIELD_RP_ID).asUtf8String()")
        )
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun mutation(passkey: Map<String, Any?>, block: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
        val copy = passkey.toMutableMap()
        block(copy)
        return copy
    }

    /** 以附录 A 那把 passkey 为内核做单点变形（缺字段 / 改值的负例都走这里）。 */
    private fun mutation(block: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> =
        mutation(fixturePasskey(), block)

    private fun assertNotNullCase(label: String, value: Any?) {
        assertTrue("$label ⇒ 未按拒收路径返回（拿到的是 $value）", value != null)
    }

    /** 把解析结果树回序列化成紧凑 JSON（夹具变形用，不手写「规范示例」）。 */
    private fun jsonOf(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is String -> "\"" + buildString(value.length) {
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
        } + "\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${jsonOf(v)}" }
        is List<*> -> value.joinToString(",", "[", "]") { jsonOf(it) }
        else -> "\"$value\""
    }

    private fun padBase64(text: String): ByteArray {
        val rem = text.length % 4
        return if (rem == 0) text.toByteArray(Charsets.UTF_8)
        else (text + "=".repeat(4 - rem)).toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val APPENDIX_RESOURCE = "/passkey-import/cxf-appendix-a-example.json"
        const val READER_SOURCE = "app/src/main/java/com/keepasskey/app/passkey/PasskeyCxfReader.kt"
        const val SCANNER_SOURCE = "app/src/main/java/com/keepasskey/app/passkey/ByteJsonScanner.kt"

        /** 私钥类节点被转成 String 的形态（同行内 `key` / `privateKey` / PRF 节点紧邻 asUtf8String） */
        val SECRET_TO_STRING = Regex(
            "(FIELD_KEY|KPXC_FIELD_PRIVATE_KEY|\"key\"|\"privateKey\"|credWithUV|credWithoutUV|EXT_FIELD_SECRET)" +
                "[^\\n]{0,60}asUtf8String\\(\\)"
        )

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory && File(candidate, "core/src/main/java").isDirectory) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
