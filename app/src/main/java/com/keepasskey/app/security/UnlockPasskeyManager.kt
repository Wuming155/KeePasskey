package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** 解锁断言产物：AuthenticatorData + ECDSA 签名 + clientDataJSON + 本次断言的 signCount */
data class UnlockPasskeyAssertion(
    val authenticatorData: ByteArray,
    val signature: ByteArray,
    val newSignCount: Int,
    /** 本地确定性序列化的 clientDataJSON（type/challenge/origin），签名覆盖其 SHA-256 摘要 */
    val clientDataJSON: ByteArray
)

/**
 * 解锁通行密钥断言门控结果（ISSUE-P1-09）。
 *
 * 「未登记」不再被静默放行：记录缺失（含被删 / 防篡改校验未过）与硬件签名失败
 * 均为显式门控结果，由调用方统一 fail-closed（拒绝快速解锁，引导主密码完整解锁）。
 */
sealed interface UnlockPasskeyGate {
    /** 断言已生成，待验证 */
    data class AssertionReady(val assertion: UnlockPasskeyAssertion) : UnlockPasskeyGate

    /** 登记记录缺失：未登记 / 记录被删 / 记录被篡改（防篡改 HMAC 校验未通过同样读为缺失） */
    data object NotEnrolled : UnlockPasskeyGate

    /** 硬件签名失败：Keystore 密钥不可用、用户认证窗口外、签名异常等 */
    data object SigningFailed : UnlockPasskeyGate
}

/**
 * 设备绑定「解锁通行密钥」管理器（TASK-18 / ISSUE-P1-09 fail-closed 化）。
 *
 * 快速解锁语义：主密码封印（AES-256-GCM，生物识别/锁屏凭据门控）之外，
 * 叠加一层 WebAuthn 形态的本地通行密钥断言——
 * - 登记：成功主密码解锁后，生成硬件内不可导出的 ES256（P-256 ECDSA）密钥对
 *   （StrongBox 优先），私钥**绑定强生物识别用户认证**（认证时间窗内方可签名，
 *   见 [KeystoreManager.getOrCreateUnlockPasskeyPair]）；公钥与随机 credentialId
 *   经防篡改 HMAC 封存于应用私有存储；
 * - 解锁：生物识别授权解封成功后，验证方生成一次性随机 challenge，硬件私钥对
 *   AuthenticatorData（rpIdHash + UP 标志 + signCount）|| SHA-256(clientDataJSON)
 *   签名；验证侧复核 clientDataJSON 规范性（type/challenge/origin 逐字节一致）、
 *   rpIdHash 归属与 signCount 严格单调（WebAuthn 反克隆语义：计数器回退即判定
 *   密钥被克隆，fail-closed 拒绝解锁）；
 * - 未登记（含记录被删/被篡改）一律 [UnlockPasskeyGate.NotEnrolled] fail-closed，
 *   **不得静默放行或后台补登记**——原「旧凭据兼容通道」（删除 3 个 key 即可一步
 *   绕过断言）已按 ISSUE-P1-09 移除。
 *
 * 诚实边界：验证方与证明方同进程同存储，本断言不构成第二因素；其价值在于
 * 持有性证明 + signCount 反克隆 + 记录防篡改抬高攻击门槛（文件级写入 / ADB
 * 备份恢复无法重算 HMAC），并使记录被删路径显式可见。
 */
@Singleton
class UnlockPasskeyManager @Inject constructor(
    private val credentialStorage: UnlockPasskeyStore,
    private val keystoreManager: KeystoreManager,
    private val debugLog: DebugLogBuffer? = null
) {

    private val random = SecureRandom()

    /** 登记/轮换解锁通行密钥（每次快速解锁凭据登记时调用；返回是否成功） */
    fun enroll(databaseId: String): Boolean {
        return try {
            val alias = aliasFor(databaseId)
            // 轮换语义：删除旧别名重建，credentialId 每次登记随机刷新
            keystoreManager.deleteKey(alias)
            val keyPair = keystoreManager.getOrCreateUnlockPasskeyPair(alias) ?: return false
            val credentialId = ByteArray(CREDENTIAL_ID_LENGTH).also { random.nextBytes(it) }
            credentialStorage.saveUnlockPasskey(
                databaseId,
                publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
                credentialIdB64 = Base64.getEncoder().encodeToString(credentialId),
                signCount = 0
            )
            debugLog?.info(TAG, "解锁通行密钥登记成功")
            true
        } catch (e: Exception) {
            debugLog?.warn(TAG, "解锁通行密钥登记失败: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    /** 生成一次性随机 challenge（验证方语义：由调用方持有并在验证时回传比对） */
    fun newChallenge(): ByteArray = ByteArray(CHALLENGE_LENGTH).also { random.nextBytes(it) }

    /**
     * 生成断言：以硬件私钥对 AuthenticatorData || SHA-256(clientDataJSON) 签名
     * （signCount 自增）。登记记录缺失返回 [UnlockPasskeyGate.NotEnrolled]（fail-closed），
     * 硬件签名失败返回 [UnlockPasskeyGate.SigningFailed]（fail-closed）。
     */
    fun assertUnlock(databaseId: String, challenge: ByteArray): UnlockPasskeyGate {
        if (challenge.size != CHALLENGE_LENGTH) return UnlockPasskeyGate.SigningFailed
        val record = credentialStorage.getUnlockPasskey(databaseId)
            ?: return UnlockPasskeyGate.NotEnrolled
        val keyPair = keystoreManager.getOrCreateUnlockPasskeyPair(aliasFor(databaseId))
            ?: return UnlockPasskeyGate.SigningFailed
        val newCount = record.signCount + 1
        return try {
            val authenticatorData = buildAuthenticatorData(RP_ID, newCount)
            val clientDataJSON = buildClientDataJson(challenge)
            val signer = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(keyPair.private)
                update(authenticatorData)
                update(sha256(clientDataJSON))
            }
            UnlockPasskeyGate.AssertionReady(
                UnlockPasskeyAssertion(
                    authenticatorData = authenticatorData,
                    signature = signer.sign(),
                    newSignCount = newCount,
                    clientDataJSON = clientDataJSON
                )
            )
        } catch (e: Exception) {
            debugLog?.warn(TAG, "解锁通行密钥断言失败: ${e.javaClass.simpleName} - ${e.message}")
            UnlockPasskeyGate.SigningFailed
        }
    }

    /** 验证断言（纯逻辑经 [verifyAssertion]）；成功后提交 signCount；记录缺失一律拒绝 */
    fun verifyAndCommit(
        databaseId: String,
        assertion: UnlockPasskeyAssertion,
        expectedChallenge: ByteArray
    ): Boolean {
        val record = credentialStorage.getUnlockPasskey(databaseId) ?: return false
        val verified = verifyAssertion(
            publicKeyEncoded = Base64.getDecoder().decode(record.publicKeyB64),
            authenticatorData = assertion.authenticatorData,
            signature = assertion.signature,
            expectedRpIdHash = rpIdHash(RP_ID),
            lastSignCount = record.signCount,
            clientDataJSON = assertion.clientDataJSON,
            expectedChallenge = expectedChallenge
        )
        if (verified) {
            credentialStorage.commitSignCount(databaseId, assertion.newSignCount)
        } else {
            debugLog?.warn(TAG, "解锁通行密钥断言校验未通过（fail-closed）")
        }
        return verified
    }

    /** 清除登记（凭据失效/密钥作废路径），同时删除硬件密钥别名 */
    fun clear(databaseId: String) {
        credentialStorage.clearUnlockPasskey(databaseId)
        try {
            keystoreManager.deleteKey(aliasFor(databaseId))
        } catch (e: Exception) {
            debugLog?.warn(TAG, "解锁通行密钥硬件别名删除失败: ${e.javaClass.simpleName}")
        }
    }

    private fun aliasFor(databaseId: String): String = "${KeystoreManager.BIOMETRIC_KEY_ALIAS}_passkey_$databaseId"

    companion object {
        private const val TAG = "UnlockPasskey"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val CREDENTIAL_ID_LENGTH = 32

        /** 一次性随机 challenge 长度（对齐 WebAuthn 惯例 32 字节） */
        const val CHALLENGE_LENGTH = 32

        /** 本地解锁通行密钥的依赖方标识（rpId）——设备本地凭据，不参与网络验证 */
        const val RP_ID = "keepasskey.local"

        /** clientDataJSON 的 origin（本地协议自定义，与 [RP_ID] 同源） */
        const val CLIENT_DATA_ORIGIN = "https://keepasskey.local"

        /** AuthenticatorData 固定头长：rpIdHash(32) + flags(1) + signCount(4) */
        const val AUTHENTICATOR_DATA_LENGTH = 37

        /** UP（User Present）标志位 */
        private const val FLAG_UP: Byte = 0x01

        /** rpIdHash：SHA-256(rpId UTF-8)（纯函数，供验证与测试复用） */
        fun rpIdHash(rpId: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(StandardCharsets.UTF_8))

        /** 构建解锁断言的 AuthenticatorData：rpIdHash + UP 标志 + 大端 signCount */
        fun buildAuthenticatorData(rpId: String, signCount: Int): ByteArray {
            val out = ByteArray(AUTHENTICATOR_DATA_LENGTH)
            rpIdHash(rpId).copyInto(out)
            out[32] = FLAG_UP
            out[33] = ((signCount ushr 24) and 0xFF).toByte()
            out[34] = ((signCount ushr 16) and 0xFF).toByte()
            out[35] = ((signCount ushr 8) and 0xFF).toByte()
            out[36] = (signCount and 0xFF).toByte()
            return out
        }

        /**
         * 本地确定性序列化的 clientDataJSON（WebAuthn 形态）：
         * type 固定 webauthn.get、challenge 为 Base64URL 无填充、origin 固定本地常量。
         * 验证侧按 [expectedChallenge] 重建后逐字节比对，任何字段篡改即 fail-closed。
         */
        fun buildClientDataJson(challenge: ByteArray): ByteArray {
            val challengeB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)
            return """{"type":"webauthn.get","challenge":"$challengeB64","origin":"$CLIENT_DATA_ORIGIN"}"""
                .toByteArray(StandardCharsets.UTF_8)
        }

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        /**
         * 断言验证纯逻辑（JVM 可测）：clientDataJSON 规范一致 + 签名有效
         * （覆盖 AuthenticatorData || SHA-256(clientDataJSON)）+ rpIdHash 归属一致 +
         * signCount 严格单调。signCount 回退 = 克隆信号，fail-closed 拒绝（WebAuthn 反克隆语义）。
         */
        fun verifyAssertion(
            publicKeyEncoded: ByteArray,
            authenticatorData: ByteArray,
            signature: ByteArray,
            expectedRpIdHash: ByteArray,
            lastSignCount: Int,
            clientDataJSON: ByteArray,
            expectedChallenge: ByteArray
        ): Boolean {
            // clientDataJSON 规范性：与按预期 challenge 重建的本地序列化逐字节一致
            if (!clientDataJSON.contentEquals(buildClientDataJson(expectedChallenge))) return false
            if (authenticatorData.size < AUTHENTICATOR_DATA_LENGTH) return false
            if (!authenticatorData.copyOfRange(0, 32).contentEquals(expectedRpIdHash)) return false
            val newCount = ((authenticatorData[33].toInt() and 0xFF) shl 24) or
                    ((authenticatorData[34].toInt() and 0xFF) shl 16) or
                    ((authenticatorData[35].toInt() and 0xFF) shl 8) or
                    (authenticatorData[36].toInt() and 0xFF)
            if (newCount <= lastSignCount) return false
            return try {
                val publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(publicKeyEncoded))
                val verifier = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                    initVerify(publicKey)
                    update(authenticatorData)
                    update(sha256(clientDataJSON))
                }
                verifier.verify(signature)
            } catch (e: Exception) {
                false
            }
        }
    }
}
