package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import kotlinx.coroutines.flow.Flow

/**
 * 密码库数据仓库接口，遵循谷歌官方 Recommended app architecture 数据层规范。
 * 屏蔽上层 UI/ViewModel 对具体存储技术（KDBX / 内存 / Room）的依赖。
 */
interface VaultRepository {
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
     * 删除凭据条目
     */
    suspend fun deleteEntry(id: String)
}
