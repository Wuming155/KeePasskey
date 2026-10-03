package com.keepasskey.app.sync

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云同步协议凭据持久化加密存储库 (Wave 3-E P2-19)。
 * 遵循安全规范：
 * 敏感密码与 AccessKey/SecretKey 经 Keystore AES-256-GCM 硬件加密后落盘于私有 SharedPreferences 中。
 * Wave 15 整改：凭据读写全链路以 CharArray 承载（CharBuffer 直转 UTF-8 字节，全程不经 String），
 * 借用语义由调用方承担用毕清零义务；保存结果如实回传（封印失败返回 false）。
 * 允许在单测中注入恒等/模拟加解密器以测试往返逻辑。
 *
 * ## 按库命名空间（`ISSUE-P2-465`）
 *
 * 全部键（协议、端点、远端路径、密文、S3 时钟偏移）**按当前活动库命名空间化**
 * （键形态与摘要口径见 [SyncCredentialKeys]），命名空间标识由 [ActiveVaultSyncNamespace]
 * 解析——整改前所有库共用一份全局配置，切库后配置页与同步周期仍指向上一个库。
 *
 * - 读写默认走**当前活动库**（设置页表单、同步周期、库列表自动同步判定同源）；
 * - 无会话的写路径可显式指定库（`dbId` 形参）：云端打开导入按即将落盘的本地路径落凭据
 *   （该路径随后即成为库列表登记 ID，解锁后读到的仍是同一个命名空间）；
 * - 无活动库（无会话且库列表无活动项）时退回旧全局键，并保留升级前的存量语义。
 *
 * ## 存量迁移（AC②）
 *
 * 首次在已知活动库语境下读写时，存量全局配置一次性归属该库并被物理删除；
 * 其余库初始为未配置。判定与执行见 [SyncCredentialKeys.adoptLegacyInto]。
 */
@Singleton
class SyncCredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager? = null,
    // 允许为 null 仅用于 JVM 单测注入；生产 DI 恒定注入真实实现
    private val debugLog: DebugLogBuffer? = null,
    // ISSUE-P1-07：凭据销毁必须连带销毁同步缓存中的 KDBX 密文快照。
    // 允许为 null 仅用于 JVM 单测注入；生产 DI 恒定注入真实实现（构造无环：
    // SyncCacheEvictor 仅依赖 ApplicationContext 与调试日志）。
    private val syncCacheEvictor: SyncCacheEvictor? = null,
    // ISSUE-P2-465：活动库命名空间解析的数据源（会话路径标识 / 活动库登记 ID）。
    // 允许为 null 仅用于 JVM 单测注入（与上两个测试缝同范式）——null 时命名空间恒为
    // 「旧全局键」，行为与整改前逐字一致。
    private val databaseSession: DatabaseSession? = null
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

    /** ISSUE-P2-465：活动库 ID 的持久化单点（与库列表共用同一份记录） */
    private val activeIdStore by lazy { ActiveDatabaseIdStore(context) }

    /** ISSUE-P2-465：当前活动库的同步配置命名空间解析（口径见该类 KDoc） */
    private val vaultNamespace by lazy {
        ActiveVaultSyncNamespace(
            filesDir = { context.filesDir },
            sessionPathIdentifier = { databaseSession?.currentPathIdentifier },
            sessionFilePath = { databaseSession?.currentFile?.absolutePath },
            persistedActiveId = { activeIdStore.load() }
        )
    }

    // ISSUE-P3-29：封印 / 解除封印已拆至 SyncCredentialSealer（同模块 internal）；
    // 测试注入钩子仍由本类持有并逐次传入，语义不变。
    private val sealer = SyncCredentialSealer(keystoreManager, debugLog, SYNC_KEY_ALIAS)

    /**
     * ISSUE-P2-285 AC①／AC②：同步凭据封印密钥的**实测**硬件落位等级
     * （`KeyInfo.securityLevel` 探测；密钥尚未生成 / 无 Keystore 时返回 null，
     * 调用方按「非硬件」如实呈现降级文案——与解锁面 `UnlockAuthPolicy` 的
     * SOFTWARE / UNKNOWN 同判口径）。
     */
    fun syncSealSecurityLevel(): KeystoreManager.KeySecurityLevel? =
        keystoreManager?.getKeySecurityLevel(SYNC_KEY_ALIAS)

    /**
     * 命名空间解析（`ISSUE-P2-465`）：[dbId] 非空即显式指定（`idFor` 归一化，不做存量迁移
     * ——该库尚不属任何会话）；为空则取当前活动库，并顺带完成存量全局配置的一次性归属（AC②）。
     */
    private fun resolveScope(dbId: String?): String? =
        dbId?.let { vaultNamespace.idFor(it) }
            ?: vaultNamespace.currentId().also {
                SyncCredentialKeys.adoptLegacyInto(prefs, it, debugLog)
            }

    /** 命名空间键名：`dbId` 为 null（无活动库）时即旧全局键（见 [SyncCredentialKeys]） */
    private fun keyOf(base: String, scope: String?): String =
        SyncCredentialKeys.scoped(base, scope)

    fun saveProvider(provider: CloudSyncProvider, dbId: String? = null) {
        val scope = resolveScope(dbId)
        prefs.edit().putString(keyOf(SyncCredentialKeys.PROVIDER, scope), provider.name).apply()
    }

    fun loadProvider(): CloudSyncProvider {
        val name = prefs.getString(
            keyOf(SyncCredentialKeys.PROVIDER, resolveScope(null)),
            CloudSyncProvider.WEBDAV.name
        )
        return try {
            CloudSyncProvider.valueOf(name ?: CloudSyncProvider.WEBDAV.name)
        } catch (_: Exception) {
            CloudSyncProvider.WEBDAV
        }
    }

    /**
     * Wave 15 整改：密码以 [CharArray] 借用语义提交，本方法内部转换为字节封印后立即擦除调用方数组；
     * 返回保存结果——先封印后落盘（Wave 15：封印失败时整体不 apply，杜绝「URL 已存但凭据未写入」的半写状态）。
     *
     * **空密码 = 保留已保存密码**：改远程路径/端点时表单密码常已被清零，若写入会抹掉已封印口令，
     * 造成「只有刚输入密码才能测连」。仅密码非空时覆盖旧密文。
     *
     * `ISSUE-P2-465`：[dbId] 非空即显式落该库命名空间（云端打开导入的解锁前提交），
     * 为空即落当前活动库。
     */
    fun saveWebDavConfig(
        url: String,
        username: String,
        password: CharArray,
        remotePath: String,
        dbId: String? = null
    ): Boolean {
        // ISSUE-P2-403：非空但全 '0' 的凭据 = 擦除后误存的「擦零指纹」（真机实证：云端打开后
        // 同步 401，设置页预填整串 0）——拒绝封印，绝不把垃圾密文落盘顶掉既有可用凭据
        if (isAllZero(password)) {
            debugLog?.warn(TAG, "拒绝封印 WebDAV 密码：内容为全零串（疑似擦除后误存）")
            return false
        }
        val scope = resolveScope(dbId)
        try {
            // 先封印再落盘：封印失败不得写入任何键（含 URL），避免半写状态
            val passwordIv: String?
            val passwordCipher: String?
            if (password.isNotEmpty()) {
                val encrypted = sealer.encrypt(password, customEncryptor) ?: return false
                passwordIv = encrypted.first
                passwordCipher = encrypted.second
            } else {
                passwordIv = null
                passwordCipher = null
            }
            val editor = prefs.edit()
            if (passwordIv != null && passwordCipher != null) {
                editor.putString(keyOf(SyncCredentialKeys.WEBDAV_PASSWORD_IV, scope), passwordIv)
                editor.putString(keyOf(SyncCredentialKeys.WEBDAV_PASSWORD_CIPHER, scope), passwordCipher)
            }
            // 空密码：不写也不删密文键 ⇒ 保留已保存口令
            editor.putString(keyOf(SyncCredentialKeys.WEBDAV_URL, scope), url)
            editor.putString(keyOf(SyncCredentialKeys.WEBDAV_USERNAME, scope), username)
            editor.putString(keyOf(SyncCredentialKeys.WEBDAV_REMOTE_PATH, scope), remotePath)
            editor.apply()
            return true
        } finally {
            password.fill('0')
        }
    }

    /** Wave 15 整改：读取解密以 [CharArray] 承载（借用语义），调用方用毕立即清零 */
    fun loadWebDavConfig(): WebDavCredentials? {
        val scope = resolveScope(null)
        // Wave 14 证书固定整体移除：旧版本遗留的锁定配置键在此一次性物理清除，
        // 不依赖「用户下次保存配置」才清理（对齐下方旧版明文 AccessKey 的迁移模式）。
        // 该键**从未有过按库形态**（功能已在命名空间化之前整体移除），故恒按基名清除。
        // 用 getString 判空而非 contains()：语义等价，且兼容 JVM 单测的 SharedPreferences 代理桩
        if (prefs.getString(SyncCredentialKeys.WEBDAV_CERT_PINS, null) != null) {
            prefs.edit().remove(SyncCredentialKeys.WEBDAV_CERT_PINS).apply()
        }

        val url = prefs.getString(keyOf(SyncCredentialKeys.WEBDAV_URL, scope), null) ?: return null
        val username = prefs.getString(keyOf(SyncCredentialKeys.WEBDAV_USERNAME, scope), "") ?: ""
        val remotePath = prefs.getString(
            keyOf(SyncCredentialKeys.WEBDAV_REMOTE_PATH, scope),
            WebDavDefaults.DEFAULT_REMOTE_PATH
        ) ?: WebDavDefaults.DEFAULT_REMOTE_PATH
        val iv = prefs.getString(keyOf(SyncCredentialKeys.WEBDAV_PASSWORD_IV, scope), null)
        val cipher = prefs.getString(keyOf(SyncCredentialKeys.WEBDAV_PASSWORD_CIPHER, scope), null)

        // P2 整改 fail-closed：已存在密文却解除封印失败（密钥被生物识别变更作废 / 密文损坏）
        // 时，原实现会把 password 退化为空串返回。上层据此会认为「用户主动清空了密码」，
        // 保存配置时进而把空密码写回云端，造成凭据不可逆丢失。现一律返回 null，交由 UI 显式重录。
        val password = if (isCipherTextPresent(iv, cipher)) {
            sealer.decrypt(iv, cipher, customDecryptor) ?: return null
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
     * 返回保存结果（语义同 [saveWebDavConfig]：**空密钥 = 保留已保存密钥**）。
     */
    fun saveS3Config(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        objectKey: String,
        usePathStyle: Boolean = false,
        dbId: String? = null
    ): Boolean {
        // ISSUE-P2-403：全零串拒绝封印（语义同 saveWebDavConfig）
        if (isAllZero(accessKey) || isAllZero(secretKey)) {
            debugLog?.warn(TAG, "拒绝封印 S3 密钥：内容为全零串（疑似擦除后误存）")
            return false
        }
        val scope = resolveScope(dbId)
        try {
            // 先封印再落盘：失败不得写入任何键
            val accessIv: String?
            val accessCipher: String?
            if (accessKey.isNotEmpty()) {
                val encrypted = sealer.encrypt(accessKey, customEncryptor) ?: return false
                accessIv = encrypted.first
                accessCipher = encrypted.second
            } else {
                accessIv = null
                accessCipher = null
            }
            val secretIv: String?
            val secretCipher: String?
            if (secretKey.isNotEmpty()) {
                val encrypted = sealer.encrypt(secretKey, customEncryptor) ?: return false
                secretIv = encrypted.first
                secretCipher = encrypted.second
            } else {
                secretIv = null
                secretCipher = null
            }
            val editor = prefs.edit()
            if (accessIv != null && accessCipher != null) {
                editor.putString(keyOf(SyncCredentialKeys.S3_ACCESS_KEY_IV, scope), accessIv)
                editor.putString(keyOf(SyncCredentialKeys.S3_ACCESS_KEY_CIPHER, scope), accessCipher)
            }
            if (secretIv != null && secretCipher != null) {
                editor.putString(keyOf(SyncCredentialKeys.S3_SECRET_IV, scope), secretIv)
                editor.putString(keyOf(SyncCredentialKeys.S3_SECRET_CIPHER, scope), secretCipher)
            }
            // 空密钥：不写也不删密文键 ⇒ 保留已保存密钥
            editor.putString(keyOf(SyncCredentialKeys.S3_ENDPOINT, scope), endpoint)
            editor.putString(keyOf(SyncCredentialKeys.S3_BUCKET, scope), bucket)
            editor.putString(keyOf(SyncCredentialKeys.S3_REGION, scope), region)
            editor.putString(keyOf(SyncCredentialKeys.S3_OBJECT_KEY, scope), objectKey)
            editor.putBoolean(keyOf(SyncCredentialKeys.S3_USE_PATH_STYLE, scope), usePathStyle)
            // TASK-45：端点/桶变更时钟偏移作废（只作废**本库**的偏移）
            editor.remove(keyOf(SyncCredentialKeys.S3_CLOCK_OFFSET, scope))
            editor.apply()
            return true
        } finally {
            accessKey.fill('0')
            secretKey.fill('0')
        }
    }

    /** Wave 15 整改：读取解密以 [CharArray] 承载（借用语义），调用方用毕立即清零 */
    fun loadS3Config(): S3Credentials? {
        val scope = resolveScope(null)
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
                sealer.decrypt(accessIv, accessCipher, customDecryptor) ?: return null
            !legacyPlainAccessKey.isNullOrBlank() -> legacyPlainAccessKey.toCharArray()
            else -> CharArray(0)
        }
        if (accessIv.isNullOrBlank() && !legacyPlainAccessKey.isNullOrBlank()) {
            // 旧版明文 AccessKey 残留清除：读取后立即转加密落盘并物理删除明文键，
            // 不再依赖「用户下次保存配置」才迁移
            val migrated = sealer.encrypt(accessKey, customEncryptor)
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
            sealer.decrypt(iv, cipher, customDecryptor) ?: return null
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

    /**
     * 清空**全部库**的同步凭据配置。
     *
     * ISSUE-P1-07：同步关系终止（换服务器、退出同步、用户主动清空）后，
     * 缓存中的两份完整 KDBX 密文快照若继续驻留，即成为无主的离线爆破素材。
     * 故凭据销毁与缓存销毁必须同批完成。
     *
     * `ISSUE-P2-465`：本方法刻意保持「整文件清空」语义（含各库命名空间与绑定登记）——
     * 它服务的是「终止全部同步关系」这一类整体动作；**单库**清理走 [clearFor]。
     */
    fun clear() {
        prefs.edit().clear().apply()
        syncCacheEvictor?.evictAll()
    }

    /**
     * `ISSUE-P2-465`：清除**该库**的同步配置（删库时调用，与按库密钥文件副本 / 记忆同批）。
     *
     * 只删该库命名空间下的键：不清缓存（同步缓存的物理键是根分组 UUID，与库文件登记 ID
     * 不同源，无法据此定位），也不清绑定登记（`SyncVaultBindingStore` 的值是根分组 UUID，
     * 删库后无从反查）——后者由既有「绑定不符即拦截」闸门兜底：若同一路径重建新库，
     * 同步会在任何网络写之前以 `VaultBindingMismatch` 中止，绝不会静默错传。
     *
     * [dbId] 与读写面同口径：裸文件名（沙盒扫描条目）归一为沙盒内绝对路径。
     */
    fun clearFor(dbId: String) {
        val scope = vaultNamespace.idFor(dbId) ?: return
        SyncCredentialKeys.removeScoped(prefs, scope)
    }

    /**
     * TASK-45（P2-14）：S3 服务端时钟偏移持久化。
     *
     * 偏移量 = 服务端时间 - 本地时间（毫秒），非敏感数据（不含任何凭据/密钥分量），
     * 与 S3 凭据同文件落盘即可（「随凭据落盘一致」防篡改面见 AGENTS.md 敏感数据铁律——
     * 此处仅为偏移常量，被篡改最坏结果是多一次 RequestTimeTooSkewed 自愈重试，无数据泄露）。
     * 未探测时返回 0（fail-closed 语义：不补偿、以本地时间签名）。
     */
    fun loadS3ClockOffsetMillis(): Long =
        prefs.getLong(keyOf(SyncCredentialKeys.S3_CLOCK_OFFSET, resolveScope(null)), 0L)

    /** TASK-45：保存最新探测的 S3 时钟偏移（由 S3SyncProvider 刷新回调驱动，尽力而为） */
    fun saveS3ClockOffsetMillis(offsetMillis: Long) {
        val key = keyOf(SyncCredentialKeys.S3_CLOCK_OFFSET, resolveScope(null))
        prefs.edit().putLong(key, offsetMillis).apply()
    }

    /** 判定 iv + 密文二元组是否均已落盘（存在密文才意味着「曾成功封印过」，可用于区分空值与解封失败） */
    private fun isCipherTextPresent(ivBase64: String?, cipherBase64: String?): Boolean =
        !ivBase64.isNullOrBlank() && !cipherBase64.isNullOrBlank()

    /** ISSUE-P2-403：非空且全 '0'（借用语义擦除指纹）；空串不算（空 = 保留既有语义） */
    private fun isAllZero(chars: CharArray): Boolean =
        chars.isNotEmpty() && chars.all { it == '0' }

    private val TAG = "SyncCredentialsStore"

    companion object {
        const val PREFS_NAME = "sync_credentials_prefs"
        const val SYNC_KEY_ALIAS = "com.keepasskey.sync_credential_key"
    }
}
