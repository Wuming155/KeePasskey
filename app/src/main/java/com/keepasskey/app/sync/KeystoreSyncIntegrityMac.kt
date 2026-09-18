package com.keepasskey.app.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.keepasskey.core.log.AppLog
import com.keepasskey.sync.engine.SyncIntegrityMac
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncIntegrityMac] 的 AndroidKeystore 实现（ISSUE-P2-18）。
 *
 * 采用应用级 HMAC-SHA256 密钥（别名 [KEY_ALIAS]），在 AndroidKeyStore 内生成、不可导出，
 * **不绑定用户认证**——后台同步需在锁屏态亦可用。用于认证本地防回滚高水位状态文件
 * （[com.keepasskey.sync.engine.SyncRollbackGuard]）。密钥在 Keystore 内受硬件隔离保护，
 * 普通文件级篡改无法伪造有效 MAC（验证失败 → 状态按「无历史」处理，不产生误报回退）。
 */
@Singleton
class KeystoreSyncIntegrityMac @Inject constructor() : SyncIntegrityMac {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    override fun compute(data: ByteArray): ByteArray? = try {
        // ISSUE-P0-10 同款纪律（`UnlockThrottleIntegrity` 已记载并被真机验证）：**不得**向
        // `Mac.getInstance` 传 "AndroidKeyStore" provider——该 provider 不注册 Mac 服务，
        // 传 provider 名必抛 `NoSuchAlgorithmException`，被本方法的 catch 吞成 null，
        // 于是 SyncRollbackGuard 永远视状态为不可信 ⇒ **防回滚在真机上整体静默停用**。
        // 不传 provider 时密钥仍由 AndroidKeyStore 持有并在 Keystore 内运算（官方 HMAC 样例同口径）。
        Mac.getInstance(ALGORITHM)
            .apply { init(getOrCreateKey()) }
            .doFinal(data)
    } catch (e: Throwable) {
        // ISSUE-P1-190：本方法的 `catch → null` 曾把「Keystore HMAC 整体失效」完全掩盖成
        // 「一切正常但防回滚悄悄下线」（真机上恒返回 null 且零留痕）。失败必须可观测——
        // 只记异常类型，绝不记载荷与密钥材料。
        AppLog.w(TAG, "同步防回滚 MAC 计算失败，状态将按不可信处理: ${e.javaClass.simpleName}", e)
        null
    }

    override fun verify(data: ByteArray, mac: ByteArray?): Boolean {
        if (mac == null) return false
        val expected = compute(data) ?: return false
        return MessageDigest.isEqual(expected, mac)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(ALGORITHM, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "KeystoreSyncIntegrityMac"

        /** 防回滚状态认证密钥别名（不绑用户认证，锁屏态后台同步可用） */
        const val KEY_ALIAS = "com.keepasskey.sync_rollback_integrity"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val ALGORITHM = "HmacSHA256"
    }
}
