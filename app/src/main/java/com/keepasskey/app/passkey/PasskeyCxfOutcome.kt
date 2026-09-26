package com.keepasskey.app.passkey

import java.util.Base64

/**
 * [PasskeyCxfReader.read] 的结果与随行账目（`ISSUE-P3-337` 口径 2 / 3，AC②③⑪）。
 *
 * 拆出独立文件只为满足 `AGENTS.md` §5 的行数分档闸门（`count_line_tiers`：`tier1 > 500` 恒 0），
 * 语义上与本节的读取器是同一单元——**判据、错误码与承载口径都写在这里**，读取器只留定位与提取。
 */
sealed class PasskeyCxfOutcome {

    /**
     * 解析成功。[credential] 含秘密材料（**调用方负责在所有路径上擦除**，含用户取消路径），
     * [notes] 是第 4 片确认对话框要逐条点名的账目。
     */
    class Parsed(val credential: ImportedPasskey, val notes: PasskeyCxfNotes) : PasskeyCxfOutcome()

    /** 拒绝入库：只有静态错误码，UI 侧按码取文案，**不回显载荷**（口径 3 末句）。 */
    class Rejected(val reason: PasskeyCxfReject) : PasskeyCxfOutcome()
}

/** 拒收原因（静态错误码；AC② 的每条拒绝路径都必须唯一落到这里）。 */
enum class PasskeyCxfReject {
    /** 超过 [PasskeyCxfReader.MAX_IMPORT_PAYLOAD_BYTES]：单张二维码放不下该载荷（口径 9）。 */
    PayloadTooLarge,

    /** 根节点不是对象 / 数组，或整体不是单个合法 JSON 值（含截断、裸词、串内裸控制字符、超深度）。 */
    MalformedJson,

    /** 形态判到了，但里面**没有** `type == "passkey"` 的凭据（混装文档是常态分支，需如实提示）。 */
    NoPasskeyCredential,

    /** 裸单对象形态的 `type` 不是 `passkey`（口径 2″②：文档 / 数组形态里那是「跳过」，不属拒收）。 */
    NotPasskeyCredential,

    /** §3.1 `Header.version.major` 非 1（本读取器只消费 v1；`minor` 按 §3.1.1 只增不改，放行）。 */
    DocumentVersionUnsupported,

    /** 文档骨架层级与 §3.1~§3.2 不符（如 `accounts` 不是数组、`version` 不是对象）。 */
    NestingMismatch,

    /** 仪式字段（`credentialId` / `rpId` / `userHandle` / `key`）缺失或空白。 */
    MissingCeremonyField,

    /** 仪式字段存在但 Base64 解码失败 / 解出零字节。 */
    InvalidBase64Url,

    /** 私钥结构里嗅不到已知 OID（ES256 / Ed25519 / RS256 之外），无法确定算法。 */
    UnsupportedKeyAlgorithm
}

/** 载荷形态（既用于分派定位，也进批次文档的取证读数）。 */
enum class PasskeyCxfSourceShape {
    /** 规范 §3.3.12 的裸 `Passkey` 字典。 */
    BarePasskeyObject,

    /** `$Credential` 数组（§3.2.10）。 */
    CredentialArray,

    /** 完整 CXF 文档：`Header.accounts[].items[].credentials[]`（§3.1~§3.2）。 */
    Document,

    /** KeePassXC `.passkey` 单对象（`PD-08` 第 1 项的兼容目标，词表与 CXF 不同）。 */
    KeePassXCPasskeyFile
}

/**
 * 本库不承载、被如实丢弃的扩展项（AC⑪②，禁止静默丢弃）。
 *
 * ⚠️ `credWithoutUV` 按 `PD-48` 裁决二**全量存入本仓扩展键** `Passkey.PrfNoUv`，
 * **不属**丢弃项 ⇒ 确认对话框里**不得**出现它「被丢弃」的字样。
 */
enum class PasskeyCxfDropped {
    CredBlob, LargeBlob, Payments,

    /** `algorithm` 值不在认得的集合内 ⇒ §3.3.12.3「ignore this **entry**」（凭据本身仍入库）。 */
    HmacUnknownAlgorithm,

    /** **规范形** `hmacCredentials` 的双值缺一（规范 §3.3.12.3 明写三者必填）⇒ 存其可得者并如实点名。 */
    HmacIncomplete
}

/** 来源未提供、由本仓补齐的展示字段（`PD-48` 裁决一的「如实标注」载体）。 */
enum class PasskeyCxfDisplayField {
    UserName, UserDisplayName
}

/** 确认对话框（第 4 片）消费的账目。 */
class PasskeyCxfNotes(
    val shape: PasskeyCxfSourceShape,
    val dropped: List<PasskeyCxfDropped>,
    val missingDisplayFields: List<PasskeyCxfDisplayField>,
    /** 文档 / 数组里除第一把之外还有几把 passkey 凭据未导入（`PD-49` 裁决三的点名项）。 */
    val additionalPasskeyCount: Int,
    /** 被跳过的非 passkey 凭据条数（规范附录 A 示例即混装 15 条）。 */
    val skippedCredentialCount: Int
)

/**
 * 导入所得的凭据材料（第 3 片按 `PD-48` / `PD-49` 落 `KPEX_PASSKEY_*`）。
 *
 * 承载口径：[credentialId] / [userHandle] 一律归一为**规范形 Base64URL（无填充）**文本——
 * KeePassXC `.passkey` 的成员值是标准 Base64（`Qt::toBase64`，含 `+ / =`），
 * 不归一则断言侧按 Base64URL 比对 `allowCredentials` 永不命中
 * （存储与比对口径见 `CredentialCandidateMatcher`，生成侧口径见 `PasskeyKeyGeneration`）。
 *
 * ⚠️ [privateKeyPemChars] / [prfWithUv] / [prfWithoutUv] 是秘密材料，
 * **擦除义务归调用方**（AGENTS.md §3 铁律；含用户取消路径，AC③ 有断言锁之）；
 * [algorithmId] 由 PKCS#8 结构嗅探得出，**不采信载荷自述**（规范没有 `alg` 成员）。
 */
class ImportedPasskey(
    val relyingPartyId: String,
    val credentialId: String,
    val userHandle: String,
    val userName: String,
    val userDisplayName: String,
    val privateKeyPemChars: CharArray,
    val algorithmId: Int,
    val prfWithUv: ByteArray?,
    val prfWithoutUv: ByteArray?
) {
    /**
     * 就地清零本对象承载的全部秘密材料（私钥 PEM 字符与两枚 PRF 种子）。
     *
     * 为什么由模型自己提供这个动作：调用链上有两条退出路径都要擦——「用户确认落库后」与
     * 「用户取消」——而取消路径**根本不经过**任何写库代码，最容易漏。把擦除收成一个方法，
     * AC③ 就能用一条断言同时锁住两支（`ISSUE-P3-337` 口径 4「私钥不跨页承载」的落地件）。
     * 幂等：重复调用无副作用。
     */
    fun wipeSecrets() {
        privateKeyPemChars.fill('0')
        prfWithUv?.fill(0)
        prfWithoutUv?.fill(0)
    }
}

/**
 * 待确认的导入草案（`ISSUE-P3-337` 口径 4：确认对话框**在当前作用域**持有，落库或取消即擦除）。
 *
 * ⚠️ 只能存在于 ViewModel / 编排器的内存状态里：**禁止**经路由参数、`SavedStateHandle`
 * 或任何框架缓存承载（`P2-105` 立过「不得经框架缓存敏感值」的规矩——草案里的私钥是明文驻留，
 * 框架一旦把它写进持久化 Bundle 就彻底失控）。
 */
class PasskeyImportDraft(
    val credential: ImportedPasskey,
    val notes: PasskeyCxfNotes
) {
    /** 用户取消或落库完成后调用；两条路径都必须擦（AC③ 锁的正是「取消路径同样已清零」）。 */
    fun wipe() = credential.wipeSecrets()
}

/**
 * Base64 编解码门面（字节进、字节出；**不出 String**）。
 *
 * 解码**先试 Base64URL、再试标准 Base64**：CXF 的 `b64url` 是无填充 Base64URL，
 * 而 KeePassXC `.passkey` 成员值是标准 Base64（含 `+ / =`）——两种拼写都必须在导入侧可用，
 * 否则 `PD-08` 第 1 项的兼容目标落空。编码一律走规范形（无填充 Base64URL），
 * 与注册生成路径的存储形态一致。
 */
internal object Base64Codec {

    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    private val stdDecoder: Base64.Decoder = Base64.getDecoder()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** 空输入解出零长数组；非法形态（含 `len % 4 == 1`、字母表混用）返回 null。 */
    fun decode(text: ByteArray): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        val padded = pad(text) ?: return null
        return try {
            urlDecoder.decode(padded)
        } catch (_: IllegalArgumentException) {
            try {
                stdDecoder.decode(padded)
            } catch (_: IllegalArgumentException) {
                null
            }
        } finally {
            // pad() 只在需要补填充时新建数组；复用原数组时不得清零调用方的节点字节
            if (padded !== text) padded.fill(0)
        }
    }

    fun encodeCanonical(bytes: ByteArray): String = urlEncoder.encodeToString(bytes)

    /** 补 `=` 到 4 的倍数；`len % 4 == 1` 是不可能形态 ⇒ null。 */
    private fun pad(text: ByteArray): ByteArray? {
        val rem = text.size % 4
        if (rem == 0) return text
        if (rem == 1) return null
        val out = text.copyOf(text.size + (4 - rem))
        for (i in text.size until out.size) out[i] = '='.code.toByte()
        return out
    }
}
