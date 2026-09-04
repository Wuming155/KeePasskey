package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import kotlinx.coroutines.flow.Flow

/**
 * 密码库数据仓库接口，遵循谷歌官方 Recommended app architecture 数据层规范。
 * 屏蔽上层 UI/ViewModel 对具体存储技术（KDBX / 内存 / Room）的依赖。
 */
interface VaultRepository {
    /**
     * 获取所有已知密码库列表
     */
    fun getDatabases(): Flow<List<VaultDatabaseInfo>>

    /**
     * 切换当前选中的活动密码库
     */
    suspend fun selectDatabase(id: String)

    /**
     * 解锁当前选中的活动密码库
     */
    suspend fun unlockActiveDatabase(passwordChars: CharArray): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 锁定当前密码库，清空内存凭据与活动树
     */
    suspend fun lockDatabase()

    /**
     * 检查当前密码库是否处于锁定状态
     */
    fun isLocked(): Boolean

    /**
     * 创建新密码库
     */
    suspend fun createDatabase(
        name: String,
        masterPassword: String,
        keyFile: Boolean,
        preset: String
    )

    /**
     * 移除密码库关联
     */
    suspend fun removeDatabase(id: String)

    /**
     * 导入并打开已有 KDBX 数据库 (支持本地、WebDAV、S3 来源)
     */
    suspend fun importExternalDatabase(name: String, path: String, syncType: String = "本地设备存储")

    /**
     * 获取全部群组/文件夹的实时响应式流
     */
    fun getGroups(): Flow<List<VaultGroup>>

    /**
     * 保存或更新群组/文件夹
     */
    suspend fun saveGroup(group: VaultGroup)

    /**
     * 删除群组/文件夹
     */
    suspend fun deleteGroup(id: String)

    /**
     * 获取全部凭据条目的实时响应式流
     */
    fun getEntries(): Flow<List<UiVaultEntry>>

    /**
     * 根据 ID 获取单条凭据的响应式流
     */
    fun getEntry(id: String): Flow<UiVaultEntry?>

    /**
     * 保存或更新凭据条目
     */
    suspend fun saveEntry(entry: UiVaultEntry)

    /**
     * 删除凭据条目（移至回收站或彻底删除）
     */
    suspend fun deleteEntry(id: String)

    /**
     * 还原处于回收站中的凭据条目
     */
    suspend fun restoreEntry(id: String)

    /**
     * 清空回收站
     */
    suspend fun emptyRecycleBin()

    /**
     * 批量移动凭据条目至目标分组
     */
    suspend fun batchMoveEntries(entryIds: Set<String>, targetGroupId: String?)

    /**
     * 批量删除凭据条目（移至回收站）
     */
    suspend fun batchDeleteEntries(entryIds: Set<String>)
}
