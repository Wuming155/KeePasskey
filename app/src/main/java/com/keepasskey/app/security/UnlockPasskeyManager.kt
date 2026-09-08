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

/** 解锁断言产物：AuthenticatorData + ECDSA 签名 + 本次断言的 signCount */
data class UnlockPasskeyAssertion(
    val authenticatorData: ByteArray,
    val signature: ByteArray,
    val newSignCount: Int
)

/**
 * 设备绑定「解锁通行密钥」管理器（TASK-18）。
 *
 * 快速解锁语义升级：主密码封印（AES-256-GCM，生物识别/锁屏凭据门控）之外，
 * 叠加一层 WebAuthn 形态的本地通行密钥断言——
 * - 登记：成功主密码解锁后，生成硬件内不可导出的 ES256（P-256 ECDSA）密钥对
 *   （StrongBox 优先），公钥与随机 credentialId 落入应用私有存储；
 * - 解锁：生物识别授权解封成功后，以硬件私钥对 AuthenticatorData（rpIdHash + UP
 *   标志 + signCount）签名，公钥验证签名、rpIdHash 归属与 signCount 严格单调
 *   （WebAuthn 反克隆语义：计数器回退即判定密钥被克隆，fail-closed 拒绝解锁）。
 *
 * 兼容策略（诚实化）：本功能上线前已登记的快速解锁凭据没有通行密钥记录——
 * 首次解锁时跳过断言校验（不破坏既有用户），并在后台补登记，下次解锁起强制断言。
 */
@Singleton
class UnlockPasskeyManager @Inject constructor(
    private val credentialStorage: BiometricCredentialStorage,
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

    /** 是否已登记解锁通行密钥 */
    fun isEnrolled(databaseId: String): Boolean =
        credentialStorage.getUnlockPasskey(databaseId) != null

    /** 生成断言：以硬件私钥对 AuthenticatorData 签名（signCount 自增）；未登记/无密钥返回 null */
    fun assertUnlock(databaseId: String): UnlockPasskeyAssertion? {
        val record = credentialStorage.getUnlockPasskey(databaseId) ?: return null
        val keyPair = keystoreManager.getOrCreateUnlockPasskeyPair(aliasFor(databaseId)) ?: return null
        val newCount = record.signCount + 1
        return try {
            val authenticatorData = buildAuthenticatorData(RP_ID, newCount)
            val signer = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(keyPair.private)
                update(authenticatorData)
            }
            UnlockPasskeyAssertion(
                authenticatorData = authenticatorData,
                signature = signer.sign(),
                newSignCount = newCount
            )
        } catch (e: Exception) {
            debugLog?.warn(TAG, "解锁通行密钥断言失败: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    /** 验证断言（纯逻辑经 [verifyAssertion]）；成功后提交 signCount */
    fun verifyAndCommit(databaseId: String, assertion: UnlockPasskeyAssertion): Boolean {
        val record = credentialStorage.getUnlockPasskey(databaseId) ?: return false
        val verified = verifyAssertion(
            publicKeyEncoded = Base64.getDecoder().decode(record.publicKeyB64),
            authenticatorData = assertion.authenticatorData,
            signature = assertion.signature,
            expectedRpIdHash = rpIdHash(RP_ID),
            lastSignCount = record.signCount
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

        /** 本地解锁通行密钥的依赖方标识（rpId）——设备本地凭据，不参与网络验证 */
        const val RP_ID = "keepasskey.local"

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
         * 断言验证纯逻辑（JVM 可测）：签名有效 + rpIdHash 归属一致 + signCount 严格单调。
         * signCount 回退 = 克隆信号，fail-closed 拒绝（WebAuthn 反克隆语义）。
         */
        fun verifyAssertion(
            publicKeyEncoded: ByteArray,
            authenticatorData: ByteArray,
            signature: ByteArray,
            expectedRpIdHash: ByteArray,
            lastSignCount: Int
        ): Boolean {
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
                }
                verifier.verify(signature)
            } catch (e: Exception) {
                false
            }
        }
    }
}
