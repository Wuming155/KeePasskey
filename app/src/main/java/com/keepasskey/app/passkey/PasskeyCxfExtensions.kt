package com.keepasskey.app.passkey

/**
 * `fido2Extensions` 的读取与账目（`ISSUE-P3-337` 口径 2′；`ISSUE-P3-647` 为保持读取器在行数分档内
 * 按职责拆出）。语义判据全部留在本文件，[PasskeyCxfReader] 只负责定位与仪式字段。
 */
internal object PasskeyCxfExtensions {

    /** 一次扩展读取的产出：两枚 PRF 种子（O 类，责任见 [read]）与丢弃项账目。 */
    class Read(
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
     *
     * 所有权：返回的两枚种子是解码出的**新数组**，责任随之转移给调用方（`PasskeyCxfReader.finish`
     * 在构造草案失败时清零，成功则交给 `ImportedPasskey` 由用户确认 / 取消路径擦除）。
     * 被消费与被拒的 AST 节点在本函数内即擦，**无论**解码成败——它们都是扫描器分配的明文副本。
     */
    fun read(obj: ByteJson.Obj): Read {
        val ext = obj.member(FIELD_FIDO2_EXTENSIONS) as? ByteJson.Obj
            ?: return Read(null, null, emptyList())
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
        return Read(withUv, withoutUv, dropped)
    }

    /**
     * 解码并**总是**擦掉 AST 里的源节点（成功、空值、非法 Base64 三条路径同样擦）：
     * 节点是扫描器分配的副本，解码失败后其 Base64 文本仍驻留在 AST 里，没有理由留着。
     */
    private fun decodeAndWipe(node: ByteJson.Str): ByteArray? = try {
        Base64Codec.decode(node.bytes)?.takeIf { it.isNotEmpty() }
    } finally {
        node.wipe()
    }

    private const val FIELD_FIDO2_EXTENSIONS = "fido2Extensions"
    private const val EXT_FIELD_HMAC_CREDENTIALS = "hmacCredentials"
    private const val EXT_FIELD_HMAC_SECRET = "hmacSecret"
    private const val EXT_FIELD_ALGORITHM = "algorithm"
    private const val EXT_FIELD_CRED_WITH_UV = "credWithUV"
    private const val EXT_FIELD_CRED_WITHOUT_UV = "credWithoutUV"
    private const val EXT_FIELD_SECRET = "secret"
    private const val EXT_FIELD_CRED_BLOB = "credBlob"
    private const val EXT_FIELD_LARGE_BLOB = "largeBlob"
    private const val EXT_FIELD_PAYMENTS = "payments"

    /** §3.3.12.4 唯一枚举值与附录 A 示例值 `HS256` 视为同义：两者都指 HMAC-SHA-256。 */
    private val KNOWN_HMAC_ALGORITHMS = setOf("hmac-sha256", "hs256")
}
