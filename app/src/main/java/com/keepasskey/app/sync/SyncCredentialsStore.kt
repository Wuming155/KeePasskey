package com.keepasskey.app.sync

import android.content.Context
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
    val objectKey: String
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
    private val keystoreManager: KeystoreManager? = null
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
        }
        editor.apply()
    }

    fun loadWebDavConfig(): WebDavCredentials? {
        val url = prefs.getString(KEY_WEBDAV_URL, null) ?: return null
        val username = prefs.getString(KEY_WEBDAV_USERNAME, "") ?: ""
        val remotePath = prefs.getString(KEY_WEBDAV_REMOTE_PATH, "/keepasskey.kdbx") ?: "/keepasskey.kdbx"
        val iv = prefs.getString(KEY_WEBDAV_PASSWORD_IV, null)
        val cipher = prefs.getString(KEY_WEBDAV_PASSWORD_CIPHER, null)
        val password = decrypt(iv, cipher) ?: ""

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
        objectKey: String
    ) {
        val editor = prefs.edit()
        editor.putString(KEY_S3_ENDPOINT, endpoint)
        editor.putString(KEY_S3_BUCKET, bucket)
        editor.putString(KEY_S3_REGION, region)
        editor.putString(KEY_S3_OBJECT_KEY, objectKey)

        // L4 整改：AccessKey 与 SecretKey 同样经 Keystore AES-256-GCM 加密落盘，不再明文存储
        if (accessKey.isNotEmpty()) {
            val encryptedAccessKey = encrypt(accessKey)
            if (encryptedAccessKey != null) {
                editor.putString(KEY_S3_ACCESS_KEY_IV, encryptedAccessKey.first)
                editor.putString(KEY_S3_ACCESS_KEY_CIPHER, encryptedAccessKey.second)
            }
        }
        if (secretKey.isNotEmpty()) {
            val encrypted = encrypt(secretKey)
            if (encrypted != null) {
                editor.putString(KEY_S3_SECRET_IV, encrypted.first)
                editor.putString(KEY_S3_SECRET_CIPHER, encrypted.second)
            }
        }
        editor.apply()
    }

    fun loadS3Config(): S3Credentials? {
        val endpoint = prefs.getString(KEY_S3_ENDPOINT, null) ?: return null
        val bucket = prefs.getString(KEY_S3_BUCKET, "") ?: ""
        val region = prefs.getString(KEY_S3_REGION, "us-east-1") ?: "us-east-1"
        val objectKey = prefs.getString(KEY_S3_OBJECT_KEY, "keepasskey.kdbx") ?: "keepasskey.kdbx"
        val accessIv = prefs.getString(KEY_S3_ACCESS_KEY_IV, null)
        val accessCipher = prefs.getString(KEY_S3_ACCESS_KEY_CIPHER, null)
        // 兼容旧版本：密文缺失时回落到（历史遗留的）明文键，读出后由下次保存转为密文
        val accessKey = decrypt(accessIv, accessCipher)
            ?: prefs.getString(KEY_S3_ACCESS_KEY, "") ?: ""
        val iv = prefs.getString(KEY_S3_SECRET_IV, null)
        val cipher = prefs.getString(KEY_S3_SECRET_CIPHER, null)
        val secretKey = decrypt(iv, cipher) ?: ""

        return S3Credentials(
            endpoint = endpoint,
            bucket = bucket,
            region = region,
            accessKey = accessKey,
            secretKey = secretKey,
            objectKey = objectKey
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            null
        }
    }

    companion object {
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

        private const val KEY_S3_ENDPOINT = "s3_endpoint"
        private const val KEY_S3_BUCKET = "s3_bucket"
        private const val KEY_S3_REGION = "s3_region"
        private const val KEY_S3_ACCESS_KEY = "s3_access_key"
        private const val KEY_S3_ACCESS_KEY_IV = "s3_access_key_iv"
        private const val KEY_S3_ACCESS_KEY_CIPHER = "s3_access_key_cipher"
        private const val KEY_S3_OBJECT_KEY = "s3_object_key"
        private const val KEY_S3_SECRET_IV = "s3_secret_iv"
        private const val KEY_S3_SECRET_CIPHER = "s3_secret_cipher"
    }
}
