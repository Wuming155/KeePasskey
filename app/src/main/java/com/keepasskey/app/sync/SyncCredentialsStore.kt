package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 同步凭据模型
 */
data class WebDavCredentials(
    val url: String,
    val username: String,
    val password: String,
    val remotePath: String
)

/**
 * S3 兼容协议同步凭据模型
 */
data class S3Credentials(
    val endpoint: String,
    val bucket: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val objectKey: String,
    /** 寻址风格：false = virtual-host（AWS 等默认），true = path 风格（Cloudflare R2、IP 直连端点等） */
    val usePathStyle: Boolean = false
)

/**
 * 云同步协议凭据持久化加密存储库 (Wave 3-E P2-19)。
 * 遵循安全规范：
 * 敏感密码与 AccessKey/SecretKey 经 Keystore AES-256-GCM 硬件加密后落盘于私有 SharedPreferences 中。
 * 允许在单测中注入恒等/模拟加解密器以测试往返逻辑。
 */
@Singleton
class SyncCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager? = null,
    // 允许为 null 仅用于 JVM 单测注入；生产 DI 恒定注入真实实现
    private val debugLog: DebugLogBuffer? = null
) {
    // 供单元测试注入模拟加解密闭包
    var customEncryptor: ((ByteArray) -> Pair<ByteArray, ByteArray>)? = null // plaintext -> (iv, ciphertext)
    var customDecryptor: ((ByteArray, ByteArray) -> ByteArray)? = null // (iv, ciphertext) -> plaintext

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveProvider(provider: CloudSyncProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun loadProvider(): CloudSyncProvider {
        val name = prefs.getString(KEY_PROVIDER, CloudSyncProvider.WEBDAV.name)
        return try {
            CloudSyncProvider.valueOf(name ?: CloudSyncProvider.WEBDAV.name)
        } catch (_: Exception) {
            CloudSyncProvider.WEBDAV
        }
    }

    fun saveWebDavConfig(
        url: String,
        username: String,
        password: String,
        remotePath: String
    ) {
        val editor = prefs.edit()
        editor.putString(KEY_WEBDAV_URL, url)
        editor.putString(KEY_WEBDAV_USERNAME, username)
        editor.putString(KEY_WEBDAV_REMOTE_PATH, remotePath)

        if (password.isNotEmpty()) {
            val encrypted = encrypt(password)
            if (encrypted != null) {
                editor.putString(KEY_WEBDAV_PASSWORD_IV, encrypted.first)
                editor.putString(KEY_WEBDAV_PASSWORD_CIPHER, encrypted.second)
            }
        } else {
            // 显式清除旧密文，避免「旧密码在清空后仍继续生效」的隐式行为
            editor.remove(KEY_WEBDAV_PASSWORD_IV)
            editor.remove(KEY_WEBDAV_PASSWORD_CIPHER)
        }
        editor.apply()
    }

    fun loadWebDavConfig(): WebDavCredentials? {
        // Wave 14 证书固定整体移除：旧版本遗留的锁定配置键在此一次性物理清除，
        // 不依赖「用户下次保存配置」才清理（对齐下方旧版明文 AccessKey 的迁移模式）。
        // 用 getString 判空而非 contains()：语义等价，且兼容 JVM 单测的 SharedPreferences 代理桩
        if (prefs.getString(KEY_WEBDAV_CERT_PINS, null) != null) {
            prefs.edit().remove(KEY_WEBDAV_CERT_PINS).apply()
        }

        val url = prefs.getString(KEY_WEBDAV_URL, null) ?: return null
        val username = prefs.getString(KEY_WEBDAV_USERNAME, "") ?: ""
        val remotePath = prefs.getString(KEY_WEBDAV_REMOTE_PATH, "/keepasskey.kdbx") ?: "/keepasskey.kdbx"
        val iv = prefs.getString(KEY_WEBDAV_PASSWORD_IV, null)
        val cipher = prefs.getString(KEY_WEBDAV_PASSWORD_CIPHER, null)

        // P2 整改 fail-closed：已存在密文却解除封印失败（密钥被生物识别变更作废 / 密文损坏）
        // 时，原实现会把 password 退化为空串返回。上层据此会认为「用户主动清空了密码」，
        // 保存配置时进而把空密码写回云端，造成凭据不可逆丢失。现一律返回 null，交由 UI 显式重录。
        val password = if (isCipherTextPresent(iv, cipher)) {
            decrypt(iv, cipher) ?: return null
        } else {
            ""
        }

        return WebDavCredentials(
            url = url,
            username = username,
            password = password,
            remotePath = remotePath
        )
    }

    fun saveS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: String,
        secretKey: String,
        objectKey: String,
        usePathStyle: Boolean = false
    ) {
        val editor = prefs.edit()
        editor.putString(KEY_S3_ENDPOINT, endpoint)
        editor.putString(KEY_S3_BUCKET, bucket)
        editor.putString(KEY_S3_REGION, region)
        editor.putString(KEY_S3_OBJECT_KEY, objectKey)
        editor.putBoolean(KEY_S3_USE_PATH_STYLE, usePathStyle)

        // L4 整改：AccessKey 与 SecretKey 同样经 Keystore AES-256-GCM 加密落盘，不再明文存储
        if (accessKey.isNotEmpty()) {
            val encryptedAccessKey = encrypt(accessKey)
            if (encryptedAccessKey != null) {
                editor.putString(KEY_S3_ACCESS_KEY_IV, encryptedAccessKey.first)
                editor.putString(KEY_S3_ACCESS_KEY_CIPHER, encryptedAccessKey.second)
            }
        } else {
            editor.remove(KEY_S3_ACCESS_KEY_IV)
            editor.remove(KEY_S3_ACCESS_KEY_CIPHER)
        }
        if (secretKey.isNotEmpty()) {
            val encrypted = encrypt(secretKey)
            if (encrypted != null) {
                editor.putString(KEY_S3_SECRET_IV, encrypted.first)
                editor.putString(KEY_S3_SECRET_CIPHER, encrypted.second)
            }
        } else {
            editor.remove(KEY_S3_SECRET_IV)
            editor.remove(KEY_S3_SECRET_CIPHER)
        }
        editor.apply()
    }

    fun loadS3Config(): S3Credentials? {
        val endpoint = prefs.getString(KEY_S3_ENDPOINT, null) ?: return null
        val bucket = prefs.getString(KEY_S3_BUCKET, "") ?: ""
        val region = prefs.getString(KEY_S3_REGION, "us-east-1") ?: "us-east-1"
        val objectKey = prefs.getString(KEY_S3_OBJECT_KEY, "keepasskey.kdbx") ?: "keepasskey.kdbx"
        val usePathStyle = prefs.getBoolean(KEY_S3_USE_PATH_STYLE, false)
        val accessIv = prefs.getString(KEY_S3_ACCESS_KEY_IV, null)
        val accessCipher = prefs.getString(KEY_S3_ACCESS_KEY_CIPHER, null)

        val legacyPlainAccessKey = prefs.getString(KEY_S3_ACCESS_KEY, null)

        // P2 整改 fail-closed：存在密文却解封失败时不再回退为「空 / 明文」，
        // 直接返回 null 避免上层把失效凭据当作用户主动清空写回云端。
        val accessKey = when {
            isCipherTextPresent(accessIv, accessCipher) ->
                decrypt(accessIv, accessCipher) ?: return null
            !legacyPlainAccessKey.isNullOrBlank() -> legacyPlainAccessKey
            else -> ""
        }
        if (accessIv.isNullOrBlank() && !legacyPlainAccessKey.isNullOrBlank()) {
            // 旧版明文 AccessKey 残留清除：读取后立即转加密落盘并物理删除明文键，
            // 不再依赖「用户下次保存配置」才迁移
            val migrated = encrypt(legacyPlainAccessKey)
            if (migrated != null) {
                prefs.edit()
                    .putString(KEY_S3_ACCESS_KEY_IV, migrated.first)
                    .putString(KEY_S3_ACCESS_KEY_CIPHER, migrated.second)
                    .remove(KEY_S3_ACCESS_KEY)
                    .apply()
            }
        }

        val iv = prefs.getString(KEY_S3_SECRET_IV, null)
        val cipher = prefs.getString(KEY_S3_SECRET_CIPHER, null)
        // P2 整改 fail-closed：同上，SecretKey 存在密文却解封失败一律让上层感知
        val secretKey = if (isCipherTextPresent(iv, cipher)) {
            decrypt(iv, cipher) ?: return null
        } else {
            ""
        }

        return S3Credentials(
            endpoint = endpoint,
            bucket = bucket,
            region = region,
            accessKey = accessKey,
            secretKey = secretKey,
            objectKey = objectKey,
            usePathStyle = usePathStyle
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plaintext: String): Pair<String, String>? {
        if (plaintext.isEmpty()) return null
        val bytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        return try {
            val (iv, cipherBytes) = customEncryptor?.invoke(bytes) ?: run {
                val km = keystoreManager ?: return null
                val key = km.getOrCreateKey(SYNC_KEY_ALIAS, requireUserAuth = false)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                Pair(cipher.iv, cipher.doFinal(bytes))
            }
            Pair(
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(cipherBytes)
            )
        } catch (e: Exception) {
            // P2 整改：原实现 `catch (_: Exception) { null }` 全静默吞掉异常。
            // 密钥失效（用户增删指纹触发 setInvalidatedByBiometricEnrollment）或 Keystore 暂不可用时，
            // 封印失败会一路静默退化，运行期完全无从感知。现显式落调试日志——注意只记异常类型，绝不写明文。
            debugLog?.error(TAG, "同步凭据封印失败，凭据未写入: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun decrypt(ivBase64: String?, cipherBase64: String?): String? {
        if (ivBase64.isNullOrBlank() || cipherBase64.isNullOrBlank()) return null
        return try {
            val iv = Base64.getDecoder().decode(ivBase64)
            val cipherBytes = Base64.getDecoder().decode(cipherBase64)
            val decryptedBytes = customDecryptor?.invoke(iv, cipherBytes) ?: run {
                val km = keystoreManager ?: return null
                val key = km.getOrCreateKey(SYNC_KEY_ALIAS, requireUserAuth = false)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                cipher.init(Cipher.DECRYPT_MODE, key, spec)
                cipher.doFinal(cipherBytes)
            }
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // P2 整改：同上，解除封印失败不得静默。返回 null 由调用方按 fail-closed 处理，
            // 绝不退化为空字符串被上层误判为「用户主动清空了密码」。
            debugLog?.warn(TAG, "同步凭据解除封印失败: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 判定 iv + 密文二元组是否均已落盘（存在密文才意味着「曾成功封印过」，可用于区分空值与解封失败） */
    private fun isCipherTextPresent(ivBase64: String?, cipherBase64: String?): Boolean =
        !ivBase64.isNullOrBlank() && !cipherBase64.isNullOrBlank()

    companion object {
        private const val TAG = "SyncCredentialsStore"
        const val PREFS_NAME = "sync_credentials_prefs"
        const val SYNC_KEY_ALIAS = "com.keepasskey.sync_credential_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val KEY_PROVIDER = "sync_provider"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USERNAME = "webdav_username"
        private const val KEY_WEBDAV_REMOTE_PATH = "webdav_remote_path"
        private const val KEY_WEBDAV_PASSWORD_IV = "webdav_password_iv"
        private const val KEY_WEBDAV_PASSWORD_CIPHER = "webdav_password_cipher"
        /** Wave 14 已废弃的证书锁定键：仅保留常量供加载期一次性清理遗留数据引用 */
        private const val KEY_WEBDAV_CERT_PINS = "webdav_cert_pins"

        private const val KEY_S3_ENDPOINT = "s3_endpoint"
        private const val KEY_S3_BUCKET = "s3_bucket"
        private const val KEY_S3_REGION = "s3_region"
        private const val KEY_S3_ACCESS_KEY = "s3_access_key"
        private const val KEY_S3_ACCESS_KEY_IV = "s3_access_key_iv"
        private const val KEY_S3_ACCESS_KEY_CIPHER = "s3_access_key_cipher"
        private const val KEY_S3_OBJECT_KEY = "s3_object_key"
        private const val KEY_S3_SECRET_IV = "s3_secret_iv"
        private const val KEY_S3_SECRET_CIPHER = "s3_secret_cipher"
        private const val KEY_S3_USE_PATH_STYLE = "s3_use_path_style"
    }
}
