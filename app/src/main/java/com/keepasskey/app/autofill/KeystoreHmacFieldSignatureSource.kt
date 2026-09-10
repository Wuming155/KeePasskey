package com.keepasskey.app.autofill

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Android Keystore 的字段签名密钥来源（ISSUE-P3-46 生产实现）。
 *
 * 设计要点（对齐 [KeystoreManager] 既有 HMAC 用法）：
 * - 密钥为 `HmacSHA256` 对称密钥，**生成并驻留于 Android Keystore（TEE / StrongBox）内，不可导出**——
 *   进程只能提交原文取回 MAC，无法取得密钥字节，因此即便攻击者拿到应用私有目录也无法离线枚举签名；
 * - `setUserAuthenticationRequired(false)`：自动填充服务进程需在后台离线计算签名，不能绑定用户认证；
 *   该密钥仅用于「不可逆、抗枚举」的字段签名，不承载任何可解封的密文，故无认证门控不构成降级；
 * - **不落盘任何密钥材料**：盐 / 密钥字节一律不写入 SharedPreferences（对照整改前的随机盐方案）；
 * - 密钥别名固定且随应用卸载销毁；用户清数据 / 卸载重装会使密钥失效 → 签名自然失效
 *   （等价于屏蔽记录保守清空，不会误命中）。
 *
 * fail-closed：`context` 为 null（纯 JVM 单测不注入本类）或 Keystore 访问异常时,
 * [hmacSha256] 返回 null，由上层按「视为已屏蔽」处理。
 */
@Singleton
class KeystoreHmacFieldSignatureSource @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 双构造器冲突
    @ApplicationContext private val context: Context?
) : HmacFieldSignatureSource {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    override fun hmacSha256(document: ByteArray): ByteArray? {
        val mac = obtainMac() ?: return null
        return try {
            mac.doFinal(document)
        } catch (_: Exception) {
            // 密码学操作失败一律 fail-closed（调用方据 null 视为密钥不可用）
            null
        }
    }

    /**
     * 取得已初始化的 Mac 实例；任何一步失败（含无 context、Keystore 异常）返回 null。
     * 每次调用新建 Mac 实例，避免跨线程共享（`javax.crypto.Mac` 非线程安全）。
     */
    @Synchronized
    private fun obtainMac(): Mac? {
        if (context == null) return null
        return try {
            if (!keyStore.containsAlias(KEY_ALIAS)) generateKey()
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
            Mac.getInstance(MAC_ALGORITHM).apply { init(entry.secretKey) }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun generateKey() {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEY_STORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"

        /** 字段签名 HMAC 密钥别名（卸载即随应用数据销毁） */
        const val KEY_ALIAS = "com.keepasskey.autofill_field_signature"

        const val MAC_ALGORITHM = "HmacSHA256"
        const val KEY_SIZE_BITS = 256
    }
}
