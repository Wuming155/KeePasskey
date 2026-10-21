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
 * **多把凭据**取第一把 + 计数点名（`PD-49` 裁决三）；扩展处置细则见 [PasskeyCxfExtensions.read]。
 * KeePassXC 形的 `url` 成员被**忽略**（口径 6：标题与归属按实际 rpId 呈现，不采信导出方的 URL 文本）。
 *
 * **擦除义务**：本读取器只清零自己的中间量；[ImportedPasskey] 返回的秘密三件套由调用链
 * （第 3 片落库接线）按 `saveEntry(totpSecretChars = …)` 同族契约负责，**含用户取消路径**。
 * 具体分工（`ISSUE-P3-647`，契约 §4 的 R2 / R4）：进入解析链的全部密码学材料——
 * 扫描器 `ByteSink` 的**每个容量代次**、AST 全部字符串副本（含未选中的多凭据、未知成员与
 * 失败分支）、`key` 解码出的 DER、两种形态的 PEM 字符工作数组——都只在本类可见的确定性钩子上
 * 处理：`read` 的 `finally` 递归清零 AST，`extract*` 的 `finally` 按「转移 / 清零」二选一
 * 处理 PEM（只有 `Take.Good` 才把同一实例转移给 `ImportedPasskey`，草案生命周期由
 * `PasskeyImportDraftHost` 的确认 / 取消 / 销毁钩子收口）。
 */
object PasskeyCxfReader {

    /**
     * 载荷字节硬上限（口径 9）。依据＝实测单张 QR 的物理上限：v40-L 可容 2953 B（M 2331 / Q 1663 /
     * H 1273），故 4096 B **大于任何单张二维码的解出上限**、又给手写扫描器一个常量界；超界即拒。
     */
    const val MAX_IMPORT_PAYLOAD_BYTES: Int = 4096

    /** 解析入口。[payload] 为 UTF-8 载荷字节，所有权归调用方（本函数不改动、不清零它）。 */
    fun read(payload: ByteArray): PasskeyCxfOutcome = read(payload, null)

    /**
     * 同上，并把扫描工作缓冲、AST 字符串副本与私钥 DER 工作数组在**分配时**交给 [probe]
     * 保留引用，供同模块契约测试直接断言清零（`ISSUE-P3-647`）。
     *
     * 生产路径传 null；[probe] 由调用方持有，**只许保存引用**，不得回写或清零。
     */
    internal fun read(payload: ByteArray, probe: PasskeyCxfWorkProbe?): PasskeyCxfOutcome {
        if (payload.size > MAX_IMPORT_PAYLOAD_BYTES) {
            return PasskeyCxfOutcome.Rejected(PasskeyCxfReject.PayloadTooLarge)
        }
        val root = ByteJsonScanner.scan(payload, probe)
            ?: return PasskeyCxfOutcome.Rejected(PasskeyCxfReject.MalformedJson)
        return try {
            when (val located = locate(root)) {
                is Located.Bad -> PasskeyCxfOutcome.Rejected(located.reason)
                is Located.Good -> extract(located, probe)
            }
        } finally {
            // 未选中的凭据、未知成员、失败分支的字符串副本一并递归清零（PD-49 裁决三：多把也只导第一把）
            root.wipe()
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

    private fun extract(located: Located.Good, probe: PasskeyCxfWorkProbe?): PasskeyCxfOutcome {
        // PD-49 裁决三：多把凭据导第一把，其余由确认对话框点名（不建逐把选择列表）
        val obj = located.candidates.first()
        val taken = if (located.shape == PasskeyCxfSourceShape.KeePassXCPasskeyFile) {
            extractKeePassXCPasskey(obj, probe)
        } else {
            extractCxfPasskey(obj, probe)
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

    private fun extractCxfPasskey(obj: ByteJson.Obj, probe: PasskeyCxfWorkProbe?): Take {
        val rpId = obj.textValue(FIELD_RP_ID) ?: return Take.Bad(PasskeyCxfReject.MissingCeremonyField)
        val credentialId = obj.canonicalB64Field(FIELD_CREDENTIAL_ID)
            ?: return obj.fieldFailure(FIELD_CREDENTIAL_ID)
        val userHandle = obj.canonicalB64Field(FIELD_USER_HANDLE)
            ?: return obj.fieldFailure(FIELD_USER_HANDLE)
        val keyNode = obj.strNode(FIELD_KEY) ?: return Take.Bad(PasskeyCxfReject.MissingCeremonyField)
        // 所有权：der 与 pem 都是本函数独占的中间量（`Base64Codec.decode` / `derToPemChars` 都返回新数组）。
        // 只有 `Take.Good` 交出 pem 才把责任转移给 ImportedPasskey（`transferred`）——其余一切出口
        // （Base64 非法、OID 不识别、PEM 编码或嗅探抛错、finish 自身抛错）都由 finally 清零。
        // keyNode 属 AST，这里提前擦以缩短驻留窗口（`read` 的 `root.wipe()` 仍兜底）。
        var pem: CharArray? = null
        var transferred = false
        try {
            val der = Base64Codec.decode(keyNode.bytes)?.also { probe?.derBytes?.add(it) }
            if (der == null || der.isEmpty()) return Take.Bad(PasskeyCxfReject.InvalidBase64Url)
            try {
                val algorithm = PasskeyKeyText.sniffAlgorithmId(der)
                    ?: return Take.Bad(PasskeyCxfReject.UnsupportedKeyAlgorithm)
                val chars = PasskeyKeyText.derToPemChars(der) // PEM 恒 ASCII，驻留形态与生成侧一致
                probe?.pemChars?.add(chars)
                pem = chars
                val taken = finish(obj, rpId, credentialId, userHandle, chars, algorithm)
                transferred = taken is Take.Good
                return taken
            } finally {
                der.fill(0)
            }
        } finally {
            if (!transferred) pem?.fill('0')
            keyNode.wipe()
        }
    }

    private fun extractKeePassXCPasskey(obj: ByteJson.Obj, probe: PasskeyCxfWorkProbe?): Take {
        val rpId = obj.textValue(KPXC_FIELD_RELYING_PARTY)
            ?: return Take.Bad(PasskeyCxfReject.MissingCeremonyField)
        val credentialId = obj.canonicalB64Field(FIELD_CREDENTIAL_ID)
            ?: return obj.fieldFailure(FIELD_CREDENTIAL_ID)
        val userHandle = obj.canonicalB64Field(FIELD_USER_HANDLE)
            ?: return obj.fieldFailure(FIELD_USER_HANDLE)
        val pemNode = obj.strNode(KPXC_FIELD_PRIVATE_KEY)
            ?: return Take.Bad(PasskeyCxfReject.MissingCeremonyField)
        // 同上：字符副本只在 `Take.Good` 时转移，拒收 / 抛错 / finish 失败都清零
        var pem: CharArray? = null
        var transferred = false
        try {
            val algorithm = PasskeyKeyText.sniffAlgorithmId(pemNode.bytes)
                ?: return Take.Bad(PasskeyCxfReject.UnsupportedKeyAlgorithm)
            val chars = asciiChars(pemNode.bytes) // PEM 恒 ASCII：逐字节转 CharArray，不经过 String
                ?: return Take.Bad(PasskeyCxfReject.MissingCeremonyField)
            probe?.pemChars?.add(chars)
            pem = chars
            val taken = finish(obj, rpId, credentialId, userHandle, chars, algorithm)
            transferred = taken is Take.Good
            return taken
        } finally {
            if (!transferred) pem?.fill('0')
            pemNode.wipe()
        }
    }

    /**
     * 两种形态共用的收尾：展示字段补齐 + `fido2Extensions` 读取。
     *
     * 所有权：`pem` 由调用方（两个 `extract*`）持有，**只有**本函数返回 [Take.Good] 时才转移给
     * [ImportedPasskey]；本函数自身抛错时不得擦 `pem`（由调用方的 `finally` 按 `transferred` 判定）。
     * `PasskeyCxfExtensions.read` 产出的两枚 PRF 种子本函数独占，构造草案失败时在此收口清零，不留悬挂副本。
     */
    private fun finish(
        obj: ByteJson.Obj,
        rpId: String,
        credentialId: String,
        userHandle: String,
        pem: CharArray,
        algorithm: Int
    ): Take {
        val hmac = PasskeyCxfExtensions.read(obj)
        return try {
            val (userName, displayName, missing) = displayFieldsOf(obj, rpId)
            Take.Good(
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
        } catch (failure: Throwable) {
            hmac.prfWithUv?.fill(0)
            hmac.prfWithoutUv?.fill(0)
            throw failure
        }
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
    private const val FIELD_VERSION = "version"
    private const val FIELD_MAJOR = "major"
    private const val FIELD_ACCOUNTS = "accounts"
    private const val FIELD_ITEMS = "items"
    private const val FIELD_CREDENTIALS = "credentials"
    private const val TYPE_PASSKEY = "passkey"

    /** KeePassXC `.passkey` 独有的键名（其余 `credentialId` / `userHandle` / `username` 与 CXF 同名）。 */
    private const val KPXC_FIELD_PRIVATE_KEY = "privateKey"
    private const val KPXC_FIELD_RELYING_PARTY = "relyingParty"

    private const val LF = 0x0A
    private const val CR = 0x0D
    private const val TAB = 0x09
}
