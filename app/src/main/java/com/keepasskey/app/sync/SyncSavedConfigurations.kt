package com.keepasskey.app.sync

import android.content.SharedPreferences
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider

/** 密封配置回执的捕获、解封与按修订条件写回；调用方持 Store 监视器固定提交边界。 */
internal class SyncSavedConfigurations(
    private val prefs: SharedPreferences,
    private val keyOf: (String, String?) -> String,
    private val encryptLegacy: (CharArray) -> Pair<String, String>?,
    private val decryptSecret: (String?, String?) -> CharArray?,
    private val identity: (Array<out String>) -> String
) {
    /** 解封指定提交快照；返回的凭据 CharArray 由调用方以 finally 负责擦除。 */
    internal fun loadWebDavConfig(snapshot: SyncProviderConfigSnapshot): WebDavCredentials? {
        if (snapshot.provider != CloudSyncProvider.WEBDAV) return null
        val url = snapshot.webDavUrl ?: return null
        val iv = snapshot.webDavPasswordIv
        val cipher = snapshot.webDavPasswordCipher
        val password = when {
            iv.isNullOrBlank() && cipher.isNullOrBlank() -> CharArray(0)
            isCipherTextPresent(iv, cipher) -> decryptSecret(iv, cipher) ?: return null
            else -> return null
        }
        return WebDavCredentials(
            url = url,
            username = snapshot.webDavUsername.orEmpty(),
            password = password,
            remotePath = snapshot.webDavRemotePath.orEmpty()
        )
    }

    /** 解封指定提交快照；若第二项凭据解封失败，首项也立即擦除。 */
    internal fun loadS3Config(snapshot: SyncProviderConfigSnapshot): S3Credentials? {
        if (snapshot.provider != CloudSyncProvider.S3_COMPATIBLE) return null
        val endpoint = snapshot.s3Endpoint ?: return null
        val accessKey = decryptSnapshotSecret(snapshot.s3AccessKeyIv, snapshot.s3AccessKeyCipher) ?: return null
        val secretKey = decryptSnapshotSecret(snapshot.s3SecretKeyIv, snapshot.s3SecretKeyCipher)
            ?: run {
                accessKey.fill('0')
                return null
            }
        return S3Credentials(
            endpoint = endpoint,
            bucket = snapshot.s3Bucket.orEmpty(),
            region = snapshot.s3Region.orEmpty(),
            accessKey = accessKey,
            secretKey = secretKey,
            objectKey = snapshot.s3ObjectKey.orEmpty(),
            usePathStyle = snapshot.s3UsePathStyle
        )
    }

    private fun decryptSnapshotSecret(iv: String?, cipher: String?): CharArray? = when {
        iv.isNullOrBlank() && cipher.isNullOrBlank() -> CharArray(0)
        isCipherTextPresent(iv, cipher) -> decryptSecret(iv, cipher)
        else -> null
    }

    /** 必须在持有本存储器监视器时调用，使保存与快照捕获处于同一提交边界。 */
    fun captureConfigSnapshot(provider: CloudSyncProvider, scope: String?): SyncProviderConfigSnapshot? {
        val values = prefs.all
        fun string(base: String, default: String? = null): String? =
            values[keyOf(base, scope)] as? String ?: default
        fun boolean(base: String, default: Boolean = false): Boolean =
            values[keyOf(base, scope)] as? Boolean ?: default
        fun long(base: String, default: Long = 0L): Long =
            values[keyOf(base, scope)] as? Long ?: default

        return when (provider) {
            CloudSyncProvider.WEBDAV -> {
                val url = string(SyncCredentialKeys.WEBDAV_URL) ?: return null
                val username = string(SyncCredentialKeys.WEBDAV_USERNAME).orEmpty()
                val remotePath = string(
                    SyncCredentialKeys.WEBDAV_REMOTE_PATH,
                    WebDavDefaults.DEFAULT_REMOTE_PATH
                ).orEmpty()
                val iv = string(SyncCredentialKeys.WEBDAV_PASSWORD_IV)
                val cipher = string(SyncCredentialKeys.WEBDAV_PASSWORD_CIPHER)
                if (!validCipherPair(iv, cipher)) return null
                val revision = targetIdentity(
                    "WEBDAV", url, username, remotePath, iv.orEmpty(), cipher.orEmpty()
                )
                SyncProviderConfigSnapshot(
                    provider = provider,
                    revision = revision,
                    scope = scope,
                    webDavUrl = url,
                    webDavUsername = username,
                    webDavRemotePath = remotePath,
                    webDavPasswordIv = iv,
                    webDavPasswordCipher = cipher
                )
            }
            CloudSyncProvider.S3_COMPATIBLE -> {
                val endpoint = string(SyncCredentialKeys.S3_ENDPOINT) ?: return null
                val bucket = string(SyncCredentialKeys.S3_BUCKET).orEmpty()
                var accessIv = string(SyncCredentialKeys.S3_ACCESS_KEY_IV)
                var accessCipher = string(SyncCredentialKeys.S3_ACCESS_KEY_CIPHER)
                val legacyAccess = string(SyncCredentialKeys.S3_ACCESS_KEY)
                if (!accessIv.isNullOrBlank() || !accessCipher.isNullOrBlank()) {
                    if (!validCipherPair(accessIv, accessCipher)) return null
                } else if (!legacyAccess.isNullOrBlank()) {
                    val legacyChars = legacyAccess.toCharArray()
                    val encrypted = try {
                        encryptLegacy(legacyChars)
                    } finally {
                        legacyChars.fill('0')
                    }
                    if (encrypted == null) return null
                    accessIv = encrypted.first
                    accessCipher = encrypted.second
                    prefs.edit()
                        .putString(keyOf(SyncCredentialKeys.S3_ACCESS_KEY_IV, scope), accessIv)
                        .putString(keyOf(SyncCredentialKeys.S3_ACCESS_KEY_CIPHER, scope), accessCipher)
                        .remove(keyOf(SyncCredentialKeys.S3_ACCESS_KEY, scope))
                        .apply()
                }
                val secretIv = string(SyncCredentialKeys.S3_SECRET_IV)
                val secretCipher = string(SyncCredentialKeys.S3_SECRET_CIPHER)
                if (!validCipherPair(secretIv, secretCipher)) return null
                val region = string(SyncCredentialKeys.S3_REGION, "us-east-1").orEmpty()
                val objectKey = string(SyncCredentialKeys.S3_OBJECT_KEY, "keepasskey.kdbx").orEmpty()
                val usePathStyle = boolean(SyncCredentialKeys.S3_USE_PATH_STYLE)
                val offset = long(SyncCredentialKeys.S3_CLOCK_OFFSET)
                val revision = targetIdentity(
                    "S3", endpoint, bucket, region, objectKey, usePathStyle.toString(),
                    accessIv.orEmpty(), accessCipher.orEmpty(), secretIv.orEmpty(), secretCipher.orEmpty()
                )
                SyncProviderConfigSnapshot(
                    provider = provider,
                    revision = revision,
                    scope = scope,
                    s3Endpoint = endpoint,
                    s3Bucket = bucket,
                    s3Region = region,
                    s3ObjectKey = objectKey,
                    s3UsePathStyle = usePathStyle,
                    s3AccessKeyIv = accessIv,
                    s3AccessKeyCipher = accessCipher,
                    s3SecretKeyIv = secretIv,
                    s3SecretKeyCipher = secretCipher,
                    s3ClockOffsetMillis = offset
                )
            }
        }
    }

    private fun validCipherPair(iv: String?, cipher: String?): Boolean =
        (iv.isNullOrBlank() && cipher.isNullOrBlank()) || isCipherTextPresent(iv, cipher)

    /** 只在该快照仍是指定库当前 S3 配置时保存服务端时钟偏移，避免迟到响应污染新配置。 */
    @Synchronized
    internal fun saveS3ClockOffsetForSnapshot(snapshot: SyncProviderConfigSnapshot, offsetMillis: Long) {
        if (snapshot.provider != CloudSyncProvider.S3_COMPATIBLE) return
        val current = captureConfigSnapshot(snapshot.provider, snapshot.scope) ?: return
        if (current.revision != snapshot.revision) return
        prefs.edit()
            .putLong(keyOf(SyncCredentialKeys.S3_CLOCK_OFFSET, snapshot.scope), offsetMillis)
            .apply()
    }

    /** Wave 15 整改：读取解密以 [CharArray] 承载（借用语义），调用方用毕立即清零 */
    fun loadCurrentS3(scope: String?): S3Credentials? {
        val endpoint = prefs.getString(keyOf(SyncCredentialKeys.S3_ENDPOINT, scope), null) ?: return null
        val bucket = prefs.getString(keyOf(SyncCredentialKeys.S3_BUCKET, scope), "") ?: ""
        val region = prefs.getString(keyOf(SyncCredentialKeys.S3_REGION, scope), "us-east-1") ?: "us-east-1"
        val objectKey = prefs.getString(keyOf(SyncCredentialKeys.S3_OBJECT_KEY, scope), "keepasskey.kdbx")
            ?: "keepasskey.kdbx"
        val usePathStyle = prefs.getBoolean(keyOf(SyncCredentialKeys.S3_USE_PATH_STYLE, scope), false)
        val accessIv = prefs.getString(keyOf(SyncCredentialKeys.S3_ACCESS_KEY_IV, scope), null)
        val accessCipher = prefs.getString(keyOf(SyncCredentialKeys.S3_ACCESS_KEY_CIPHER, scope), null)

        val legacyPlainAccessKey = prefs.getString(keyOf(SyncCredentialKeys.S3_ACCESS_KEY, scope), null)

        // P2 整改 fail-closed：存在密文却解封失败时不再回退为「空 / 明文」，
        // 直接返回 null 避免上层把失效凭据当作用户主动清空写回云端。
        val accessKey = when {
            isCipherTextPresent(accessIv, accessCipher) ->
                decryptSecret(accessIv, accessCipher) ?: return null
            !legacyPlainAccessKey.isNullOrBlank() -> legacyPlainAccessKey.toCharArray()
            else -> CharArray(0)
        }
        if (accessIv.isNullOrBlank() && !legacyPlainAccessKey.isNullOrBlank()) {
            // 旧版明文 AccessKey 残留清除：读取后立即转加密落盘并物理删除明文键，
            // 不再依赖「用户下次保存配置」才迁移
            val migrated = encryptLegacy(accessKey)
            if (migrated != null) {
                prefs.edit()
                    .putString(keyOf(SyncCredentialKeys.S3_ACCESS_KEY_IV, scope), migrated.first)
                    .putString(keyOf(SyncCredentialKeys.S3_ACCESS_KEY_CIPHER, scope), migrated.second)
                    .remove(keyOf(SyncCredentialKeys.S3_ACCESS_KEY, scope))
                    .apply()
            }
        }

        val iv = prefs.getString(keyOf(SyncCredentialKeys.S3_SECRET_IV, scope), null)
        val cipher = prefs.getString(keyOf(SyncCredentialKeys.S3_SECRET_CIPHER, scope), null)
        // P2 整改 fail-closed：同上，SecretKey 存在密文却解封失败一律让上层感知
        val secretKey = if (isCipherTextPresent(iv, cipher)) {
            decryptSecret(iv, cipher) ?: run { accessKey.fill('0'); return null }
        } else {
            CharArray(0)
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

    private fun targetIdentity(vararg fields: String): String = identity(fields)
    private fun isCipherTextPresent(iv: String?, cipher: String?): Boolean = !iv.isNullOrBlank() && !cipher.isNullOrBlank()
}
