package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.repository.FakeSettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 仅供 JVM 单元测试使用的密钥文件访问假实现（ISSUE-P3-04；ISSUE-P2-460 按库化）。
 *
 * 记忆/清除直接写入 [FakeSettingsRepository] 的**按库**记忆（与生产 [SafKeyFileAccess]
 * 经 SettingsRepository 持久化同一语义），因此断言「指定库名下的 Uri/显示名」即验证了
 * 完整持久化链路；SAF/ContentResolver 交互本身无 JVM 实现，需真机验证。
 */
class FakeKeyFileAccess(
    private val settings: FakeSettingsRepository = FakeSettingsRepository(),
    /** 「记住密钥文件」偏好开关 */
    var rememberEnabled: Boolean = true,
    /** takePersistableUriPermission 是否成功（false = 提供方不支持持久授权） */
    var persistPermissionSucceeds: Boolean = true,
    /** 该 Uri 当前是否仍持有持久化读授权 */
    var permissionValid: Boolean = true,
    /** 读取是否一律失败（模拟文件被删/提供方拒绝） */
    var failRead: Boolean = false
) : KeyFileAccess {

    private val sources = mutableMapOf<String, ByteArray>()
    private val displayNames = mutableMapOf<String, String>()

    /** 已申请的持久化读授权 Uri 序列（断言「偏好关闭时不申请」） */
    val permissionRequests = mutableListOf<String>()

    /** 实际清除过记忆的次数（断言「授权失效降级为未记住」时确已清理记录；空清除不计） */
    var forgetCount: Int = 0
        private set

    fun putSource(uri: String, bytes: ByteArray, displayName: String = DISPLAY_NAME) {
        sources[uri] = bytes.copyOf()
        displayNames[uri] = displayName
    }

    /** 测试注入：直写旧版全局槽（回归用例构造 ISSUE-P2-460 前的历史遗留态） */
    suspend fun putLegacyGlobal(uri: String, displayName: String = DISPLAY_NAME) {
        settings.setLegacyGlobalKeyFile(uri, displayName)
    }

    /** 读取指定库当前按库登记的密钥文件 Uri（空串 = 未登记） */
    suspend fun persistedKeyFileUri(databaseId: String = DEFAULT_DB_ID): String =
        settings.rememberedKeyFileFor(databaseId)?.uri ?: ""

    /** 读取指定库当前按库登记的密钥文件显示名（空串 = 未登记） */
    suspend fun persistedKeyFileName(databaseId: String = DEFAULT_DB_ID): String =
        settings.rememberedKeyFileFor(databaseId)?.displayName ?: ""

    /** 读取旧版全局槽 Uri（空串 = 无残留；断言「按库登记绝不写全局槽」用） */
    suspend fun legacyGlobalUri(): String = settings.getSettings().first().lastKeyFileUri

    override suspend fun isRememberEnabled(): Boolean = rememberEnabled

    override suspend fun read(uri: String): KeyFileReadResult {
        if (failRead) return KeyFileReadResult.Unreadable
        val bytes = sources[uri] ?: return KeyFileReadResult.Unreadable
        if (bytes.isEmpty()) return KeyFileReadResult.Empty
        return KeyFileReadResult.Success(bytes.copyOf(), displayNames[uri] ?: DISPLAY_NAME)
    }

    override suspend fun persistReadPermission(uri: String): Boolean {
        permissionRequests += uri
        return persistPermissionSucceeds
    }

    override suspend fun hasPersistedReadPermission(uri: String): Boolean = permissionValid

    override suspend fun loadRemembered(databaseId: String): RememberedKeyFile? {
        val meta = settings.rememberedKeyFileFor(databaseId) ?: return null
        return RememberedKeyFile(meta.uri, meta.displayName)
    }

    override suspend fun loadLegacyGlobalHint(): RememberedKeyFile? {
        val snapshot = settings.getSettings().first()
        val uri = snapshot.lastKeyFileUri
        return if (uri.isBlank()) null else RememberedKeyFile(uri, snapshot.lastKeyFileName)
    }

    override suspend fun remember(databaseId: String, uri: String, displayName: String) {
        settings.setRememberedKeyFileFor(databaseId, uri, displayName)
    }

    override suspend fun forget(databaseId: String?) {
        val hadPerDb = databaseId != null && settings.rememberedKeyFileFor(databaseId) != null
        val hadLegacy = settings.getSettings().first().lastKeyFileUri.isNotBlank()
        if (databaseId != null) settings.clearRememberedKeyFileFor(databaseId)
        settings.clearRememberedKeyFile()
        if (hadPerDb || hadLegacy) forgetCount++
    }

    companion object {
        /** 假 SAF 文档 Uri（非真实文件；仅用于断言非密钥元数据的记忆链路） */
        const val KEY_FILE_URI = "content://test.docs/keyfile/vault.keyx"

        /** 假密钥文件显示名 */
        const val DISPLAY_NAME = "vault.keyx"

        /** 假密钥文件字节：32 字节裸格式（官方解析梯子第 2 档），绝非真实凭据 */
        val FAKE_KEY_FILE_BYTES = ByteArray(32) { (it * 5 + 1).toByte() }

        /** 测试默认活动库 id（与 [com.keepasskey.app.data.repository.FakeVaultRepository] 的活动库一致） */
        const val DEFAULT_DB_ID = "db_personal"
    }
}
