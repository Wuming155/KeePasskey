package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString

/**
 * FIDO2 / WebAuthn 通行密钥 (Passkey) 核心领域模型。
 * 遵循 W3C WebAuthn 规范与 KeePass 扩展标准（对齐 KeePassXC / KeeWeb / KeePassDX）。
 * 持久化至条目自定义字段 (Custom Fields)，私钥严格以 ProtectedString / CharArray 保护。
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
     * 私钥数据 (PKCS#8 格式，严格以 ProtectedString 封装，杜绝堆残留)
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
     * 将通行密钥数据映射为 KDBX 条目自定义字段列表 (对齐 KeePass 事实标准)
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
            KdbxCustomField(FIELD_SIGN_COUNT, ProtectedString(signCount.toString(), isProtected = false)),
            KdbxCustomField(FIELD_BACKUP_ELIGIBLE, ProtectedString(backupEligible.toString(), isProtected = false)),
            KdbxCustomField(FIELD_BACKUP_STATE, ProtectedString(backupState.toString(), isProtected = false)),
            KdbxCustomField(FIELD_CREATED_AT, ProtectedString(createdAtMillis.toString(), isProtected = false))
        )
    }

    companion object {
        // COSE 算法定义 (RFC 8152 / W3C WebAuthn)
        const val ALGORITHM_ES256 = -7 // ECDSA with SHA-256 (P-256)
        const val ALGORITHM_ED25519 = -8 // EdDSA (Ed25519)
        const val ALGORITHM_RS256 = -257 // RSASSA-PKCS1-v1_5 with SHA-256

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

        /**
         * 从条目自定义字段中解析还原 PasskeyData；若缺少关键字段则返回 null
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
            val signCount = map[FIELD_SIGN_COUNT]?.value?.readString()?.toIntOrNull() ?: 0
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
