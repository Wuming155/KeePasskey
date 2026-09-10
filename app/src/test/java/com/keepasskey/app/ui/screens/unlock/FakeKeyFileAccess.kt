package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.app.data.repository.FakeSettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 仅供 JVM 单元测试使用的密钥文件访问假实现（ISSUE-P3-04）。
 *
 * 记忆/清除直接写入 [FakeSettingsRepository]（与生产 [SafKeyFileAccess] 经
 * SettingsRepository 持久化同一语义），因此断言「偏好中的 Uri/显示名」即验证了
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

    /** 清除记忆中元数据的次数（断言「授权失效降级为未记住」时确已清理记录） */
    var forgetCount: Int = 0
        private set

    fun putSource(uri: String, bytes: ByteArray, displayName: String = DISPLAY_NAME) {
        sources[uri] = bytes.copyOf()
        displayNames[uri] = displayName
    }

    /** 读取当前持久化的密钥文件 Uri（空串 = 未记住） */
    suspend fun persistedKeyFileUri(): String = settings.getSettings().first().lastKeyFileUri

    /** 读取当前持久化的密钥文件显示名（空串 = 未记住） */
    suspend fun persistedKeyFileName(): String = settings.getSettings().first().lastKeyFileName

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

    override suspend fun loadRemembered(): RememberedKeyFile? {
        val uri = persistedKeyFileUri()
        return if (uri.isBlank()) null else RememberedKeyFile(uri, persistedKeyFileName())
    }

    override suspend fun remember(uri: String, displayName: String) {
        settings.setRememberedKeyFile(uri, displayName)
    }

    override suspend fun forget() {
        forgetCount++
        settings.clearRememberedKeyFile()
    }

    companion object {
        /** 假 SAF 文档 Uri（非真实文件；仅用于断言非密钥元数据的记忆链路） */
        const val KEY_FILE_URI = "content://test.docs/keyfile/vault.keyx"

        /** 假密钥文件显示名 */
        const val DISPLAY_NAME = "vault.keyx"

        /** 假密钥文件字节：32 字节裸格式（官方解析梯子第 2 档），绝非真实凭据 */
        val FAKE_KEY_FILE_BYTES = ByteArray(32) { (it * 5 + 1).toByte() }
    }
}
