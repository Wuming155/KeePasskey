package com.keepasskey.app.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
        Mac.getInstance(ALGORITHM, KEYSTORE_PROVIDER)
            .apply { init(getOrCreateKey()) }
            .doFinal(data)
    } catch (_: Throwable) {
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
        /** 防回滚状态认证密钥别名（不绑用户认证，锁屏态后台同步可用） */
        const val KEY_ALIAS = "com.keepasskey.sync_rollback_integrity"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val ALGORITHM = "HmacSHA256"
    }
}
