package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString

/**
 * FIDO2 / WebAuthn 通行密钥 (Passkey) 核心领域模型。
 * 遵循 W3C WebAuthn 规范与 KeePass 扩展标准（对齐 KeePassXC / KeeWeb / KeePassDX）。
 * 持久化至条目自定义字段 (Custom Fields)，私钥严格以 ProtectedString / CharArray 保护。
 *
 * **ISSUE-P1-02 私钥受控生命周期与不可变边界声明**：
 * - KDBX 4 自定义字段值在格式层为 XML 文本（`<Value Protected="True">`），**必须以文本形态
 *   承载**——这是格式标准不可消解的边界，与 KeePassXC `KPEX_PASSKEY_PRIVATE_KEY_PEM` 等
 *   schema 的承载方式一致；
 * - 因此私钥明文文本的唯一长期持有者是 [privateKey] ([ProtectedString])：驻留态经
 *   [InMemoryCipher] 随机化加密，堆扫描不可直接读出；明文仅在受控读取瞬间物化；
 * - 任何对私钥的消费必须走 [usePrivateKeyBytes] 字节流通道（读出即用、退出自动清零），
 *   **严禁**调用 `readString()` 生成不可变私钥 String（不可擦除、必驻堆）；
 * - 除私钥外的其余字段（rpId / userName / credentialId / 公钥等）均为 WebAuthn 规范定义的
 *   公开材料，允许以 String 承载。
 */
data class PasskeyData(
    /**
     * 依赖方域名 / RP ID (例如: "github.com", "google.com")
     */
    val relyingPartyId: String,

    /**
     * 用户句柄 (User Handle / User ID 由 RP 分配，Base64URL 编码)
     */
    val userHandle: String,

    /**
     * 用户登录用户名或邮箱 (例如: "alice@example.com")
     */
    val userName: String,

    /**
     * 用户友好显示名称 (例如: "Alice")
     */
    val userDisplayName: String = "",

    /**
     * 凭据唯一标识符 (Credential ID，Base64URL 编码)
     */
    val credentialId: String,

    /**
     * 算法标识 (COSE 算法标识，ES256 = -7, Ed25519 = -8, RS256 = -257)
     */
    val algorithmId: Int = ALGORITHM_ES256,

    /**
     * 公钥数据 (DER / X.509 SubjectPublicKeyInfo 格式，Base64 编码)
     */
    val publicKeyBase64: String,

    /**
     * 私钥数据 (PKCS#8 格式，严格以 ProtectedString 封装，杜绝堆残留)。
     *
     * 受控生命周期（ISSUE-P1-02）：本属性是私钥明文文本在内存中的**唯一**长期持有者
     * （密文驻留）；格式层要求的文本形态由其承载，消费侧一律走 [usePrivateKeyBytes]。
     */
    val privateKey: ProtectedString,

    /**
     * 签名计数器 (Signature Counter，防重放攻击)
     */
    val signCount: Int = 0,

    /**
     * 多设备同步支持与状态标志位
     */
    val backupEligible: Boolean = true,
    val backupState: Boolean = true,

    /**
     * 创建与修改时间戳 (毫秒)
     */
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    /**
     * 将通行密钥数据映射为 KDBX 条目自定义字段列表 (对齐 KeePass 事实标准)。
     *
     * 零拷贝别名语义（ISSUE-P1-02）：[FIELD_PRIVATE_KEY] 字段**直接引用** [privateKey]
     * 同一 [ProtectedString] 实例（不克隆、不物化明文副本）。因此落库完成前**严禁**对本
     * 对象执行任何 `clear()`——否则会连同库内驻留字段一并置为已清零态，后续断言读取将 fail。
     */
    fun toCustomFields(): List<KdbxCustomField> {
        return listOf(
            KdbxCustomField(FIELD_RP_ID, ProtectedString(relyingPartyId, isProtected = false)),
            KdbxCustomField(FIELD_USER_HANDLE, ProtectedString(userHandle, isProtected = false)),
            KdbxCustomField(FIELD_USER_NAME, ProtectedString(userName, isProtected = false)),
            KdbxCustomField(FIELD_USER_DISPLAY_NAME, ProtectedString(userDisplayName, isProtected = false)),
            KdbxCustomField(FIELD_CREDENTIAL_ID, ProtectedString(credentialId, isProtected = false)),
            KdbxCustomField(FIELD_ALGORITHM, ProtectedString(algorithmId.toString(), isProtected = false)),
            KdbxCustomField(FIELD_PUBLIC_KEY, ProtectedString(publicKeyBase64, isProtected = false)),
            KdbxCustomField(FIELD_PRIVATE_KEY, privateKey),
            KdbxCustomField(FIELD_SIGN_COUNT, ProtectedString(clampSignCount(signCount).toString(), isProtected = false)),
            KdbxCustomField(FIELD_BACKUP_ELIGIBLE, ProtectedString(backupEligible.toString(), isProtected = false)),
            KdbxCustomField(FIELD_BACKUP_STATE, ProtectedString(backupState.toString(), isProtected = false)),
            KdbxCustomField(FIELD_CREATED_AT, ProtectedString(createdAtMillis.toString(), isProtected = false))
        )
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

        // KDBX 自定义字段键名标准
        const val FIELD_PREFIX = "Passkey."
        const val FIELD_RP_ID = "${FIELD_PREFIX}RelyingParty"
        const val FIELD_USER_HANDLE = "${FIELD_PREFIX}UserHandle"
        const val FIELD_USER_NAME = "${FIELD_PREFIX}UserName"
        const val FIELD_USER_DISPLAY_NAME = "${FIELD_PREFIX}UserDisplayName"
        const val FIELD_CREDENTIAL_ID = "${FIELD_PREFIX}CredentialId"
        const val FIELD_ALGORITHM = "${FIELD_PREFIX}Algorithm"
        const val FIELD_PUBLIC_KEY = "${FIELD_PREFIX}PublicKey"
        const val FIELD_PRIVATE_KEY = "${FIELD_PREFIX}PrivateKey"
        const val FIELD_SIGN_COUNT = "${FIELD_PREFIX}SignCount"
        const val FIELD_BACKUP_ELIGIBLE = "${FIELD_PREFIX}BackupEligible"
        const val FIELD_BACKUP_STATE = "${FIELD_PREFIX}BackupState"
        const val FIELD_CREATED_AT = "${FIELD_PREFIX}CreatedAt"

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
         * 反序列化边界（ISSUE-P1-02）：本方法对私钥字段**只引用不读取**（[privateKey] 直接
         * 挂接库内既有 [ProtectedString]，不物化明文）；其余字段为公开材料，允许 `readString()`
         * 生成 String（候选匹配等只读消费所需）。
         */
        fun fromCustomFields(fields: List<KdbxCustomField>): PasskeyData? {
            val map = fields.associateBy { it.key }
            val rpId = map[FIELD_RP_ID]?.value?.readString() ?: return null
            val credId = map[FIELD_CREDENTIAL_ID]?.value?.readString() ?: return null
            val privateKeyVal = map[FIELD_PRIVATE_KEY]?.value ?: return null

            val userHandle = map[FIELD_USER_HANDLE]?.value?.readString() ?: ""
            val userName = map[FIELD_USER_NAME]?.value?.readString() ?: ""
            val userDisplayName = map[FIELD_USER_DISPLAY_NAME]?.value?.readString() ?: ""
            val algorithmId = map[FIELD_ALGORITHM]?.value?.readString()?.toIntOrNull() ?: ALGORITHM_ES256
            val publicKeyBase64 = map[FIELD_PUBLIC_KEY]?.value?.readString() ?: ""
            // ISSUE-P3-10 子项 2：计数器文本经统一解析边界收口（钳制 + 缺失归哨兵），
            // 严禁裸 `toIntOrNull() ?: 0` 让不可信库直接注入溢出前值
            val signCount = parseSignCount(map[FIELD_SIGN_COUNT]?.value?.readString())
            val backupEligible = map[FIELD_BACKUP_ELIGIBLE]?.value?.readString()?.toBooleanStrictOrNull() ?: true
            val backupState = map[FIELD_BACKUP_STATE]?.value?.readString()?.toBooleanStrictOrNull() ?: true
            val createdAt = map[FIELD_CREATED_AT]?.value?.readString()?.toLongOrNull() ?: System.currentTimeMillis()

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
                createdAtMillis = createdAt
            )
        }
    }
}
