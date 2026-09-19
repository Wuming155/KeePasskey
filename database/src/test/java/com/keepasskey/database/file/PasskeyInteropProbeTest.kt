package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.model.PasskeyKeyText
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Arrays
import java.util.Base64

/**
 * 通行密钥 `KPEX_PASSKEY_*` 产物对拍探针（`ISSUE-P2-210`；证据纪律见 `AGENTS.md` 规则 8）。
 *
 * ## 为什么需要它
 *
 * `ISSUE-P2-210` 的核心事实是：通行密钥落库 schema 虽已对齐 KeePassXC / KeePassDX
 * （`KPEX_PASSKEY_*` + PKCS#8 PEM），但**全部**既有对拍都是「自家 writer → 自家 reader」
 * 加格式常数断言——自读自写恒为绿，**证明不了**库文件级互操作。
 * 本用例把**真实产物**留在磁盘上，供**官方实现**（`keepassxc-cli` / `pykeepass`）做端到端对拍。
 *
 * ## 产物（`build/` 可丢弃、不入库）
 *
 * - 库文件：`build/interop-probe/keepasskey-passkey-probe.kdbx`
 * - 机器可读清单：`build/interop-probe/keepasskey-passkey-probe.expected.json`
 *   （供 `tools/passkey-interop/verify_interop.py` 逐字段核对；**不含**任何秘密明文——
 *   PRF 秘密与私钥只留 SHA-256 / 长度判据）
 * - 人读说明：`build/interop-probe/PASSKEY_PROBE.md`（SHA-256、口令、复现命令）
 * - 口令：本类常量 [PROBE_PASSWORD]（仅测试用，非敏感）
 *
 * ## 产物内容（三条 KPEX 通行密钥条目，覆盖本仓可生成的全部算法）
 *
 * | 条目 | 算法 | 覆盖的 KPEX 面 |
 * |---|---|---|
 * | `Passkey ES256 (…)` | ES256 (-7) | 全部 `KPEX_PASSKEY_*` 含 `_PRF` |
 * | `Passkey Ed25519 (…)` | Ed25519 (-8) | 除 PRF 外的全部键 |
 * | `Passkey RS256 (…)` | RS256 (-257) | 除 PRF 外的全部键 |
 *
 * ## 外用验证（官方实现端到端对拍，本机已实测可用：pykeepass 4.2.0 / keepassxc-cli 2.7.12）
 *
 * ```bash
 * python tools/passkey-interop/verify_interop.py            # 权威判据：逐字段 + PKCS#8 独立解析
 * echo -n '<口令>' | keepassxc-cli show -q --all -s <产物路径> '<条目标题>'   # 独立实现目视核对
 * ```
 *
 * ## 进程内守卫与外部证据的分工
 *
 * 本用例内的断言（读写往返、保护位、PEM 通道还原、JCE 独立验签）是**必要但不充分**条件——
 * 它们只能证伪自家两侧的不一致；**互操作结论只由外部官方实现的读取结果给出**（§38）。
 *
 * ## KDF 选择
 *
 * 刻意使用 **AES-KDF（6000 轮）**而非默认 Argon2id：让外用工具成功 / 失败只反映**被验对象
 * （KPEX schema 与 PKCS#8 PEM）**，而不掺杂第三方工具对 Argon2 变体的支持差异。
 * 轮数取小值以保证外用工具解锁耗时可控（与 `OwnProductInteropProbeTest` 同口径）。
 */
class PasskeyInteropProbeTest {

    private fun probeTime(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private fun probeHeader(): KdbxHeader =
        KdbxHeader.createDefault(useArgon2 = false).copy(
            kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 0x5A }, rounds = PROBE_AES_ROUNDS)
        )

    /**
     * 把原始字节以 **Base64 文本**封装进 [ProtectedString]，全程不经 `String`——
     * 与生产侧「秘密不物化为不可擦 String」口径一致（探针数据虽为合成，仍按同纪律书写）。
     */
    private fun sealedBase64(bytes: ByteArray): ProtectedString {
        val encoded = Base64.getEncoder().encode(bytes)
        val chars = CharArray(encoded.size) { encoded[it].toInt().toChar() }
        try {
            return ProtectedString(chars, isProtected = true)
        } finally {
            Arrays.fill(encoded, 0.toByte())
            Arrays.fill(chars, '0')
        }
    }

    /** 生成 32 字节 PRF 秘密材料（`KPEX_PASSKEY_PRF` 口径：Base64 文本、受保护）。 */
    private fun randomPrfSecret(): ProtectedString {
        val raw = ByteArray(PRF_SECRET_BYTES)
        try {
            SecureRandom().nextBytes(raw)
            return sealedBase64(raw)
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }

    private fun passkeyEntry(passkey: PasskeyData, title: String, time: Instant): KdbxEntry =
        KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
                KdbxConstants.Fields.USER_NAME to ProtectedString(passkey.userName, isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString(PROBE_ENTRY_PASSWORD, isProtected = true),
                KdbxConstants.Fields.URL to ProtectedString("https://${passkey.relyingPartyId}", isProtected = false),
                KdbxConstants.Fields.NOTES to ProtectedString("", isProtected = false)
            ),
            customFields = passkey.toCustomFields(),
            times = KdbxTimes(
                creationTime = time,
                lastModificationTime = time,
                lastAccessTime = time,
                expiryTime = time,
                usageCount = 0L
            ),
            tags = listOf("passkey-interop")
        )

    /**
     * 产出对拍产物 + 在进程内锁定「schema / 保护位 / PEM 通道 / 公钥一致性」四条不变量。
     */
    @Test
    fun `产出可被官方客户端读取的KPEX通行密钥对拍产物`() {
        val time = probeTime()

        val es256 = PasskeyCryptoEngine.generateEs256KeyPair(
            relyingPartyId = PROBE_RP_ID,
            userName = ES256_USER_NAME,
            userHandle = PROBE_USER_HANDLE,
            userDisplayName = "Interop Probe User"
        ).copy(prfSecret = randomPrfSecret())

        val ed25519 = PasskeyCryptoEngine.generateEd25519KeyPair(
            relyingPartyId = PROBE_RP_ID,
            userName = ED25519_USER_NAME,
            userHandle = PROBE_USER_HANDLE,
            userDisplayName = "Interop Probe User"
        )

        val rs256 = PasskeyCryptoEngine.generateRs256KeyPair(
            relyingPartyId = PROBE_RP_ID,
            userName = RS256_USER_NAME,
            userHandle = PROBE_USER_HANDLE,
            userDisplayName = "Interop Probe User"
        )

        val database = KdbxDatabase(
            header = probeHeader(),
            databaseName = "PasskeyInteropProbe",
            databaseDescription = "KPEX passkey interop probe artifact (ISSUE-P2-210)",
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    passkeyEntry(es256, ES256_ENTRY_TITLE, time),
                    passkeyEntry(ed25519, ED25519_ENTRY_TITLE, time),
                    passkeyEntry(rs256, RS256_ENTRY_TITLE, time)
                )
            )
        )

        val password = PROBE_PASSWORD.toCharArray()
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, database, password)
        val fileBytes = bos.toByteArray()
        assertTrue("产物应包含头部与载荷", fileBytes.size > 200)

        // ── 进程内守卫（必要不充分）：自家读回 → schema 完整性 → PEM 通道 → JCE 独立验签 ──────────
        val loaded = KdbxFile.load(ByteArrayInputStream(fileBytes), password, keyFileData = null)
        val entriesByTitle = loaded.rootGroup.entries.associateBy { it.title }
        val loadedEs256 = requireNotNull(entriesByTitle[ES256_ENTRY_TITLE]) { "ES256 条目必须可读回" }
        val loadedEd25519 = requireNotNull(entriesByTitle[ED25519_ENTRY_TITLE]) { "Ed25519 条目必须可读回" }
        val loadedRs256 = requireNotNull(entriesByTitle[RS256_ENTRY_TITLE]) { "RS256 条目必须可读回" }

        assertKpexSchemaShape(loadedEs256, expectPrf = true)
        assertKpexSchemaShape(loadedEd25519, expectPrf = false)
        assertKpexSchemaShape(loadedRs256, expectPrf = false)

        val restoredEs256 = PasskeyData.fromCustomFields(loadedEs256.customFields)
        assertNotNull("ES256 条目必须能还原为 PasskeyData", restoredEs256)
        assertEquals(PasskeyData.ALGORITHM_ES256, restoredEs256!!.algorithmId)
        assertEquals(es256.credentialId, restoredEs256.credentialId)
        assertEquals(es256.publicKeyBase64, restoredEs256.publicKeyBase64)

        val restoredEd25519 = PasskeyData.fromCustomFields(loadedEd25519.customFields)
        assertNotNull("Ed25519 条目必须能还原为 PasskeyData", restoredEd25519)
        assertEquals(PasskeyData.ALGORITHM_ED25519, restoredEd25519!!.algorithmId)
        assertEquals(ed25519.credentialId, restoredEd25519.credentialId)
        assertEquals(ed25519.publicKeyBase64, restoredEd25519.publicKeyBase64)

        val restoredRs256 = PasskeyData.fromCustomFields(loadedRs256.customFields)
        assertNotNull("RS256 条目必须能还原为 PasskeyData", restoredRs256)
        assertEquals(PasskeyData.ALGORITHM_RS256, restoredRs256!!.algorithmId)
        assertEquals(rs256.credentialId, restoredRs256.credentialId)
        assertEquals(rs256.publicKeyBase64, restoredRs256.publicKeyBase64)

        assertSignatureVerifiesWithPublishedPublicKey(restoredEs256)
        assertSignatureVerifiesWithPublishedPublicKey(restoredEd25519)
        assertSignatureVerifiesWithPublishedPublicKey(restoredRs256)
        restoredEs256.privateKey.clear()
        restoredEd25519.privateKey.clear()
        restoredRs256.privateKey.clear()

        // ── 落盘留存供外用对拍 ───────────────────────────────────────────────────────────────
        val outDir = File(System.getProperty("user.dir"), PROBE_DIR_NAME).apply { mkdirs() }
        val outFile = File(outDir, PROBE_FILE_NAME)
        outFile.writeBytes(fileBytes)

        val sha256Hex = HashUtil.sha256(fileBytes).joinToString("") { "%02x".format(it) }
        File(outDir, PROBE_MANIFEST_NAME).writeText(buildManifest(es256, ed25519, rs256))
        File(outDir, PROBE_NOTE_NAME).writeText(
            buildNote(outFile, fileBytes.size, sha256Hex)
        )
        assertTrue("产物必须留在磁盘上供外用对拍", outFile.isFile && outFile.length() > 200)

        es256.privateKey.clear()
        ed25519.privateKey.clear()
        rs256.privateKey.clear()
        es256.prfSecret?.clear()
    }

    /**
     * KPEX schema 形状守卫：必需的 6 个 `KPEX_PASSKEY_*` 键齐备、保护位与 KeePassXC 口径一致、
     * 扩展键齐备、`PRF` 按需出现。任一漂移都会让官方客户端读不到 / 读错——这是**本仓一侧**的
     * 责任面，外部对拍无法替代（外部只能反映结果）。
     */
    private fun assertKpexSchemaShape(entry: KdbxEntry, expectPrf: Boolean) {
        val byKey = entry.customFields.associateBy { it.key }

        // 必需 KPEX 键（KeePassXC 写入口径：受保护者 = USER_HANDLE / CREDENTIAL_ID / PRIVATE_KEY_PEM）
        assertTrue(
            "缺少必需 KPEX 键 KPEX_PASSKEY_RELYING_PARTY",
            byKey.containsKey(PasskeyData.KPEX_FIELD_RELYING_PARTY)
        )
        assertTrue(
            "缺少必需 KPEX 键 KPEX_PASSKEY_CREDENTIAL_ID",
            byKey.containsKey(PasskeyData.KPEX_FIELD_CREDENTIAL_ID)
        )
        assertTrue(
            "缺少必需 KPEX 键 KPEX_PASSKEY_PRIVATE_KEY_PEM",
            byKey.containsKey(PasskeyData.KPEX_FIELD_PRIVATE_KEY)
        )

        assertFalse("RP ID 属公开材料，不得为受保护字段", byKey.getValue(PasskeyData.KPEX_FIELD_RELYING_PARTY).isProtected)
        assertTrue("Credential ID 必须受保护（KeePassXC 口径）", byKey.getValue(PasskeyData.KPEX_FIELD_CREDENTIAL_ID).isProtected)
        assertTrue("User Handle 必须受保护（KeePassXC 口径）", byKey.getValue(PasskeyData.KPEX_FIELD_USER_HANDLE).isProtected)
        assertTrue("私钥 PEM 必须受保护", byKey.getValue(PasskeyData.KPEX_FIELD_PRIVATE_KEY).isProtected)
        assertFalse("用户名属公开材料，不得为受保护字段", byKey.getValue(PasskeyData.KPEX_FIELD_USERNAME).isProtected)

        // 备份位文本必须是 KeePassXC 口径的 `1` / `0`
        val be = byKey.getValue(PasskeyData.KPEX_FIELD_FLAG_BE).value.readString()
        val bs = byKey.getValue(PasskeyData.KPEX_FIELD_FLAG_BS).value.readString()
        assertTrue("KPEX_PASSKEY_FLAG_BE 必须是 `1` / `0` 文本，实为 `$be`", be == "1" || be == "0")
        assertTrue("KPEX_PASSKEY_FLAG_BS 必须是 `1` / `0` 文本，实为 `$bs`", bs == "1" || bs == "0")

        // 私钥文本必须是 PKCS#8 PEM（外部工具按此解析；非 PEM 即互操作失败）。
        // 走字节通道判定，不物化私钥 String（ISSUE-P1-02 敏感数据铁律，测试侧同样适用）。
        val pemIsPkcs8 = byKey.getValue(PasskeyData.KPEX_FIELD_PRIVATE_KEY).value.useUtf8 { bytes ->
            val header = PKCS8_PEM_HEADER.toByteArray(Charsets.UTF_8)
            bytes.size > header.size && header.indices.all { bytes[it] == header[it] }
        }
        assertTrue("私钥必须以 PKCS#8 PEM 文本承载（缺少 `$PKCS8_PEM_HEADER` 首行）", pemIsPkcs8)

        // 本仓扩展键（对其它管理器为无关属性，但必须在场以保证自家读回不依赖 OID 嗅探）
        assertTrue("缺少扩展键 Passkey.Algorithm", byKey.containsKey(PasskeyData.FIELD_ALGORITHM))
        assertTrue("缺少扩展键 Passkey.PublicKey", byKey.containsKey(PasskeyData.FIELD_PUBLIC_KEY))

        if (expectPrf) {
            val prf = byKey[PasskeyData.KPEX_FIELD_PRF]
            assertNotNull("注册携带 prf 时 KPEX_PASSKEY_PRF 必须在场", prf)
            assertTrue("PRF 秘密必须受保护", prf!!.isProtected)
            val decoded = prf.value.useUtf8 { Base64.getDecoder().decode(it) }
            try {
                assertEquals("PRF 秘密为 32 字节材料", PRF_SECRET_BYTES, decoded.size)
            } finally {
                Arrays.fill(decoded, 0.toByte())
            }
        } else {
            assertFalse("未携带 prf 的条目不得写入 KPEX_PASSKEY_PRF", byKey.containsKey(PasskeyData.KPEX_FIELD_PRF))
        }
    }

    /**
     * **独立**验签：用 JDK 自带 JCE（`SunEC`）以条目公开的 `Passkey.PublicKey` 校验
     * 生产签名通道产出的签名。
     *
     * 这条断言把「PEM 私钥」与「扩展键公钥」两处**绑定**起来——若 PEM 与公钥不匹配
     * （例如误写命名曲线、标量字节序错位），外部管理器即便能读出字段也无法真正使用该凭据。
     */
    private fun assertSignatureVerifiesWithPublishedPublicKey(restored: PasskeyData) {
        val dataToSign = PasskeyCryptoEngine.buildAuthenticatorData(
            restored.relyingPartyId,
            PasskeyCryptoEngine.FLAG_UP,
            1
        ) + ByteArray(32) { 0x24 }

        val signingKey = restored.usePrivateKeyBytes { raw ->
            requireNotNull(PasskeyCryptoEngine.decodePemPrivateKeyText(raw)) {
                "驻留的 PKCS#8 PEM 必须能经生产 PEM 通道还原签名材料"
            }
        }
        try {
            assertEquals(
                "PKCS#8 OID 还原出的算法必须与条目算法一致",
                restored.algorithmId,
                signingKey.algorithmId
            )
            val signature = PasskeyCryptoEngine.signAssertion(
                signingKey.algorithmId,
                signingKey.keyBytes,
                dataToSign
            )

            val javaPublicKey = when (restored.algorithmId) {
                PasskeyData.ALGORITHM_ES256 -> {
                    val point = Base64.getDecoder().decode(restored.publicKeyBase64)
                    assertEquals("ES256 公钥为未压缩点 0x04||X||Y", ES256_PUBLIC_KEY_BYTES, point.size)
                    assertEquals("未压缩点首字节必须是 0x04", 0x04, point[0].toInt())
                    val params = AlgorithmParameters.getInstance(JCA_EC).apply {
                        init(ECGenParameterSpec(JCA_SECP256R1))
                    }
                    KeyFactory.getInstance(JCA_EC).generatePublic(
                        ECPublicKeySpec(
                            ECPoint(
                                java.math.BigInteger(1, point, 1, ES256_SCALAR_BYTES),
                                java.math.BigInteger(1, point, 1 + ES256_SCALAR_BYTES, ES256_SCALAR_BYTES)
                            ),
                            params.getParameterSpec(ECParameterSpec::class.java)
                        )
                    )
                }

                PasskeyData.ALGORITHM_ED25519 -> {
                    val raw = Base64.getDecoder().decode(restored.publicKeyBase64)
                    assertEquals("Ed25519 公钥为 32 字节原始公钥", ED25519_PUBLIC_KEY_BYTES, raw.size)
                    KeyFactory.getInstance(JCA_ED25519).generatePublic(
                        X509EncodedKeySpec(ED25519_SPKI_PREFIX + raw)
                    )
                }

                PasskeyData.ALGORITHM_RS256 -> {
                    // RS256 的公钥形态为 SPKI DER（Base64），JCE 可直接吃
                    KeyFactory.getInstance(JCA_RSA).generatePublic(
                        X509EncodedKeySpec(Base64.getDecoder().decode(restored.publicKeyBase64))
                    )
                }

                else -> throw AssertionError("探针未覆盖的算法：${restored.algorithmId}")
            }

            val verifier = Signature.getInstance(
                when (restored.algorithmId) {
                    PasskeyData.ALGORITHM_ES256 -> JCA_SHA256_ECDSA
                    PasskeyData.ALGORITHM_ED25519 -> JCA_ED25519
                    else -> JCA_SHA256_RSA
                }
            )
            verifier.initVerify(javaPublicKey)
            verifier.update(dataToSign)
            assertTrue(
                "JCE 以条目公开公钥必须能验证该签名（PEM 私钥与公钥不匹配即互操作不可用）",
                verifier.verify(signature)
            )
        } finally {
            Arrays.fill(signingKey.keyBytes, 0.toByte())
        }
    }

    /**
     * 机器可读清单：供外部对拍脚本逐字段核对。
     * **不含**私钥明文与 PRF 秘密——仅含公开材料（RP / 用户名 / 凭据 ID / 公钥 / 算法）。
     */
    private fun buildManifest(
        es256: PasskeyData,
        ed25519: PasskeyData,
        rs256: PasskeyData
    ): String = buildString {
        appendLine("{")
        appendLine("  \"password\": \"$PROBE_PASSWORD\",")
        appendLine("  \"rpId\": \"$PROBE_RP_ID\",")
        appendLine("  \"entries\": [")
        appendLine(manifestEntry(ES256_ENTRY_TITLE, es256, expectPrf = true) + ",")
        appendLine(manifestEntry(ED25519_ENTRY_TITLE, ed25519, expectPrf = false) + ",")
        appendLine(manifestEntry(RS256_ENTRY_TITLE, rs256, expectPrf = false))
        appendLine("  ]")
        appendLine("}")
    }

    private fun manifestEntry(title: String, passkey: PasskeyData, expectPrf: Boolean): String = buildString {
        append("    {")
        append("\"title\": \"$title\", ")
        append("\"userName\": \"${passkey.userName}\", ")
        append("\"algorithmId\": ${passkey.algorithmId}, ")
        append("\"credentialId\": \"${passkey.credentialId}\", ")
        append("\"userHandle\": \"${passkey.userHandle}\", ")
        append("\"publicKeyBase64\": \"${passkey.publicKeyBase64}\", ")
        append("\"privateKeyPemDerSha256\": \"${passkey.usePrivateKeyBytes { pemSha256Hex(it) }}\", ")
        append("\"prfPresent\": $expectPrf")
        append("}")
    }

    /**
     * PEM **内层 DER** 的 SHA-256（只留摘要，不留明文——清单要能安全落盘 / 贴进记录文档）。
     *
     * 口径刻意取「装甲剥离后的 DER」而非「PEM 文本字节」：外部脚本用 `pykeepass` 读出 PEM 后
     * 需自行剥装甲再哈希，若按文本字节比对就会被行尾 / 空行规范化的差异误判为篡改。
     */
    private fun pemSha256Hex(pemBytes: ByteArray): String {
        val der = requireNotNull(PasskeyKeyText.pemToDer(pemBytes)) { "私钥 PEM 必须能还原为 DER" }
        try {
            return HashUtil.sha256(der).joinToString("") { "%02x".format(it) }
        } finally {
            der.fill(0)
        }
    }

    private fun buildNote(outFile: File, byteCount: Int, sha256Hex: String): String = buildString {
        appendLine("# 通行密钥对拍探针产物说明（由 PasskeyInteropProbeTest 生成，可随时重跑）")
        appendLine()
        appendLine("> `ISSUE-P2-210` 的**产物半边**；读取半边由 `PasskeyInteropExternalFixtureTest` +")
        appendLine("> `tools/passkey-interop/` 承担。证据纪律见 `AGENTS.md` 规则 8。")
        appendLine()
        appendLine("- 产物：`${outFile.absolutePath}`")
        appendLine("- 字节数：$byteCount")
        appendLine("- SHA-256：`$sha256Hex`")
        appendLine("- 口令：`$PROBE_PASSWORD`")
        appendLine("- KDF：AES-KDF $PROBE_AES_ROUNDS 轮（刻意避开 Argon2 变体差异）")
        appendLine("- 条目：`$ES256_ENTRY_TITLE`（ES256，含 PRF）、`$ED25519_ENTRY_TITLE`（Ed25519）、`$RS256_ENTRY_TITLE`（RS256）")
        appendLine("- 机器可读清单：`$PROBE_MANIFEST_NAME`（公开材料 + PEM 摘要，无秘密明文）")
        appendLine()
        appendLine("## 外用验证（官方实现端到端对拍）")
        appendLine()
        appendLine("```bash")
        appendLine("python tools/passkey-interop/verify_interop.py")
        appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli show -q --all -s '${outFile.absolutePath}' '$ES256_ENTRY_TITLE'")
        appendLine(
            "python -c \"from pykeepass import PyKeePass; " +
                "kp=PyKeePass(r'${outFile.absolutePath}', password='$PROBE_PASSWORD'); " +
                "e=kp.find_entries(title='$ES256_ENTRY_TITLE', first=True); " +
                "print(e.get_custom_property('$KPEX_PEM_KEY'))\""
        )
        appendLine("```")
        appendLine()
        appendLine("期望：两条命令均成功，且 `KPEX_PASSKEY_*` 键名、保护位与 PKCS#8 PEM 内容可被")
        appendLine("独立实现读出并解析（详见 `verify_interop.py` 的逐字段判据）。")
    }

    private companion object {
        const val PROBE_PASSWORD = "passkey-interop-probe-2026"
        const val PROBE_AES_ROUNDS = 6_000L
        const val PROBE_DIR_NAME = "build/interop-probe"
        const val PROBE_FILE_NAME = "keepasskey-passkey-probe.kdbx"
        const val PROBE_NOTE_NAME = "PASSKEY_PROBE.md"
        const val PROBE_MANIFEST_NAME = "keepasskey-passkey-probe.expected.json"

        const val PROBE_RP_ID = "passkey-interop.example"
        const val PROBE_USER_HANDLE = "aW50ZXJvcC11c2VyLWhhbmRsZQ"
        const val PROBE_ENTRY_PASSWORD = "Probe-Entry-P@ssw0rd"
        const val ES256_ENTRY_TITLE = "Passkey ES256 (passkey-interop.example)"
        const val ED25519_ENTRY_TITLE = "Passkey Ed25519 (passkey-interop.example)"
        const val RS256_ENTRY_TITLE = "Passkey RS256 (passkey-interop.example)"
        const val ES256_USER_NAME = "es256-user@passkey-interop.example"
        const val ED25519_USER_NAME = "ed25519-user@passkey-interop.example"
        const val RS256_USER_NAME = "rs256-user@passkey-interop.example"

        const val KPEX_PEM_KEY = "KPEX_PASSKEY_PRIVATE_KEY_PEM"

        /** PKCS#8 PEM 首行（KeePassXC / KeePassDX 口径的私钥承载形态） */
        const val PKCS8_PEM_HEADER = "-----BEGIN PRIVATE KEY-----"

        const val PRF_SECRET_BYTES = 32
        const val ES256_SCALAR_BYTES = 32
        const val ES256_PUBLIC_KEY_BYTES = 65
        const val ED25519_PUBLIC_KEY_BYTES = 32

        /** JCE 算法名（避免字面量散落） */
        const val JCA_EC = "EC"
        const val JCA_SECP256R1 = "secp256r1"
        const val JCA_SHA256_ECDSA = "SHA256withECDSA"
        const val JCA_ED25519 = "Ed25519"
        const val JCA_RSA = "RSA"
        const val JCA_SHA256_RSA = "SHA256withRSA"

        /**
         * RFC 8410 §4 的 Ed25519 `SubjectPublicKeyInfo` 固定前缀
         * （`SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING(32) }`）。
         *
         * 用于把 32 字节原始公钥补齐成 JCE 可解析的 SPKI 形态；补齐后由 JDK 承担解析，
         * 探针自身不做任何曲线数学。
         */
        val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
        )
    }
}
