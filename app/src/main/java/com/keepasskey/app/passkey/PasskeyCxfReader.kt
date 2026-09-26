package com.keepasskey.app.passkey

import com.keepasskey.core.model.PasskeyKeyText

/**
 * FIDO **CXF v1.0** 通行密钥载荷读取器（`ISSUE-P3-337` 口径 2 / 2′ / 2″ / 3，AC②③）。
 * 结果类型、错误码与各形态的语义见 [PasskeyCxfOutcome] 所在文件（两文件同一单元，行数分档闸门所迫而分）。
 *
 * 形态分派见 [locate]：根数组＝`$Credential` 数组；根对象含 `version` / `accounts`＝§3.1 文档
 * （凭据路径 **`accounts[].items[].credentials[]`**）；含 `type`＝裸 `Passkey` 对象；
 * 含 `privateKey` / `relyingParty` 且无 `type`＝KeePassXC `.passkey` 单对象（`PD-08` 兼容目标）。
 * ⚠️ **`collections[].items[]` 不是取凭据的路径**——§3.2.2 `Collection.items` 是
 * `LinkedItem{ item: b64url, ?account: b64url }`，仅引用、不含凭据（`PD-08` 原表述错在此处，已勘误）。
 *
 * 全程走 [ByteJsonScanner] 的**字节通道**：载荷的 `key` 是 PKCS#8 私钥文本，任何 `String` 中间量
 * 都违反 `AGENTS.md` §3 铁律，而现成的 [SimpleJson] 入参正是 `String`、`org.json` 在宿主单测里是
 * 未实现桩（同一理由见 [CallingOriginResolver]）⇒ 私钥只以 `ByteArray`（DER）与 `CharArray`（PEM）
 * 存在，**永不进 `String`**（AC③）；本读取器消费掉的秘密节点（`key` / `credWithUV` /
 * `credWithoutUV` / `hmacSecret.secret` / `privateKey`）取用后即就地清零。
 *
 * **判据分两层**（`PD-48` 裁决一）：① 仪式字段 `type` / `credentialId` / `rpId` / `userHandle` / `key`
 * 缺失、**空白（等同缺失）**、Base64 非法、解出零字节、或 PKCS#8 结构嗅不到已知 OID ⇒ 一律拒收，
 * 只回 [PasskeyCxfReject] 静态码（**不回显载荷**）；② 仅 `username` / `userDisplayName` 缺失 ⇒
 * 容错补齐（用户名以 rpId 占位、显示名空串）并登记 [PasskeyCxfDisplayField]，补齐值**只进展示字段**、
 * 绝不回填 `userHandle`。算法一律由 [PasskeyKeyText.sniffAlgorithmId] 从 PKCS#8 的 OID 判定——
 * 规范**没有** `alg` 成员，「`key.alg` 与嗅探交叉核对」对本载荷恒不触发，属虚构。
 * 白名单外的成员一律**忽略而非拒收**（§3.1.1「MUST ignore unknown fields or enumeration values」）；
 * **重复键取首个**（[ByteJson.Obj.member]，「后写覆盖」会让载荷尾部悄悄改写仪式字段）；
 * **多把凭据**取第一把 + 计数点名（`PD-49` 裁决三）；扩展处置细则见 [readHmac]。
 * KeePassXC 形的 `url` 成员被**忽略**（口径 6：标题与归属按实际 rpId 呈现，不采信导出方的 URL 文本）。
 *
 * **擦除义务**：本读取器只清零自己的中间量；[ImportedPasskey] 返回的秘密三件套由调用链
 * （第 3 片落库接线）按 `saveEntry(totpSecretChars = …)` 同族契约负责，**含用户取消路径**。
 */
object PasskeyCxfReader {

    /**
     * 载荷字节硬上限（口径 9）。依据＝实测单张 QR 的物理上限：v40-L 可容 2953 B（M 2331 / Q 1663 /
     * H 1273），故 4096 B **大于任何单张二维码的解出上限**、又给手写扫描器一个常量界；超界即拒。
     */
    const val MAX_IMPORT_PAYLOAD_BYTES: Int = 4096

    /** 解析入口。[payload] 为 UTF-8 载荷字节，所有权归调用方（本函数不改动、不清零它）。 */
    fun read(payload: ByteArray): PasskeyCxfOutcome {
        if (payload.size > MAX_IMPORT_PAYLOAD_BYTES) {
            return PasskeyCxfOutcome.Rejected(PasskeyCxfReject.PayloadTooLarge)
        }
        val root = ByteJsonScanner.scan(payload)
            ?: return PasskeyCxfOutcome.Rejected(PasskeyCxfReject.MalformedJson)
        return when (val located = locate(root)) {
            is Located.Bad -> PasskeyCxfOutcome.Rejected(located.reason)
            is Located.Good -> extract(located)
        }
    }

    // ------------------------------------------------------------------
    // 形态定位
    // ------------------------------------------------------------------

    private sealed class Located {
        class Good(
            val shape: PasskeyCxfSourceShape,
            val candidates: List<ByteJson.Obj>,
            val skippedCredentialCount: Int
        ) : Located()

        class Bad(val reason: PasskeyCxfReject) : Located()
    }

    private fun locate(root: ByteJson): Located {
        if (root is ByteJson.Arr) {
            val (passkeys, skipped) = partitionCredentials(root.items)
            if (passkeys.isEmpty()) return Located.Bad(PasskeyCxfReject.NoPasskeyCredential)
            return Located.Good(PasskeyCxfSourceShape.CredentialArray, passkeys, skipped)
        }
        if (root !is ByteJson.Obj) return Located.Bad(PasskeyCxfReject.MalformedJson)
        return when {
            root.has(FIELD_VERSION) || root.has(FIELD_ACCOUNTS) -> locateDocument(root)

            root.has(FIELD_TYPE) ->
                // 裸单对象形态：type 非 passkey 即拒（口径 2″②——文档 / 数组形态里那是「跳过」，不是「拒」）
                if (isPasskeyType(root)) {
                    Located.Good(PasskeyCxfSourceShape.BarePasskeyObject, listOf(root), 0)
                } else {
                    Located.Bad(PasskeyCxfReject.NotPasskeyCredential)
                }

            root.has(KPXC_FIELD_PRIVATE_KEY) || root.has(KPXC_FIELD_RELYING_PARTY) ->
                Located.Good(PasskeyCxfSourceShape.KeePassXCPasskeyFile, listOf(root), 0)

            else -> Located.Bad(PasskeyCxfReject.NotPasskeyCredential)
        }
    }

    /** §3.1 文档形态：版本门 + `accounts[].items[].credentials[]` 三层展开。 */
    private fun locateDocument(header: ByteJson.Obj): Located {
        val version = header.member(FIELD_VERSION) as? ByteJson.Obj
        if (version == null && header.has(FIELD_VERSION)) {
            return Located.Bad(PasskeyCxfReject.NestingMismatch) // `version` 存在但不是对象
        }
        val major = version?.member(FIELD_MAJOR) as? ByteJson.Num
        if (major != null && major.longValue != 1L) {
            return Located.Bad(PasskeyCxfReject.DocumentVersionUnsupported)
        }
        val accounts = header.member(FIELD_ACCOUNTS) as? ByteJson.Arr
            ?: return Located.Bad(PasskeyCxfReject.NestingMismatch)
        val passkeys = ArrayList<ByteJson.Obj>(4)
        var skipped = 0
        for (account in accounts.items) {
            val items = (account as? ByteJson.Obj)?.member(FIELD_ITEMS) as? ByteJson.Arr
                ?: continue // 该 account 无 items：跳过，不算畸形（§3.1.1 的宽容面）
            for (item in items.items) {
                val credentials = (item as? ByteJson.Obj)?.member(FIELD_CREDENTIALS) as? ByteJson.Arr
                    ?: continue
                val (found, counted) = partitionCredentials(credentials.items)
                passkeys += found
                skipped += counted
            }
        }
        if (passkeys.isEmpty()) return Located.Bad(PasskeyCxfReject.NoPasskeyCredential)
        return Located.Good(PasskeyCxfSourceShape.Document, passkeys, skipped)
    }

    /** 分成「passkey 候选」与「其余（跳过并计数）」两类（核实 10③：混装是常态分支，不是畸形载荷）。 */
    private fun partitionCredentials(items: List<ByteJson>): Pair<List<ByteJson.Obj>, Int> {
        val passkeys = ArrayList<ByteJson.Obj>(items.size.coerceAtMost(8))
        var skipped = 0
        for (item in items) {
            val obj = item as? ByteJson.Obj
            if (obj != null && isPasskeyType(obj)) passkeys += obj else skipped++
        }
        return passkeys to skipped
    }

    private fun isPasskeyType(obj: ByteJson.Obj): Boolean =
        (obj.member(FIELD_TYPE) as? ByteJson.Str)?.asUtf8String() == TYPE_PASSKEY

    // ------------------------------------------------------------------
    // 字段提取
    // ------------------------------------------------------------------

    private sealed class Take {
        class Good(
            val credential: ImportedPasskey,
            val dropped: List<PasskeyCxfDropped>,
            val missingDisplayFields: List<PasskeyCxfDisplayField>
        ) : Take()

        class Bad(val reason: PasskeyCxfReject) : Take()
    }

    private fun extract(located: Located.Good): PasskeyCxfOutcome {
        // PD-49 裁决三：多把凭据导第一把，其余由确认对话框点名（不建逐把选择列表）
        val obj = located.candidates.first()
        val taken = if (located.shape == PasskeyCxfSourceShape.KeePassXCPasskeyFile) {
            extractKeePassXCPasskey(obj)
        } else {
            extractCxfPasskey(obj)
        }
        return when (taken) {
            is Take.Bad -> PasskeyCxfOutcome.Rejected(taken.reason)
            is Take.Good -> PasskeyCxfOutcome.Parsed(
                credential = taken.credential,
                notes = PasskeyCxfNotes(
                    shape = located.shape,
                    dropped = taken.dropped,
                    missingDisplayFields = taken.missingDisplayFields,
                    additionalPasskeyCount = located.candidates.size - 1,
                    skippedCredentialCount = located.skippedCredentialCount
                )
            )
        }
    }

    private fun extractCxfPasskey(obj: ByteJson.Obj): Take {
        val rpId = obj.textValue(FIELD_RP_ID) ?: return Take.Bad(PasskeyCxfReject.MissingCeremonyField)
        val credentialId = obj.canonicalB64Field(FIELD_CREDENTIAL_ID)
            ?: return obj.fieldFailure(FIELD_CREDENTIAL_ID)
        val userHandle = obj.canonicalB64Field(FIELD_USER_HANDLE)
            ?: return obj.fieldFailure(FIELD_USER_HANDLE)
        val keyNode = obj.strNode(FIELD_KEY) ?: return Take.Bad(PasskeyCxfReject.MissingCeremonyField)
        val der = Base64Codec.decode(keyNode.bytes)
        if (der == null || der.isEmpty()) {
            keyNode.wipe()
            return Take.Bad(PasskeyCxfReject.InvalidBase64Url)
        }
        val algorithm = PasskeyKeyText.sniffAlgorithmId(der)
        val pem = if (algorithm == null) {
            der.fill(0)
            keyNode.wipe()
            return Take.Bad(PasskeyCxfReject.UnsupportedKeyAlgorithm)
        } else {
            try {
                PasskeyKeyText.derToPemChars(der) // PEM 恒 ASCII，驻留形态与生成侧一致
            } finally {
                der.fill(0)
                keyNode.wipe()
            }
        }
        return finish(obj, rpId, credentialId, userHandle, pem, algorithm)
    }

    private fun extractKeePassXCPasskey(obj: ByteJson.Obj): Take {
        val rpId = obj.textValue(KPXC_FIELD_RELYING_PARTY)
            ?: return Take.Bad(PasskeyCxfReject.MissingCeremonyField)
        val credentialId = obj.canonicalB64Field(FIELD_CREDENTIAL_ID)
            ?: return obj.fieldFailure(FIELD_CREDENTIAL_ID)
        val userHandle = obj.canonicalB64Field(FIELD_USER_HANDLE)
            ?: return obj.fieldFailure(FIELD_USER_HANDLE)
        val pemNode = obj.strNode(KPXC_FIELD_PRIVATE_KEY)
            ?: return Take.Bad(PasskeyCxfReject.MissingCeremonyField)
        val algorithm = PasskeyKeyText.sniffAlgorithmId(pemNode.bytes)
        val pem = if (algorithm == null) {
            pemNode.wipe()
            return Take.Bad(PasskeyCxfReject.UnsupportedKeyAlgorithm)
        } else {
            val chars = asciiChars(pemNode.bytes) // PEM 恒 ASCII：逐字节转 CharArray，不经过 String
            pemNode.wipe()
            if (chars == null) return Take.Bad(PasskeyCxfReject.MissingCeremonyField) else chars
        }
        return finish(obj, rpId, credentialId, userHandle, pem, algorithm)
    }

    /** 两种形态共用的收尾：展示字段补齐 + `fido2Extensions` 读取。 */
    private fun finish(
        obj: ByteJson.Obj,
        rpId: String,
        credentialId: String,
        userHandle: String,
        pem: CharArray,
        algorithm: Int
    ): Take {
        val hmac = readHmac(obj)
        val (userName, displayName, missing) = displayFieldsOf(obj, rpId)
        return Take.Good(
            credential = ImportedPasskey(
                relyingPartyId = rpId,
                credentialId = credentialId,
                userHandle = userHandle,
                userName = userName,
                userDisplayName = displayName,
                privateKeyPemChars = pem,
                algorithmId = algorithm,
                prfWithUv = hmac.prfWithUv,
                prfWithoutUv = hmac.prfWithoutUv
            ),
            dropped = hmac.dropped,
            missingDisplayFields = missing
        )
    }

    /** 展示字段容错补齐（`PD-48` 裁决一 (b)）：只进展示字段，不参与任何仪式计算。 */
    private fun displayFieldsOf(obj: ByteJson.Obj, rpId: String): DisplayFields {
        val missing = ArrayList<PasskeyCxfDisplayField>(2)
        val userName = obj.textValue(FIELD_USER_NAME) ?: rpId.also {
            missing += PasskeyCxfDisplayField.UserName
        }
        val displayName = obj.textValue(FIELD_USER_DISPLAY_NAME) ?: "".also {
            missing += PasskeyCxfDisplayField.UserDisplayName
        }
        return DisplayFields(userName, displayName, missing)
    }

    private data class DisplayFields(
        val userName: String,
        val userDisplayName: String,
        val missing: List<PasskeyCxfDisplayField>
    )

    // ------------------------------------------------------------------
    // fido2Extensions（口径 2′）
    // ------------------------------------------------------------------

    private class HmacRead(
        val prfWithUv: ByteArray?,
        val prfWithoutUv: ByteArray?,
        val dropped: List<PasskeyCxfDropped>
    )

    /**
     * 读 `fido2Extensions`，只取本库能承载或有账目价值的面（逐条对齐条目口径 2′）：
     * - **两种拼写都认**：§3.3.12.2 `hmacCredentials{algorithm, credWithUV, credWithoutUV}` 与
     *   **规范附录 A 示例**的遗留形 `hmacSecret{algorithm, secret}`（键名 / 成员数 / 算法值三项与
     *   §3.3.12.2~.4 全部冲突）——只按 CDDL 拼写取值会让**规范自己的示例**解出「无 PRF」；
     *   规范形存在时**优先**，别名仅在其缺席时消费，其 `secret` 视作 withUV 那一枚；
     * - **算法同义**：`hmac-sha256`（§3.3.12.4 唯一枚举）与 `HS256`（示例值）都指 HMAC-SHA-256；
     *   其余值按 §3.3.12.3「unknown algorithm **SHOULD ignore this entry**」⇒ **只丢该扩展项、
     *   保留凭据**（「ignore this entry」≠ ignore this credential），丢弃项进确认清单；
     * - **长度不判、不裁、不拒收**（规范用词「SHOULD be 32 bytes」，`PD-49` 裁决二），非 32 字节
     *   原样保真存、**不进**丢弃清单；别名形天然只有一枚 ⇒ `prfWithoutUv` 留空且**不**记缺失
     *   （禁止复制出第二枚假种子），规范形缺一枚才记 [PasskeyCxfDropped.HmacIncomplete]；
     * - `credBlob` / `largeBlob` / `payments(true)` 本库不承载 ⇒ 逐条进丢弃清单（AC⑪②）；
     *   `payments(false)` 无内容可丢、不点名；其余未知成员按 §3.1.1 静默忽略。
     */
    private fun readHmac(obj: ByteJson.Obj): HmacRead {
        val ext = obj.member(FIELD_FIDO2_EXTENSIONS) as? ByteJson.Obj
            ?: return HmacRead(null, null, emptyList())
        val canonical = ext.member(EXT_FIELD_HMAC_CREDENTIALS) as? ByteJson.Obj
        val alias = ext.member(EXT_FIELD_HMAC_SECRET) as? ByteJson.Obj
        val holder = canonical ?: alias
        val dropped = ArrayList<PasskeyCxfDropped>(4)
        var withUv: ByteArray? = null
        var withoutUv: ByteArray? = null
        if (holder != null) {
            val withNode = holder.member(EXT_FIELD_CRED_WITH_UV) as? ByteJson.Str
                ?: if (canonical == null) holder.member(EXT_FIELD_SECRET) as? ByteJson.Str else null
            val withoutNode = holder.member(EXT_FIELD_CRED_WITHOUT_UV) as? ByteJson.Str
            val algorithm = (holder.member(EXT_FIELD_ALGORITHM) as? ByteJson.Str)?.asUtf8String()
            if (algorithm != null && algorithm.lowercase() in KNOWN_HMAC_ALGORITHMS) {
                withUv = withNode?.takeUnless { it.isBlankUtf8() }?.let { decodeAndWipe(it) }
                withoutUv = withoutNode?.takeUnless { it.isBlankUtf8() }?.let { decodeAndWipe(it) }
                if (withUv == null || withUv.isEmpty()) {
                    withUv = null
                    dropped += PasskeyCxfDropped.HmacIncomplete
                } else if (canonical != null && (withoutUv == null || withoutUv.isEmpty())) {
                    withoutUv = null
                    dropped += PasskeyCxfDropped.HmacIncomplete
                }
            } else {
                dropped += PasskeyCxfDropped.HmacUnknownAlgorithm
                withNode?.wipe()
                withoutNode?.wipe()
            }
        }
        if (ext.has(EXT_FIELD_CRED_BLOB)) dropped += PasskeyCxfDropped.CredBlob
        if (ext.has(EXT_FIELD_LARGE_BLOB)) dropped += PasskeyCxfDropped.LargeBlob
        if ((ext.member(EXT_FIELD_PAYMENTS) as? ByteJson.Bool)?.value == true) {
            dropped += PasskeyCxfDropped.Payments
        }
        return HmacRead(withUv, withoutUv, dropped)
    }

    private fun decodeAndWipe(node: ByteJson.Str): ByteArray? =
        Base64Codec.decode(node.bytes)?.takeIf { it.isNotEmpty() }?.also { node.wipe() }

    // ------------------------------------------------------------------
    // 值读取小工具
    // ------------------------------------------------------------------

    private fun ByteJson.Obj.strNode(key: String): ByteJson.Str? = member(key) as? ByteJson.Str

    /** 非敏感文本：存在、是字符串、且非全空白才返回；否则 null（＝缺失，交判据层拒绝或补齐）。 */
    private fun ByteJson.Obj.textValue(key: String): String? =
        strNode(key)?.asUtf8String()?.takeIf { it.isNotBlank() }

    /** Base64 字段 → **规范形 Base64URL（无填充）**文本；解码失败或零长度返回 null。 */
    private fun ByteJson.Obj.canonicalB64Field(key: String): String? {
        val node = strNode(key) ?: return null
        val decoded = Base64Codec.decode(node.bytes)
        node.wipe()
        return if (decoded == null || decoded.isEmpty()) null
        else Base64Codec.encodeCanonical(decoded).also { decoded.fill(0) }
    }

    /** 区分「键缺失」与「值非法」两种静态错误码（都不回显载荷）。 */
    private fun ByteJson.Obj.fieldFailure(key: String): Take =
        if (has(key)) Take.Bad(PasskeyCxfReject.InvalidBase64Url)
        else Take.Bad(PasskeyCxfReject.MissingCeremonyField)

    /**
     * ASCII 字节 → CharArray（PEM 专用）。PEM 正文只含 base64 字母表、`-`、空白与换行，
     * 出现 `>= 0x80` 或非法控制字符即判不合法（返回 null，**同时**清零输出缓冲）。
     */
    private fun asciiChars(bytes: ByteArray): CharArray? {
        val out = CharArray(bytes.size)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            val legal = b >= 0x20 || b == LF || b == CR || b == TAB
            if (!legal || b >= 0x80) {
                out.fill('0')
                return null
            }
            out[i] = b.toChar()
        }
        return out
    }

    private const val FIELD_TYPE = "type"
    private const val FIELD_CREDENTIAL_ID = "credentialId"
    private const val FIELD_RP_ID = "rpId"
    private const val FIELD_USER_NAME = "username"
    private const val FIELD_USER_DISPLAY_NAME = "userDisplayName"
    private const val FIELD_USER_HANDLE = "userHandle"
    private const val FIELD_KEY = "key"
    private const val FIELD_FIDO2_EXTENSIONS = "fido2Extensions"
    private const val FIELD_VERSION = "version"
    private const val FIELD_MAJOR = "major"
    private const val FIELD_ACCOUNTS = "accounts"
    private const val FIELD_ITEMS = "items"
    private const val FIELD_CREDENTIALS = "credentials"
    private const val TYPE_PASSKEY = "passkey"

    private const val EXT_FIELD_HMAC_CREDENTIALS = "hmacCredentials"
    private const val EXT_FIELD_HMAC_SECRET = "hmacSecret"
    private const val EXT_FIELD_ALGORITHM = "algorithm"
    private const val EXT_FIELD_CRED_WITH_UV = "credWithUV"
    private const val EXT_FIELD_CRED_WITHOUT_UV = "credWithoutUV"
    private const val EXT_FIELD_SECRET = "secret"
    private const val EXT_FIELD_CRED_BLOB = "credBlob"
    private const val EXT_FIELD_LARGE_BLOB = "largeBlob"
    private const val EXT_FIELD_PAYMENTS = "payments"

    /** KeePassXC `.passkey` 独有的键名（其余 `credentialId` / `userHandle` / `username` 与 CXF 同名）。 */
    private const val KPXC_FIELD_PRIVATE_KEY = "privateKey"
    private const val KPXC_FIELD_RELYING_PARTY = "relyingParty"

    /** §3.3.12.4 唯一枚举值与附录 A 示例值 `HS256` 视为同义：两者都指 HMAC-SHA-256。 */
    private val KNOWN_HMAC_ALGORITHMS = setOf("hmac-sha256", "hs256")

    private const val LF = 0x0A
    private const val CR = 0x0D
    private const val TAB = 0x09
}
