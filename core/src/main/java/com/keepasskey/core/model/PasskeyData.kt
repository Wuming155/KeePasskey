package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString

/**
 * FIDO2 / WebAuthn 通行密钥 (Passkey) 核心领域模型。
 *
 * ## 落库 schema：**对齐 KeePassXC / KeePassDX**（`KPEX_PASSKEY_*`）
 *
 * 持久化至条目自定义字段（Custom Fields），键名与语义一律采用 KeePassXC（本项目 Passkey schema
 * 裁决者）的 `KPEX_PASSKEY_*` 保留键 —— KeePassDX 的 `PasskeyEntryFields` 明写
 * "field names from KeypassXC are used"，故三者共用同一 schema，**库文件级可互操作**：
 *
 * | 字段 | 键名 | 保护 |
 * |---|---|---|
 * | RP ID | `KPEX_PASSKEY_RELYING_PARTY` | 否 |
 * | 用户名 | `KPEX_PASSKEY_USERNAME` | 否 |
 * | User Handle | `KPEX_PASSKEY_USER_HANDLE` | **是** |
 * | Credential ID | `KPEX_PASSKEY_CREDENTIAL_ID` | **是** |
 * | 私钥（PKCS#8 PEM） | `KPEX_PASSKEY_PRIVATE_KEY_PEM` | **是** |
 * | 备份位 BE / BS | `KPEX_PASSKEY_FLAG_BE` / `_FLAG_BS`（`1` / `0`，KeePassXC 口径） | 否 |
 * | PRF 秘密（Base64） | `KPEX_PASSKEY_PRF` | **是** |
 *
 * KPEX schema **不含**签名计数器、公钥与展示名，本仓以 `Passkey.*` 前缀的**扩展键**承载
 * （`Passkey.SignCount` / `Passkey.PublicKey` / `Passkey.Algorithm` / `Passkey.UserDisplayName`
 * / `Passkey.CreatedAt`）：扩展键对其它管理器是无关属性，读写均忽略，不破坏互操作；
 * 其中 `Passkey.Algorithm` 的存在使热路径（自动填充候选评分）无需解密私钥嗅探算法。
 *
 * **历史 schema（v1，`Passkey.RelyingParty` / `Passkey.CredentialId` / `Passkey.PrivateKey` /
 * `Passkey.UserHandle` / `Passkey.UserName` / `Passkey.BackupEligible` / `Passkey.BackupState`）
 * 保留只读兼容**：既有库中的条目无需迁移即可继续断言；新写入一律采用 KPEX，且**写路径会
 * 就地自动迁移**——条目因断言而被修补（签名计数器）时整条换新为 KPEX（`ISSUE-P3-213`，
 * 判据见 [needsKpexMigration]）。
 *
 * ## ISSUE-P1-02 私钥受控生命周期与不可变边界声明
 * - KDBX 4 自定义字段值在格式层为 XML 文本（`<Value Protected="True">`），**必须以文本形态
 *   承载**——这是格式标准不可消解的边界，与 KeePassXC `KPEX_PASSKEY_PRIVATE_KEY_PEM` 的
 *   承载方式一致；新写入形态为 **PKCS#8 PEM**（v1 历史条目为 hex / Base64，读侧兼容）；
 * - 因此私钥明文文本的唯一长期持有者是 [privateKey] ([ProtectedString])：驻留态经
 *   [InMemoryCipher] 随机化加密，堆扫描不可直接读出；明文仅在受控读取瞬间物化；
 * - 任何对私钥的消费必须走 [usePrivateKeyBytes] 字节流通道（读出即用、退出自动清零），
 *   **严禁**调用 `readString()` 生成不可变私钥 String（不可擦除、必驻堆）；
 * - 除私钥与 PRF 秘密外的其余字段（rpId / userName / credentialId 等）均为 WebAuthn
 *   规范定义的公开材料，允许以 String 承载。
 */
data class PasskeyData(
    /**
     * 依赖方域名 / RP ID (例如: "github.com", "google.com")
     */
    val relyingPartyId: String,

    /**
     * 用户句柄 (User Handle / User ID 由 RP 分配，Base64URL 编码)。
     *
     * WebAuthn 规范要求认证器**原样保存并回传** RP 在注册请求中下发的 `user.id` ——
     * 无用户名（discoverable credential）登录依赖它找回账号，故本字段不得由本应用自行编造。
     */
    val userHandle: String,

    /**
     * 用户登录用户名或邮箱 (例如: "alice@example.com")
     */
    val userName: String,

    /**
     * 用户友好显示名称 (例如: "Alice")。KPEX schema 无对应字段，以扩展键
     * `Passkey.UserDisplayName` 承载（对其它管理器为无关属性）。
     */
    val userDisplayName: String = "",

    /**
     * 凭据唯一标识符 (Credential ID，Base64URL 编码)
     */
    val credentialId: String,

    /**
     * 算法标识 (COSE 算法标识，ES256 = -7, Ed25519 = -8, RS256 = -257)。
     *
     * KPEX schema 不含算法字段：本仓以扩展键 `Passkey.Algorithm` 承载，缺失时（外部管理器
     * 创建的条目）由 [PasskeyKeyText.sniffAlgorithmId] **纯字节嗅探**得出（先按 PKCS#8
     * `AlgorithmIdentifier` 内的 OID，再按 v1 文本形态：64 字符 hex → ES256、32 字节种子
     * → Ed25519，`ISSUE-P3-214`）；两者皆不可得时回落 [ALGORITHM_ES256]，签名时由
     * crypto 侧的权威解析 fail-closed 兜底。
     */
    val algorithmId: Int = ALGORITHM_ES256,

    /**
     * 公钥数据 (DER / X.509 SubjectPublicKeyInfo 格式，Base64 编码)。
     *
     * KPEX schema 无对应字段：本仓以扩展键 `Passkey.PublicKey` 承载；**外部管理器创建的条目
     * 该字段为空串**（公钥可由私钥推导，且生产路径只在注册当场消费，不回读）。
     */
    val publicKeyBase64: String = "",

    /**
     * 私钥数据（新写入为 PKCS#8 PEM；v1 历史条目为 hex / Base64），
     * 严格以 ProtectedString 封装，杜绝堆残留。
     *
     * 受控生命周期（ISSUE-P1-02）：本属性是私钥明文文本在内存中的**唯一**长期持有者
     * （密文驻留）；格式层要求的文本形态由其承载，消费侧一律走 [usePrivateKeyBytes]。
     */
    val privateKey: ProtectedString,

    /**
     * 签名计数器 (Signature Counter，防重放攻击)。KPEX schema 无该字段，以扩展键承载。
     */
    val signCount: Int = 0,

    /**
     * 多设备同步支持与状态标志位（写入 `KPEX_PASSKEY_FLAG_BE` / `_FLAG_BS`）
     */
    val backupEligible: Boolean = true,
    val backupState: Boolean = true,

    /**
     * 创建与修改时间戳 (毫秒)。KPEX schema 无该字段，以扩展键承载。
     */
    val createdAtMillis: Long = System.currentTimeMillis(),

    /**
     * WebAuthn **PRF 扩展**（`prf`）的凭据秘密：32 字节随机材料的 Base64 文本，
     * 承载于 `KPEX_PASSKEY_PRF`（受保护字段，KeePassDX 同键同保护口径）。
     *
     * 仅当注册请求携带 `extensions.prf` 时才生成；断言时用于按请求的 salt 计算
     * `HMAC-SHA-256(secret, SHA-256("WebAuthn PRF" || 0x00 || salt))`。
     */
    val prfSecret: ProtectedString? = null
) {
    /**
     * 将通行密钥数据映射为 KDBX 条目自定义字段列表（**KeePassXC / KeePassDX 兼容 schema**）。
     *
     * 零拷贝别名语义（ISSUE-P1-02）：[FIELD_PRIVATE_KEY] 与 [KPEX_FIELD_PRF] 字段**直接引用**
     * [privateKey] / [prfSecret] 同一 [ProtectedString] 实例（不克隆、不物化明文副本）。
     * 因此落库完成前**严禁**对本对象执行任何 `clear()`——否则会连同库内驻留字段一并置为
     * 已清零态，后续断言读取将 fail。
     */
    fun toCustomFields(): List<KdbxCustomField> {
        val fields = ArrayList<KdbxCustomField>(13)
        fields += KdbxCustomField(FIELD_RP_ID, ProtectedString(relyingPartyId, isProtected = false))
        fields += KdbxCustomField(FIELD_USER_NAME, ProtectedString(userName, isProtected = false))
        // KeePassXC / KeePassDX 口径：Credential ID 与 User Handle 同为受保护字段
        fields += KdbxCustomField(KPEX_FIELD_CREDENTIAL_ID, ProtectedString(credentialId, isProtected = true))
        fields += KdbxCustomField(KPEX_FIELD_USER_HANDLE, ProtectedString(userHandle, isProtected = true))
        fields += KdbxCustomField(FIELD_PRIVATE_KEY, privateKey)
        if (publicKeyBase64.isNotBlank()) {
            fields += KdbxCustomField(FIELD_PUBLIC_KEY, ProtectedString(publicKeyBase64, isProtected = false))
        }
        fields += KdbxCustomField(FIELD_ALGORITHM, ProtectedString(algorithmId.toString(), isProtected = false))
        fields += KdbxCustomField(KPEX_FIELD_FLAG_BE, ProtectedString(flagToFieldValue(backupEligible), isProtected = false))
        fields += KdbxCustomField(KPEX_FIELD_FLAG_BS, ProtectedString(flagToFieldValue(backupState), isProtected = false))
        prfSecret?.let { fields += KdbxCustomField(KPEX_FIELD_PRF, it) }
        fields += KdbxCustomField(FIELD_SIGN_COUNT, ProtectedString(clampSignCount(signCount).toString(), isProtected = false))
        fields += KdbxCustomField(FIELD_USER_DISPLAY_NAME, ProtectedString(userDisplayName, isProtected = false))
        fields += KdbxCustomField(FIELD_CREATED_AT, ProtectedString(createdAtMillis.toString(), isProtected = false))
        return fields
    }

    /**
     * 私钥受控消费通道（ISSUE-P1-02）：以 UTF-8 字节流读出私钥交由 [block] 使用，
     * 退出时自动清零字节副本——全程不产生不可变私钥 String。
     * 仅限签名 / 导入导出等一次性消费场景，对齐断言侧 `readUtf8()` 字节流路径。
     */
    inline fun <R> usePrivateKeyBytes(block: (ByteArray) -> R): R = privateKey.useUtf8(block)

    companion object {
        // COSE 算法定义 (RFC 8152 / W3C WebAuthn)
        const val ALGORITHM_ES256 = -7 // ECDSA with SHA-256 (P-256)
        const val ALGORITHM_ED25519 = -8 // EdDSA (Ed25519)
        const val ALGORITHM_RS256 = -257 // RSASSA-PKCS1-v1_5 with SHA-256

        // ---------------- KeePassXC / KeePassDX 兼容 schema（写入口径） ----------------

        /** 旧 v1 键名的前缀；KPEX 出现后仅用于承载本仓扩展键与只读兼容旧条目 */
        const val FIELD_PREFIX = "Passkey."

        /** `KPEX_PASSKEY_RELYING_PARTY`（RP ID） */
        const val KPEX_FIELD_RELYING_PARTY = "KPEX_PASSKEY_RELYING_PARTY"

        /** `KPEX_PASSKEY_USERNAME` */
        const val KPEX_FIELD_USERNAME = "KPEX_PASSKEY_USERNAME"

        /** `KPEX_PASSKEY_USER_HANDLE`（受保护） */
        const val KPEX_FIELD_USER_HANDLE = "KPEX_PASSKEY_USER_HANDLE"

        /** `KPEX_PASSKEY_CREDENTIAL_ID`（受保护） */
        const val KPEX_FIELD_CREDENTIAL_ID = "KPEX_PASSKEY_CREDENTIAL_ID"

        /** `KPEX_PASSKEY_PRIVATE_KEY_PEM`（受保护，PKCS#8 PEM） */
        const val KPEX_FIELD_PRIVATE_KEY = "KPEX_PASSKEY_PRIVATE_KEY_PEM"

        /** `KPEX_PASSKEY_FLAG_BE`（备份可用位，`1` / `0`） */
        const val KPEX_FIELD_FLAG_BE = "KPEX_PASSKEY_FLAG_BE"

        /** `KPEX_PASSKEY_FLAG_BS`（已备份状态位，`1` / `0`） */
        const val KPEX_FIELD_FLAG_BS = "KPEX_PASSKEY_FLAG_BS"

        /** `KPEX_PASSKEY_PRF`（PRF 凭据秘密，受保护，Base64） */
        const val KPEX_FIELD_PRF = "KPEX_PASSKEY_PRF"

        // 既有调用点沿用的常量名（值即 KPEX 键名，语义不变）
        const val FIELD_RP_ID = KPEX_FIELD_RELYING_PARTY
        const val FIELD_USER_HANDLE = KPEX_FIELD_USER_HANDLE
        const val FIELD_USER_NAME = KPEX_FIELD_USERNAME
        const val FIELD_CREDENTIAL_ID = KPEX_FIELD_CREDENTIAL_ID
        const val FIELD_PRIVATE_KEY = KPEX_FIELD_PRIVATE_KEY

        // ---------------- 本仓扩展键（KPEX 无对应项；对其它管理器为无关属性） ----------------

        /** 扩展：COSE 算法标识（使热路径无需解密私钥即可判定算法） */
        const val FIELD_ALGORITHM = "${FIELD_PREFIX}Algorithm"

        /** 扩展：公钥（SPKI DER 的 Base64） */
        const val FIELD_PUBLIC_KEY = "${FIELD_PREFIX}PublicKey"

        /** 扩展：签名计数器 */
        const val FIELD_SIGN_COUNT = "${FIELD_PREFIX}SignCount"

        /** 扩展：展示名 */
        const val FIELD_USER_DISPLAY_NAME = "${FIELD_PREFIX}UserDisplayName"

        /** 扩展：创建时间（毫秒） */
        const val FIELD_CREATED_AT = "${FIELD_PREFIX}CreatedAt"

        // ---------------- 历史 v1 schema（**只读兼容**，不再写入） ----------------

        /** v1：`Passkey.RelyingParty` */
        const val LEGACY_FIELD_RP_ID = "${FIELD_PREFIX}RelyingParty"

        /** v1：`Passkey.CredentialId` */
        const val LEGACY_FIELD_CREDENTIAL_ID = "${FIELD_PREFIX}CredentialId"

        /** v1：`Passkey.PrivateKey`（hex / Base64 文本形态） */
        const val LEGACY_FIELD_PRIVATE_KEY = "${FIELD_PREFIX}PrivateKey"

        /** v1：`Passkey.UserHandle`（非保护） */
        const val LEGACY_FIELD_USER_HANDLE = "${FIELD_PREFIX}UserHandle"

        /** v1：`Passkey.UserName` */
        const val LEGACY_FIELD_USER_NAME = "${FIELD_PREFIX}UserName"

        /** v1：`Passkey.BackupEligible`（布尔字面量） */
        const val LEGACY_FIELD_BACKUP_ELIGIBLE = "${FIELD_PREFIX}BackupEligible"

        /** v1：`Passkey.BackupState`（布尔字面量） */
        const val LEGACY_FIELD_BACKUP_STATE = "${FIELD_PREFIX}BackupState"

        /** 本模型占用的**全部** schema 键（KPEX + 扩展 + v1 只读兼容），用于「原地替换」时保留其它字段 */
        val SCHEMA_FIELD_KEYS: Set<String> = setOf(
            KPEX_FIELD_RELYING_PARTY,
            KPEX_FIELD_USERNAME,
            KPEX_FIELD_USER_HANDLE,
            KPEX_FIELD_CREDENTIAL_ID,
            KPEX_FIELD_PRIVATE_KEY,
            KPEX_FIELD_FLAG_BE,
            KPEX_FIELD_FLAG_BS,
            KPEX_FIELD_PRF,
            FIELD_ALGORITHM,
            FIELD_PUBLIC_KEY,
            FIELD_SIGN_COUNT,
            FIELD_USER_DISPLAY_NAME,
            FIELD_CREATED_AT,
            LEGACY_FIELD_RP_ID,
            LEGACY_FIELD_CREDENTIAL_ID,
            LEGACY_FIELD_PRIVATE_KEY,
            LEGACY_FIELD_USER_HANDLE,
            LEGACY_FIELD_USER_NAME,
            LEGACY_FIELD_BACKUP_ELIGIBLE,
            LEGACY_FIELD_BACKUP_STATE
        )

        /** [key] 是否属本模型的 schema 键（原地替换时须整体换新，不得残留旧值） */
        fun isPasskeyFieldKey(key: String): Boolean = key in SCHEMA_FIELD_KEYS

        /** v1 旧 schema 的键集合（[needsKpexMigration] 的判据来源） */
        private val LEGACY_FIELD_KEYS: Set<String> = setOf(
            LEGACY_FIELD_RP_ID,
            LEGACY_FIELD_CREDENTIAL_ID,
            LEGACY_FIELD_PRIVATE_KEY,
            LEGACY_FIELD_USER_HANDLE,
            LEGACY_FIELD_USER_NAME,
            LEGACY_FIELD_BACKUP_ELIGIBLE,
            LEGACY_FIELD_BACKUP_STATE
        )

        /** KPEX 核心三键（RP ID / Credential ID / 私钥）：齐备才认为条目已是 KPEX 形态 */
        private val KPEX_CORE_FIELD_KEYS: Set<String> = setOf(
            KPEX_FIELD_RELYING_PARTY,
            KPEX_FIELD_CREDENTIAL_ID,
            KPEX_FIELD_PRIVATE_KEY
        )

        /**
         * 条目是否**仍需由 v1 旧 schema 迁移到 KPEX**（`ISSUE-P3-213`）：持有任一 v1 旧键
         * 且 KPEX 核心三键（RP ID / Credential ID / 私钥）不齐备。
         *
         * 与 [fromCustomFields] 一致，本判定**只扫键名**：不解密、不建 Map、不物化明文，
         * 故可安全地在写入路径（每次签名计数器修补）上先判后迁。
         */
        fun needsKpexMigration(fields: List<KdbxCustomField>): Boolean {
            var hasLegacy = false
            var coreKeyCount = 0
            for (field in fields) {
                val key = field.key
                if (key in LEGACY_FIELD_KEYS) {
                    hasLegacy = true
                } else if (key in KPEX_CORE_FIELD_KEYS) {
                    coreKeyCount++
                }
            }
            return hasLegacy && coreKeyCount < KPEX_CORE_FIELD_KEYS.size
        }

        /**
         * 签名计数器「未知 / 认证器不支持计数器」哨兵值。
         *
         * WebAuthn 规范：认证器不实现计数器时该值恒为 0，RP 据此跳过克隆检测。
         * 字段缺失或文本非法同样归入该值——如实表达「无计数器」，绝不伪造成递增序列。
         */
        const val SIGN_COUNT_UNKNOWN: Int = 0

        /**
         * 签名计数器合法上界（ISSUE-P3-10 子项 2，CWE-190 整数溢出防护）。
         *
         * 取 `Int.MAX_VALUE - 1`：为递增运算预留 1 的余量，使**任何**经本契约产出的
         * 计数器值（含 [nextSignCount] 的返回值、写入 KDBX 的文本、写入
         * AuthenticatorData 的 uint32）都落在 `SIGN_COUNT_UNKNOWN..MAX_SIGN_COUNT` 闭区间内，
         * `signCount + 1` 永无溢出为负的机会。
         *
         * 背景：KDBX 自定义字段属不可信输入，原实现 `toIntOrNull() ?: 0` 允许恶意库写入
         * `Int.MAX_VALUE`，断言侧 `+1` 随即回绕为 `Int.MIN_VALUE`（负计数器），
         * 向 RP 交出语义错乱的重放防护状态。
         */
        const val MAX_SIGN_COUNT: Int = Int.MAX_VALUE - 1

        /** 计数器上界 [MAX_SIGN_COUNT] 的十进制位数（用于解析超长数字串时短路钳制） */
        private const val MAX_SIGN_COUNT_DECIMAL_DIGITS = 10

        /** 十进制数字字符区间（拒绝正负号、小数点、空白等一切非纯数字文本） */
        private val DECIMAL_DIGIT_RANGE = '0'..'9'

        /**
         * 把任意 Int 钳制到合法计数器区间 `[SIGN_COUNT_UNKNOWN], [MAX_SIGN_COUNT]`。
         * 负数（含溢出产物）归 [SIGN_COUNT_UNKNOWN]，超上界归 [MAX_SIGN_COUNT]。
         */
        fun clampSignCount(value: Int): Int = value.coerceIn(SIGN_COUNT_UNKNOWN, MAX_SIGN_COUNT)

        /**
         * 解析持久化的计数器文本（KDBX 自定义字段为不可信输入）：
         * - 缺失 / 空 / 含非数字字符（含负号）→ [SIGN_COUNT_UNKNOWN]；
         * - 纯数字但超出 [MAX_SIGN_COUNT]（含位数超出 Int 表达范围的超长串）→ 钳制为 [MAX_SIGN_COUNT]。
         */
        fun parseSignCount(raw: String?): Int {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || !text.all { it in DECIMAL_DIGIT_RANGE }) {
                return SIGN_COUNT_UNKNOWN
            }
            // 位数超出 Int 十进制上限时无需构造数值即可判定超界（避免 toIntOrNull 溢出为 null
            // 被静默降级为「未知」而丢失「超上界」语义）
            if (text.length > MAX_SIGN_COUNT_DECIMAL_DIGITS) {
                return MAX_SIGN_COUNT
            }
            // 位数已限定，Long 解析恒成功；再按上界钳制（"9999999999" 之类超 Int 值同样归上界）
            val parsed = text.toLong()
            return if (parsed > MAX_SIGN_COUNT.toLong()) MAX_SIGN_COUNT else parsed.toInt()
        }

        /**
         * 布尔标志位的**落库文本**：`1` / `0`（KeePassXC `KPEX_PASSKEY_FLAG_*` 口径）。
         * 读取侧经 [parseFlag] 宽松兼容 `1/0`、`true/false`、`yes/no`（KeePassDX 与手工库混用）。
         */
        fun flagToFieldValue(value: Boolean): String = if (value) "1" else "0"

        /**
         * 宽松解析布尔标志位文本（KDBX 自定义字段为不可信输入）：
         * `1/true/yes` → true，`0/false/no` → false，缺失或无法识别 → [default]。
         */
        fun parseFlag(raw: String?, default: Boolean): Boolean {
            return when (raw?.trim()?.lowercase()) {
                "1", "true", "yes" -> true
                "0", "false", "no" -> false
                else -> default
            }
        }

        /**
         * 读取条目自定义字段中的当前签名计数器（不构造完整 [PasskeyData]，
         * 供断言侧的原子递增路径读取「库内现值」）。
         */
        fun readSignCount(fields: List<KdbxCustomField>): Int =
            parseSignCount(fields.firstOrNull { it.key == FIELD_SIGN_COUNT }?.value?.readString())

        /**
         * 断言递增后的下一个计数器值：恒非负、恒不超过 [MAX_SIGN_COUNT]（饱和递增，绝不回绕）。
         * 签名（写入 AuthenticatorData）与落库（写入自定义字段）必须共用本函数，保证两者一致。
         */
        fun nextSignCount(current: Int): Int {
            val clamped = clampSignCount(current)
            return if (clamped >= MAX_SIGN_COUNT) MAX_SIGN_COUNT else clamped + 1
        }

        /**
         * 从条目自定义字段中解析还原 PasskeyData；若缺少关键字段则返回 null。
         *
         * 兼容两套 schema：**KPEX（新，优先）** 与 **v1 `Passkey.*`（历史，只读兼容）**；
         * 两套的必需键分别为「RP ID / Credential ID / PrivateKey」，任一整套齐备即可解析。
         *
         * 反序列化边界（ISSUE-P1-02）：本方法对私钥字段**只引用不读取**（[privateKey] 直接
         * 挂接库内既有 [ProtectedString]，不物化明文）；仅在扩展键 `Passkey.Algorithm` 缺失时
         * 才经 [usePrivateKeyBytes] 字节通道嗅探算法（外部管理器条目），仍不产生 String。
         */
        fun fromCustomFields(fields: List<KdbxCustomField>): PasskeyData? {
            // ISSUE-P3-171：先做**不解密、不建 Map** 的形状短路——绝大多数条目根本没有 passkey
            // 字段，而原实现无论有没有都先 `associateBy` 建一次 Map，再逐个 `readString()`
            // （每次都是一次驻留密文解密 + String 物化）。此处只扫 key 名，两套 schema 各三个
            // 必需键，均不齐备即返回。
            var kpexRpId = false
            var kpexCredentialId = false
            var kpexPrivateKey = false
            var legacyRpId = false
            var legacyCredentialId = false
            var legacyPrivateKey = false
            for (field in fields) {
                when (field.key) {
                    KPEX_FIELD_RELYING_PARTY -> kpexRpId = true
                    KPEX_FIELD_CREDENTIAL_ID -> kpexCredentialId = true
                    KPEX_FIELD_PRIVATE_KEY -> kpexPrivateKey = true
                    LEGACY_FIELD_RP_ID -> legacyRpId = true
                    LEGACY_FIELD_CREDENTIAL_ID -> legacyCredentialId = true
                    LEGACY_FIELD_PRIVATE_KEY -> legacyPrivateKey = true
                }
            }
            val kpexComplete = kpexRpId && kpexCredentialId && kpexPrivateKey
            val legacyComplete = legacyRpId && legacyCredentialId && legacyPrivateKey
            if (!kpexComplete && !legacyComplete) return null

            val map = fields.associateBy { it.key }
            val rpId = (map[KPEX_FIELD_RELYING_PARTY] ?: map[LEGACY_FIELD_RP_ID])?.value?.readString()
                ?: return null
            val credId = (map[KPEX_FIELD_CREDENTIAL_ID] ?: map[LEGACY_FIELD_CREDENTIAL_ID])?.value?.readString()
                ?: return null
            val privateKeyVal = map[KPEX_FIELD_PRIVATE_KEY]?.value
                ?: map[LEGACY_FIELD_PRIVATE_KEY]?.value
                ?: return null

            val userHandle = (map[KPEX_FIELD_USER_HANDLE] ?: map[LEGACY_FIELD_USER_HANDLE])
                ?.value?.readString().orEmpty()
            val userName = (map[KPEX_FIELD_USERNAME] ?: map[LEGACY_FIELD_USER_NAME])
                ?.value?.readString().orEmpty()
            val userDisplayName = map[FIELD_USER_DISPLAY_NAME]?.value?.readString().orEmpty()
            // 扩展键优先（写入口径）；缺失（外部管理器条目 / v1 历史条目）则字节嗅探
            // （PKCS#8 OID → v1 hex 标量 / 32 字节种子），仍不可得时回落 ES256
            // —— 签名侧由 crypto 的权威解析 fail-closed 兜底。
            val algorithmId = map[FIELD_ALGORITHM]?.value?.readString()?.toIntOrNull()
                ?: privateKeyVal.useUtf8 { PasskeyKeyText.sniffAlgorithmId(it) }
                ?: ALGORITHM_ES256
            val publicKeyBase64 = map[FIELD_PUBLIC_KEY]?.value?.readString().orEmpty()
            // ISSUE-P3-10 子项 2：计数器文本经统一解析边界收口（钳制 + 缺失归哨兵），
            // 严禁裸 `toIntOrNull() ?: 0` 让不可信库直接注入溢出前值
            val signCount = parseSignCount(map[FIELD_SIGN_COUNT]?.value?.readString())
            val backupEligible = parseFlag(
                (map[KPEX_FIELD_FLAG_BE] ?: map[LEGACY_FIELD_BACKUP_ELIGIBLE])?.value?.readString(),
                default = true
            )
            val backupState = parseFlag(
                (map[KPEX_FIELD_FLAG_BS] ?: map[LEGACY_FIELD_BACKUP_STATE])?.value?.readString(),
                default = true
            )
            val createdAt = map[FIELD_CREATED_AT]?.value?.readString()?.toLongOrNull()
                ?: System.currentTimeMillis()

            return PasskeyData(
                relyingPartyId = rpId,
                userHandle = userHandle,
                userName = userName,
                userDisplayName = userDisplayName,
                credentialId = credId,
                algorithmId = algorithmId,
                publicKeyBase64 = publicKeyBase64,
                privateKey = privateKeyVal,
                signCount = signCount,
                backupEligible = backupEligible,
                backupState = backupState,
                createdAtMillis = createdAt,
                prfSecret = map[KPEX_FIELD_PRF]?.value
            )
        }
    }
}
