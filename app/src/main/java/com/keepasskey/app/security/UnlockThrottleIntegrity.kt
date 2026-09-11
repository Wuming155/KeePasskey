package com.keepasskey.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 解锁节流记录 MAC 载荷编码（ISSUE-P3-54，纯函数，JVM 可测）。
 *
 * 载荷仅含 `databaseId` + 失败计数 + 锁定截止时间戳，**不含任何主密码明文**；
 * 使用 `\u0000` 分隔避免字段边界歧义（长度前缀的简化等价形式）。
 */
object UnlockThrottleMacPayload {
    private const val FIELD_SEPARATOR = '\u0000'

    fun encode(databaseId: String, record: UnlockThrottleRecord): ByteArray =
        "$databaseId$FIELD_SEPARATOR${record.failureCount}$FIELD_SEPARATOR${record.lockoutUntilEpochMs}"
            .toByteArray(Charsets.UTF_8)
}

/**
 * 解锁节流记录完整性校验抽象（ISSUE-P3-54）。
 *
 * 生产环境使用 [AndroidKeystoreUnlockThrottleIntegrity]（AndroidKeyStore 内不可导出的 HMAC 密钥）；
 * JVM 单测注入固定密钥的假实现。记录被删除 / 篡改导致 MAC 校验失败时，由
 * [UnlockThrottleManager.gate] fail-closed 处理。
 */
interface UnlockThrottleIntegrity {
    /** 计算记录 MAC；密钥不可用时返回 null（调用方按 fail-closed 处理） */
    fun mac(databaseId: String, record: UnlockThrottleRecord): ByteArray?

    /** 校验记录 MAC；密钥不可用 / MAC 缺失 / 不匹配一律 false */
    fun verify(databaseId: String, record: UnlockThrottleRecord, mac: ByteArray?): Boolean
}

/**
 * [UnlockThrottleIntegrity] 的 AndroidKeystore 实现（ISSUE-P3-54）。
 *
 * 采用应用级 HMAC-SHA256 密钥（别名 [KEY_ALIAS]），**不绑定用户认证**——解锁前闸门
 * [UnlockThrottleManager.gate] 必须在锁屏态亦可用。密钥在 AndroidKeyStore 内生成、不可导出，
 * root 之外的普通文件级删除 / 篡改节流记录会因 MAC 不匹配触发 fail-closed 锁定。
 *
 * 威胁模型边界：具备任意代码执行 / Hook 进程能力的攻击者本可绕过应用层逻辑，本类属纵深防御卫生层。
 */
@Singleton
class AndroidKeystoreUnlockThrottleIntegrity @Inject constructor() : UnlockThrottleIntegrity {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KeystoreManager.ANDROID_KEY_STORE).apply { load(null) }
    }

    override fun mac(databaseId: String, record: UnlockThrottleRecord): ByteArray? = try {
        val mac = Mac.getInstance(HMAC_ALGORITHM, KeystoreManager.ANDROID_KEY_STORE)
        mac.init(getOrCreateKey())
        mac.doFinal(UnlockThrottleMacPayload.encode(databaseId, record))
    } catch (_: Throwable) {
        null
    }

    override fun verify(databaseId: String, record: UnlockThrottleRecord, mac: ByteArray?): Boolean {
        if (mac == null) return false
        val expected = mac(databaseId, record) ?: return false
        return MessageDigest.isEqual(expected, mac)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(HMAC_ALGORITHM, KeystoreManager.ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "com.keepasskey.unlock_throttle_integrity"
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
