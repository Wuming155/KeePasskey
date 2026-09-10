package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import kotlinx.coroutines.flow.Flow

/**
 * 密码库数据仓库接口，遵循谷歌官方 Recommended app architecture 数据层规范。
 * 屏蔽上层 UI/ViewModel 对具体存储技术（KDBX / 内存 / Room）的依赖。
 *
 * ISSUE-P3-29：本接口的伴随值对象（[EntryRevisionSnapshot] / [EntryTotpSnapshot] /
 * [CreateKeyFileFactor]）已拆至同包 `VaultRepositoryTypes.kt`，全限定名不变。
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
     * 解锁当前选中的活动密码库。
     * [readOnly] 为 true 时以只读模式打开（H4-只读整改）：会话期间一切写盘硬拒绝。
     * [keyFileData] 为复合密钥的密钥文件原始字节（修复虚假开关整改）：数据库以
     * 「主密码 + 密钥文件」保护时必须提供；数组为借用语义——实现方与会话内部各自克隆，
     * 调用方持有方负责在解锁成功/失败收尾与离开页面时显式清零，绝不落地为 String。
     */
    suspend fun unlockActiveDatabase(
        passwordChars: CharArray,
        keyFileData: ByteArray? = null,
        readOnly: Boolean = false
    ): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 更改当前数据库的主密钥。
     */
    suspend fun changeMasterPassword(newPassword: CharArray): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 锁定当前密码库，清空内存凭据与活动树
     */
    suspend fun lockDatabase()

    /**
     * 检查当前密码库是否处于锁定状态
     */
    fun isLocked(): Boolean

    /**
     * 创建新密码库（H3 整改：创建/写盘结果必须向上传播，禁止静默失败）。
     * H2 整改：主密码以 [CharArray] 承载（原 String 参数不可变驻留堆内存）；
     * 数组为借用语义——实现方不持有、不擦除，调用方用毕自行清零。
     *
     * ISSUE-P3-21：本方法是历史入口，[keyFile] = true 的真实语义为
     * 「生成并绑定附属密钥文件」（等价于 [createDatabaseWithKeyFile] 的
     * [CreateKeyFileFactor.Generate]）；携带**用户选定的既有密钥文件字节**必须改走
     * [createDatabaseWithKeyFile]，本方法无法承载该意图。
     */
    suspend fun createDatabase(
        name: String,
        masterPassword: CharArray,
        keyFile: Boolean,
        preset: String
    ): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 以显式密钥文件因子创建新密码库（ISSUE-P3-21：复合密钥第二因子真实接线）。
     *
     * [masterPassword] 为借用语义（实现方不持有、不擦除）；[keyFileFactor] 中
     * [CreateKeyFileFactor.Existing] 的字节同为借用语义，由调用方负责清零。
     *
     * 默认实现**仅供未覆盖本方法的测试替身沿用**：仅 [CreateKeyFileFactor.None] 可回退到
     * [createDatabase]；一旦携带密钥文件因子而未覆盖本方法，必须**显式失败**——
     * 静默丢弃第二因子正是 ISSUE-P3-21 要消除的假开关语义
     * （生产实现 `RealVaultRepository` 已覆盖本方法）。
     */
    suspend fun createDatabaseWithKeyFile(
        name: String,
        masterPassword: CharArray,
        keyFileFactor: CreateKeyFileFactor,
        preset: String
    ): com.keepasskey.core.result.KdbxResult<Unit> = when (keyFileFactor) {
        CreateKeyFileFactor.None -> createDatabase(name, masterPassword, keyFile = false, preset)
        else -> com.keepasskey.core.result.KdbxResult.Failure(
            UnsupportedOperationException("实现方未支持携带密钥文件的建库通道"),
            // 兜底文案留空：本分支在生产 DI 下不可达（RealVaultRepository 已覆盖），
            // Failure.message 会回退为 KdbxResult 的固定通用文案，绝不谎报「创建成功」
            userMessage = null
        )
    }

    /**
     * 移除密码库关联
     */
    suspend fun removeDatabase(id: String): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 导入并打开已有 KDBX 数据库 (支持本地、WebDAV、S3 来源)
     *
     * P3-23：[syncType] 为落库的持久化标签（数据库卡片回显其存储值，与
     * OpenVaultSourceType.LOCAL.label 对齐），非纯展示文案，保留原样
     */
    suspend fun importExternalDatabase(
        name: String,
        path: String,
        syncType: String = "本地设备存储"
    ): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 获取全部群组/文件夹的实时响应式流
     */
    fun getGroups(): Flow<List<VaultGroup>>

    /**
     * 保存或更新群组/文件夹
     */
    suspend fun saveGroup(group: VaultGroup): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 删除群组/文件夹
     */
    suspend fun deleteGroup(id: String): com.keepasskey.core.result.KdbxResult<Unit>

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
     * 擦除契约（加解密审查 2026-09 M4/M1 统一）：实现方在任何结果路径（成功/失败/异常）
     * 用毕后负责显式清零传入的 [passwordChars]、[totpSecretChars] 与 [protectedFieldChars]
     * 各数组副本（与 [saveAutofillCredential] 同一契约），调用方须传入可被清零的副本，
     * 不可复用为后续编辑状态。
     * [totpSecretChars] 语义同密码（断点4 整改 + TASK-10 CharArray 化）：null 表示未修改
     * 保留既有 TOTP 配置；非 null 时写入标准 otp 字段（空数组表示清除 TOTP）。
     * [protectedFieldChars]（TASK-10：受保护自定义字段编辑态 CharArray 化）：键为
     * [UiCustomField.id]，值为用户显式编辑后的受保护字段明文——仅显式编辑过的字段需要
     * 提交，未编辑的受保护字段在 UI 投影中值为空串，由实现方回填既有值（F2 语义）。
     * 返回 [com.keepasskey.core.result.KdbxResult]（H3 整改）：保存失败必须显式返回，
     * 禁止磁盘写失败被静默吞掉而 UI 谎报成功。
     */
    suspend fun saveEntry(
        entry: UiVaultEntry,
        passwordChars: CharArray? = null,
        totpSecretChars: CharArray? = null,
        protectedFieldChars: Map<String, CharArray> = emptyMap()
    ): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 删除凭据条目（移至回收站或彻底删除）
     */
    suspend fun deleteEntry(id: String): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 克隆凭据条目（TASK-16）：全字段保真复制至同一分组，分配全新 UUID、
     * 清空历史修订、时间属性重置为克隆时刻。成功返回新条目十六进制 UUID
     * （KdbxResult.Success.data），供 UI 导航至克隆体。
     */
    suspend fun duplicateEntry(id: String): com.keepasskey.core.result.KdbxResult<String>

    /**
     * 上传 PNG 字节为库级自定义图标（TASK-15，存于 KDBX Meta CustomIcons，
     * 条目经 CustomIconUUID 引用）。内容去重：重复上传相同图片返回既有图标 UUID。
     * 成功返回图标 UUID hex（KdbxResult.Success.data）。
     */
    suspend fun addCustomIcon(pngBytes: ByteArray): com.keepasskey.core.result.KdbxResult<String>

    /** 库内自定义图标池快照（UUID hex → PNG 字节），供 UI 解码渲染 */
    suspend fun getCustomIconBytes(): Map<String, ByteArray>

    /**
     * 解析 [rawText] 中的 KeePass 字段引用 `{REF:...}`（TASK-17）。
     * 仅在取值消费点调用（详情复制 / 自动填充下发），投影层不展开——
     * 引用指向的密码明文不得提前物化进 UI 状态流。
     * 条目或库会话不可用时返回 null（调用方回退原文）。
     */
    suspend fun resolveFieldReferences(entryId: String, rawText: String): String?

    /**
     * 切换条目收藏状态并持久化落库（TASK-34 整改：原实现仅翻转内存 Flow 不落库）。
     * 收藏标记存于 KDBX 条目 customData，随库文件同步；不产生历史修订快照。
     */
    suspend fun setEntryFavorite(
        entryId: String,
        favorite: Boolean
    ): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 还原处于回收站中的凭据条目
     */
    suspend fun restoreEntry(id: String): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 清空回收站（断点9 整改：同时清除回收站内的子分组并记录墓碑）
     */
    suspend fun emptyRecycleBin(): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 批量移动凭据条目至目标分组
     */
    suspend fun batchMoveEntries(entryIds: Set<String>, targetGroupId: String?): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 批量删除凭据条目（移至回收站）
     */
    suspend fun batchDeleteEntries(entryIds: Set<String>): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 一次性快照直出 Core 层 KdbxEntry 列表（供 Credential Provider 与 Autofill 系统服务直接消费，不经 UI 投影）
     */
    suspend fun getKdbxEntries(): List<com.keepasskey.core.model.KdbxEntry>

    /**
     * 按需解密单条凭据的密码（M1 整改）。
     * 仅在用户显式查看/复制密码时调用，杜绝全库密码明文驻留 StateFlow / 堆内存；
     * 返回 String 由调用方用毕自然丢弃（UI 显示边界），条目不存在或无密码时返回 null。
     *
     * ISSUE-P2-15：返回值为不可擦除 String，已列入下线通道；新代码一律改用
     * [getEntryPasswordChars]（CharArray 独占副本，调用方用毕 `fill('0')`）。
     * 仅剩 {REF:...} 字段引用解析等 String 语义引擎不得不用时保留。
     */
    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryPasswordChars(entryId)")
    )
    suspend fun getEntryPassword(entryId: String): String?

    /**
     * 按需解密单条凭据的密码为 CharArray（加解密审查 2026-09 M1 整改，编辑页 CharArray 直通链路）。
     * 返回的数组是分配给调用方的独占副本，调用方使用完毕必须显式清零
     * （`Arrays.fill(chars, '0')`）；条目不存在或无密码时返回 null。
     */
    suspend fun getEntryPasswordChars(entryId: String): CharArray?

    /**
     * 按需解密单条历史修订的密码（M1 整改，供详情页回滚/对比使用），语义同 [getEntryPassword]。
     *
     * ISSUE-P2-15：同属待下线 String 通道，请改用 [getEntryRevisionPasswordChars]
     * （CharArray 独占副本，调用方用毕 `fill('0')` 或交由 [saveEntry] 擦除）。
     */
    @Deprecated(
        message = "String 明文不可显式擦除；请改用 getEntryRevisionPasswordChars 并在 finally 中清零",
        replaceWith = ReplaceWith("getEntryRevisionPasswordChars(entryId, revisionId)")
    )
    suspend fun getEntryRevisionPassword(entryId: String, revisionId: String): String?

    /**
     * 按需解密单条历史修订的密码为 CharArray（加解密审查 2026-09 M2 整改，回滚路径专用：
     * 全程 CharArray、不经 String 中转）。返回的数组是分配给调用方的独占副本，调用方
     * 使用完毕必须显式清零；修订不存在或无密码时返回 null。
     */
    suspend fun getEntryRevisionPasswordChars(entryId: String, revisionId: String): CharArray?

    /**
     * 读取单条历史修订的完整回滚快照（断点8 整改，供详情页全字段回滚）。
     * 返回的 [EntryRevisionSnapshot.entry] 中受保护字段已解密（仅驻留编辑会话），
     * [EntryRevisionSnapshot.totpSecretChars] 为该修订 TOTP 配置原文独占 CharArray 副本
     * （无则空数组），调用方按借用语义用毕清零或交由 [saveEntry] 擦除；修订不存在时返回 null。
     */
    suspend fun getEntryRevisionSnapshot(entryId: String, revisionId: String): EntryRevisionSnapshot?

    /**
     * 按需解密单条凭据的受保护自定义字段为 CharArray（TASK-10：编辑态 CharArray 化，
     * 与 [getEntryPasswordChars] 同一借用语义）。
     * 仅在用户显式编辑该字段时调用；返回的数组是分配给调用方的独占副本，调用方使用完毕
     * 必须显式清零；条目或字段不存在时返回 null，未加保护的字段直接返回其值副本。
     */
    suspend fun getEntryProtectedFieldChars(entryId: String, fieldKey: String): CharArray?

    /**
     * 按需计算单条凭据的当前 TOTP 验证码（F2 整改）。
     * TOTP 种子绝不离开数据层——种子解析与验证码计算均在仓库内部完成并即时丢弃，
     * UI 层仅取得验证码与展示配置；条目未配置 TOTP 时返回 null。
     */
    suspend fun calculateEntryTotp(entryId: String): EntryTotpSnapshot?

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
     * 递增并写回 Passkey 条目的签名计数器 (SignCount)。
     *
     * ISSUE-P3-27 子项 2：本入口不向调用方回传落库值，**断言路径不得使用它**——
     * 需要把计数器写进 AuthenticatorData 的调用方必须改用 [incrementPasskeySignCount]。
     */
    suspend fun patchPasskeySignCount(entryId: String, newCount: Int)

    /**
     * 原子递增并返回**本次实际落库**的签名计数器（ISSUE-P3-27 子项 2）。
     *
     * 断言路径必须使用本返回值，禁止用锁外快照自行计算（`快照 + 1`）：
     * 快照在进入断言时读取、与落库不在同一临界区，两个并发断言会算出同一个值并各自
     * 向 RP 交出**重复**的 signCount（违反 WebAuthn 单调性）；「已签名回传、落盘前进程中断」
     * 的重试同样会再交一次同值。本方法的递增与落库属同一受控原子变换，故返回值即「已提交」
     * 的计数器，且返回值已写进响应时库内计数器必然已推进。
     *
     * @return 实际落库的计数器值；条目不存在 / entryId 非法时返回 null
     *   （调用方此时必须 fail-closed 拒绝签发，不得回退为自算值）。
     */
    suspend fun incrementPasskeySignCount(entryId: String): Int?

    /**
     * 保存传统自动填充捕获的凭据：匹配既有条目则更新密码，否则新建条目。用毕显式擦除密码字符。
     */
    suspend fun saveAutofillCredential(
        packageName: String,
        webDomain: String?,
        username: String,
        passwordChars: CharArray
    ): com.keepasskey.core.result.KdbxResult<Unit>

    /**
     * 按需读取单条凭据的 TOTP 配置原文为 CharArray（断点4 整改 + TASK-10 编辑态 CharArray 化）。
     * 与 [calculateEntryTotp] 同源：标准 otp 字段优先，回退 TOTP 开头的自定义字段；
     * 未配置时返回 null。返回的数组是分配给调用方的独占副本，调用方使用完毕必须显式清零。
     */
    suspend fun getEntryTotpSecretChars(entryId: String): CharArray?

    /**
     * 按需解析单条凭据指定附件的二进制内容（断点3 整改，SAF 导出用）。
     * 附件不存在或名称不匹配时返回 null。
     */
    suspend fun getAttachmentData(entryId: String, fileName: String): ByteArray?

    /**
     * 当前会话是否以只读模式打开（H4-只读整改）。锁定/关闭状态下返回 false。
     */
    fun isSessionReadOnly(): Boolean

    /**
     * TASK-13 整改：将当前内存数据库序列化为 KDBX 完整字节流（设置页「导出 KDBX」SAF 写盘用）。
     * 锁定/关闭/凭据丢失时返回 [com.keepasskey.core.result.KdbxResult.Failure]。
     */
    suspend fun exportKdbxBytes(): com.keepasskey.core.result.KdbxResult<ByteArray>

    /**
     * TASK-13 整改：将当前内存数据库导出为 KeePass 2.x 兼容明文 XML（设置页「导出 XML」用）。
     * 明文包含全部受保护字段（安全声明见导出确认对话框），锁定/关闭时返回 Failure。
     */
    suspend fun exportVaultXmlBytes(): com.keepasskey.core.result.KdbxResult<ByteArray>

    /**
     * TASK-13 整改：导出会话绑定的密钥文件原始字节（设置页「导出密钥文件」用）。
     * 会话未绑定密钥文件时返回 Failure。
     */
    suspend fun exportKeyFileBytes(): com.keepasskey.core.result.KdbxResult<ByteArray>

    /**
     * TASK-13 整改：安装条目模板库（创建「模板」分组与 5 个标准模板条目，真实落库）。
     */
    suspend fun installEntryTemplates(): com.keepasskey.core.result.KdbxResult<Unit>
}
