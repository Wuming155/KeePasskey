package com.keepasskey.app.sync

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 同步凭据模型。
 *
 * Wave 15 整改：[password] 以 [CharArray] 承载（借用语义：调用方用毕立即清零，
 * 绝不以 String 长期驻留）。
 */
class WebDavCredentials(
    val url: String,
    val username: String,
    val password: CharArray,
    val remotePath: String
)

/**
 * S3 兼容协议同步凭据模型。
 *
 * Wave 15 整改：[accessKey] / [secretKey] 以 [CharArray] 承载（借用语义：
 * 调用方用毕立即清零，绝不以 String 长期驻留）。
 */
class S3Credentials(
    val endpoint: String,
    val bucket: String,
    val region: String,
    val accessKey: CharArray,
    val secretKey: CharArray,
    val objectKey: String,
    /** 寻址风格：false = virtual-host（AWS 等默认），true = path 风格（Cloudflare R2、IP 直连端点等） */
    val usePathStyle: Boolean = false
)

/**
 * 云同步协议凭据持久化加密存储库 (Wave 3-E P2-19)。
 * 遵循安全规范：
 * 敏感密码与 AccessKey/SecretKey 经 Keystore AES-256-GCM 硬件加密后落盘于私有 SharedPreferences 中。
 * Wave 15 整改：凭据读写全链路以 CharArray 承载（CharBuffer 直转 UTF-8 字节，全程不经 String），
 * 借用语义由调用方承担用毕清零义务；保存结果如实回传（封印失败返回 false）。
 * 允许在单测中注入恒等/模拟加解密器以测试往返逻辑。
 */
@Singleton
class SyncCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager? = null,
    // 允许为 null 仅用于 JVM 单测注入；生产 DI 恒定注入真实实现
    private val debugLog: DebugLogBuffer? = null
) {
    // TASK-14 整改（P2-21）：生产类不得暴露 public 可写加解密钩子——任何持有实例的
    // 代码都可静默替换封印算法，形成凭据泄露面。现以 @VisibleForTesting + internal
    // 双重收窄：仅本模块单元测试（同一编译单元）可注入模拟加解密闭包，
    // 生产 DI 与外部调用方不可见、不可写。
    @VisibleForTesting
    internal var customEncryptor: ((ByteArray) -> Pair<ByteArray, ByteArray>)? = null // plaintext -> (iv, ciphertext)

    @VisibleForTesting
    internal var customDecryptor: ((ByteArray, ByteArray) -> ByteArray)? = null // (iv, ciphertext) -> plaintext

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

    /**
     * Wave 15 整改：密码以 [CharArray] 借用语义提交，本方法内部转换为字节封印后立即擦除调用方数组；
     * 返回保存结果——先封印后落盘（Wave 15：封印失败时整体不 apply，杜绝「URL 已存但凭据未写入」的半写状态）。
     */
    fun saveWebDavConfig(
        url: String,
        username: String,
        password: CharArray,
        remotePath: String
    ): Boolean {
        try {
            // 先封印：失败则整体不落盘（fail-fast）
            val encrypted = if (password.isNotEmpty()) encrypt(password) ?: return false else null
            val editor = prefs.edit()
            editor.putString(KEY_WEBDAV_URL, url)
            editor.putString(KEY_WEBDAV_USERNAME, username)
            editor.putString(KEY_WEBDAV_REMOTE_PATH, remotePath)
            if (encrypted != null) {
                editor.putString(KEY_WEBDAV_PASSWORD_IV, encrypted.first)
                editor.putString(KEY_WEBDAV_PASSWORD_CIPHER, encrypted.second)
            } else {
                // 显式清除旧密文，避免「旧密码在清空后仍继续生效」的隐式行为
                editor.remove(KEY_WEBDAV_PASSWORD_IV)
                editor.remove(KEY_WEBDAV_PASSWORD_CIPHER)
            }
            editor.apply()
            return true
        } finally {
            // 借用语义：任何结果路径（成功/封印失败）均擦除调用方密码数组
            // （对齐 saveS3Config 的 finally 擦除契约与 Wave 15 文档声明）
            password.fill('0')
        }
    }

    /** Wave 15 整改：读取解密以 [CharArray] 承载（借用语义），调用方用毕立即清零 */
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
            CharArray(0)
        }

        return WebDavCredentials(
            url = url,
            username = username,
            password = password,
            remotePath = remotePath
        )
    }

    /**
     * Wave 15 整改：AccessKey/SecretKey 以 [CharArray] 借用语义提交，封印后立即擦除调用方数组；
     * 返回保存结果——先封印后落盘（封印失败时整体不 apply，语义同 [saveWebDavConfig]）。
     */
    fun saveS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        objectKey: String,
        usePathStyle: Boolean = false
    ): Boolean {
        try {
            // 先封印：任一失败则整体不落盘（fail-fast）
            // L4 整改：AccessKey 与 SecretKey 同样经 Keystore AES-256-GCM 加密落盘，不再明文存储
            val encryptedAccessKey = if (accessKey.isNotEmpty()) encrypt(accessKey) ?: return false else null
            val encryptedSecretKey = if (secretKey.isNotEmpty()) encrypt(secretKey) ?: return false else null

            val editor = prefs.edit()
            editor.putString(KEY_S3_ENDPOINT, endpoint)
            editor.putString(KEY_S3_BUCKET, bucket)
            editor.putString(KEY_S3_REGION, region)
            editor.putString(KEY_S3_OBJECT_KEY, objectKey)
            editor.putBoolean(KEY_S3_USE_PATH_STYLE, usePathStyle)
            // TASK-45：凭据变更 = 换端点/换桶，旧端点探测的时钟偏移立即作废（置未知，
            // 下次同步 fail-closed 以本地时间签名并据首个响应 Date 头重新学习）
            editor.remove(KEY_S3_CLOCK_OFFSET)
            if (encryptedAccessKey != null) {
                editor.putString(KEY_S3_ACCESS_KEY_IV, encryptedAccessKey.first)
                editor.putString(KEY_S3_ACCESS_KEY_CIPHER, encryptedAccessKey.second)
            } else {
                editor.remove(KEY_S3_ACCESS_KEY_IV)
                editor.remove(KEY_S3_ACCESS_KEY_CIPHER)
            }
            if (encryptedSecretKey != null) {
                editor.putString(KEY_S3_SECRET_IV, encryptedSecretKey.first)
                editor.putString(KEY_S3_SECRET_CIPHER, encryptedSecretKey.second)
            } else {
                editor.remove(KEY_S3_SECRET_IV)
                editor.remove(KEY_S3_SECRET_CIPHER)
            }
            editor.apply()
            return true
        } finally {
            // 借用语义：任何结果路径（成功/封印失败）均擦除调用方密钥数组
            accessKey.fill('0')
            secretKey.fill('0')
        }
    }

    /** Wave 15 整改：读取解密以 [CharArray] 承载（借用语义），调用方用毕立即清零 */
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
            !legacyPlainAccessKey.isNullOrBlank() -> legacyPlainAccessKey.toCharArray()
            else -> CharArray(0)
        }
        if (accessIv.isNullOrBlank() && !legacyPlainAccessKey.isNullOrBlank()) {
            // 旧版明文 AccessKey 残留清除：读取后立即转加密落盘并物理删除明文键，
            // 不再依赖「用户下次保存配置」才迁移
            val migrated = encrypt(accessKey)
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

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * TASK-45（P2-14）：S3 服务端时钟偏移持久化。
     *
     * 偏移量 = 服务端时间 - 本地时间（毫秒），非敏感数据（不含任何凭据/密钥分量），
     * 与 S3 凭据同文件落盘即可（「随凭据落盘一致」防篡改面见 AGENTS.md 敏感数据铁律——
     * 此处仅为偏移常量，被篡改最坏结果是多一次 RequestTimeTooSkewed 自愈重试，无数据泄露）。
     * 未探测时返回 0（fail-closed 语义：不补偿、以本地时间签名）。
     */
    fun loadS3ClockOffsetMillis(): Long = prefs.getLong(KEY_S3_CLOCK_OFFSET, 0L)

    /** TASK-45：保存最新探测的 S3 时钟偏移（由 S3SyncProvider 刷新回调驱动，尽力而为） */
    fun saveS3ClockOffsetMillis(offsetMillis: Long) {
        prefs.edit().putLong(KEY_S3_CLOCK_OFFSET, offsetMillis).apply()
    }

    /**
     * Wave 15 整改：封印输入以 [CharArray] 承载，经 CharBuffer 直转 UTF-8 字节
     * （对齐 Wave 11 H1 手法），明文字节在封印完成后立即擦除，全程不经 String。
     *
     * ISSUE-P1-06 安全取舍声明（requireUserAuth = false）：
     * 同步凭据封印密钥 [SYNC_KEY_ALIAS] 不绑定用户认证——这是**有意为之的架构决策**：
     * - **必要性**：后台周期同步（WorkManager）与冷启动自动同步需在设备锁屏态执行，
     *   若密钥要求 per-operation 生物认证，则锁屏期间无法解封凭据，同步功能彻底瘫痪；
     * - **风险**：进程内任意代码路径（含被注入的恶意线程）可无认证解封凭据；
     * - **缓解措施**：
     *   1. 凭据解封后以 CharArray 承载，借用语义要求调用方用毕立即 fill('0') 擦除；
     *   2. S3 凭据在同步周期结束后经 [S3SyncProvider.clearCredentials] 显式清零；
     *   3. SigV4 派生链（signingKey/kSecret/kDate/kRegion/kService）全程 finally 擦除；
     *   4. UI 层（CloudSyncScreen ZeroKnowledgeCard）向用户明示此安全取舍；
     * - **替代方案评估**：改为 requireUserAuth=true + 短时授权窗口（如 30s）会导致
     *   后台同步频繁弹出 BiometricPrompt，用户体验不可接受；当前方案在「可用性」与
     *   「安全性」间取得平衡，凭据暴露面已从「String 不可变驻留」收窄至「CharArray 可控生命周期」。
     */
    private fun encrypt(chars: CharArray): Pair<String, String>? {
        if (chars.isEmpty()) return null
        val bytes = chars.toUtf8Bytes()
        return try {
            val (iv, cipherBytes) = customEncryptor?.invoke(bytes) ?: run {
                val km = keystoreManager ?: return null
                // ISSUE-P1-06：requireUserAuth=false 为有意决策（见方法 KDoc 安全取舍声明）
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
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Wave 15 整改：解除封印以 [CharArray] 承载（UTF-8 直解码，全程不经 String），
     * 中间字节用毕立即擦除。调用方对返回数组承担用毕清零义务（借用语义）。
     */
    private fun decrypt(ivBase64: String?, cipherBase64: String?): CharArray? {
        if (ivBase64.isNullOrBlank() || cipherBase64.isNullOrBlank()) return null
        var decryptedBytes: ByteArray? = null
        return try {
            val iv = Base64.getDecoder().decode(ivBase64)
            val cipherBytes = Base64.getDecoder().decode(cipherBase64)
            decryptedBytes = customDecryptor?.invoke(iv, cipherBytes) ?: run {
                val km = keystoreManager ?: return null
                val key = km.getOrCreateKey(SYNC_KEY_ALIAS, requireUserAuth = false)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                cipher.init(Cipher.DECRYPT_MODE, key, spec)
                cipher.doFinal(cipherBytes)
            }
            decryptedBytes.toUtf8Chars()
        } catch (e: Exception) {
            // P2 整改：同上，解除封印失败不得静默。返回 null 由调用方按 fail-closed 处理，
            // 绝不退化为空字符串被上层误判为「用户主动清空了密码」。
            debugLog?.warn(TAG, "同步凭据解除封印失败: ${e.javaClass.simpleName}")
            null
        } finally {
            decryptedBytes?.fill(0)
        }
    }

    /** CharArray → UTF-8 字节（CharBuffer 直转，缓冲区底层副本尽力擦除，不经 String） */
    private fun CharArray.toUtf8Bytes(): ByteArray {
        val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(this))
        return try {
            val bytes = ByteArray(bb.remaining())
            bb.get(bytes)
            bytes
        } finally {
            if (bb.hasArray()) bb.array().fill(0)
        }
    }

    /** UTF-8 字节 → CharArray（ByteBuffer 直解码，缓冲区底层副本尽力擦除，不经 String） */
    private fun ByteArray.toUtf8Chars(): CharArray {
        val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(this))
        return try {
            val chars = CharArray(cb.remaining())
            cb.get(chars)
            chars
        } finally {
            if (cb.hasArray()) cb.array().fill('0')
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
        private const val KEY_S3_CLOCK_OFFSET = "s3_clock_offset_millis"
    }
}
