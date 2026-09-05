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
     * 保存或更新凭据条目。
     * [passwordChars] 非空时写入新密码；为 null 时保留既有条目的密码不动（M1 整改：
     * UI 投影不再携带密码明文，密码由编辑页按需加载后显式提交）。
     */
    suspend fun saveEntry(entry: UiVaultEntry, passwordChars: CharArray? = null)

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

    /**
     * 一次性快照直出 Core 层 KdbxEntry 列表（供 Credential Provider 与 Autofill 系统服务直接消费，不经 UI 投影）
     */
    suspend fun getKdbxEntries(): List<com.keepasskey.core.model.KdbxEntry>

    /**
     * 按需解密单条凭据的密码（M1 整改）。
     * 仅在用户显式查看/复制密码时调用，杜绝全库密码明文驻留 StateFlow / 堆内存；
     * 返回 String 由调用方用毕自然丢弃（UI 显示边界），条目不存在或无密码时返回 null。
     */
    suspend fun getEntryPassword(entryId: String): String?

    /**
     * 按需解密单条历史修订的密码（M1 整改，供详情页回滚/对比使用），语义同 [getEntryPassword]。
     */
    suspend fun getEntryRevisionPassword(entryId: String, revisionId: String): String?

    /**
     * 根据依赖方标识 (RP ID) 或域名查询匹配的凭据条目
     */
    suspend fun findEntriesForRpId(rpId: String): List<com.keepasskey.core.model.KdbxEntry>

    /**
     * 根据 Base64URL 编码的 Credential ID 查找对应的 Passkey 凭据条目
     */
    suspend fun findPasskeyByCredentialId(credentialId: String): com.keepasskey.core.model.KdbxEntry?

    /**
     * 保存全新的 Passkey 凭据条目至根群组。
     * [boundPackage] 非空时（普通应用创建路径）条目 url 记录为 android://<包名>，
     * 供凭据查询按严格包名边界匹配；为空时记录为 https://<rpId>。
     */
    suspend fun saveNewPasskeyEntry(
        data: com.keepasskey.core.model.PasskeyData,
        boundPackage: String? = null
    ): com.keepasskey.core.model.KdbxEntry

    /**
     * 递增并写回 Passkey 条目的签名计数器 (SignCount)
     */
    suspend fun patchPasskeySignCount(entryId: String, newCount: Int)

    /**
     * 保存传统自动填充捕获的凭据：匹配既有条目则更新密码，否则新建条目。用毕显式擦除密码字符。
     */
    suspend fun saveAutofillCredential(
        packageName: String,
        webDomain: String?,
        username: String,
        passwordChars: CharArray
    )
}
