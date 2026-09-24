package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.model.VaultRemovalKind
import kotlinx.coroutines.flow.Flow

/**
 * 密码库数据仓库接口，遵循谷歌官方 Recommended app architecture 数据层规范。
 * 屏蔽上层 UI/ViewModel 对具体存储技术（KDBX / 内存 / Room）的依赖。
 *
 * ISSUE-P3-29：本接口的伴随值对象（[EntryRevisionSnapshot] / [EntryTotpSnapshot] /
 * [CreateKeyFileFactor]）已拆至同包 `VaultRepositoryTypes.kt`，全限定名不变。
 *
 * ISSUE-P3-305：本接口按**容量面**继续分列（纯结构性改动，接口方法全限定名与调用点零改动）——
 * 敏感值按需读取通道见 [VaultSecretAccess]，Passkey 凭据面见 [VaultPasskeyRepository]；
 * 本文件只保留库生命周期、分组与条目 CRUD、条目投影 / 查询、导出与模板。
 */
interface VaultRepository : VaultSecretAccess, VaultPasskeyRepository {
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
     *
     * ISSUE-P2-85：[preset] 为类型化的整组加密预设（外层算法 + KDF），二者都真实落到文件头。
     */
    suspend fun createDatabase(
        name: String,
        masterPassword: CharArray,
        keyFile: Boolean,
        preset: CreateVaultPreset
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
        preset: CreateVaultPreset,
        /** ISSUE-P2-229：非空即「经系统文件选择器自选位置」建库（`content://` uri 字符串） */
        targetUri: String? = null
    ): com.keepasskey.core.result.KdbxResult<Unit> = when {
        targetUri != null -> com.keepasskey.core.result.KdbxResult.Failure(
            UnsupportedOperationException("实现方未支持在自选位置建库"),
            // 兜底文案留空：本分支在生产 DI 下不可达（RealVaultRepository 已覆盖）
            userMessage = null
        )

        else -> when (keyFileFactor) {
            CreateKeyFileFactor.None -> createDatabase(name, masterPassword, keyFile = false, preset)
            else -> com.keepasskey.core.result.KdbxResult.Failure(
                UnsupportedOperationException("实现方未支持携带密钥文件的建库通道"),
                // 兜底文案留空：本分支在生产 DI 下不可达（RealVaultRepository 已覆盖），
                // Failure.message 会回退为 KdbxResult 的固定通用文案，绝不谎报「创建成功」
                userMessage = null
            )
        }
    }

    /**
     * 移除密码库。
     *
     * `ISSUE-P1-241`：[kind] 即该动作的真实对象——[com.keepasskey.app.ui.model.VaultRemovalKind.PRIVATE_FILE]
     * 表示这是应用私有目录内的库文件，移除**会删除该物理文件**（不可恢复）；
     * [com.keepasskey.app.ui.model.VaultRemovalKind.EXTERNAL_LINK] 表示物理文件在应用之外，
     * 只摘除本机登记。实现**必须**据此决定是否删除文件，不得由 `id` 形状反推。
     */
    suspend fun removeDatabase(
        id: String,
        kind: VaultRemovalKind
    ): com.keepasskey.core.result.KdbxResult<Unit>

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
     * 评估某个密码库来源的 KDF 工作因子是否**低于本应用建库默认强度**（ISSUE-P2-87）。
     *
     * 语义边界（调用前必读）：
     * - **只读、非阻断**：只解析外层明文头部（按 KDBX 规范，头部在认证之前即为明文，
     *   故**不需要任何凭据**，也不解密载荷），既不拒绝打开、也不改写任何 KDF 参数；
     * - 判据与文案口径见 `KdbxKdfStrengthAssessor`：结论只能表述为「低于本应用建库默认强度」，
     *   **不得**解读为「不安全 / 已被攻破」；
     * - **null = 未评估**（来源不可读 / 头部不可解析 / 远端来源不是本地文件）：
     *   本方法只服务于一条提示，调用时机在导入**已成功之后**，读取失败绝不得反过来
     *   影响已成功的导入，也不得据此谎报「低于基线」。
     *
     * 默认实现恒返回 null（未评估），**仅供不建模文件 IO 的测试替身沿用**——
     * 生产实现 `RealVaultRepository` 已覆盖。
     */
    suspend fun assessKdfStrength(path: String): com.keepasskey.database.file.KdbxKdfStrengthAssessment? = null

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
     *
     * [consumerField] 为**消费点面白名单**（ISSUE-P0-08）：调用方必须显式声明解析结果
     * 将进入哪个字段通道（`P`=口令通道 / 其余=非口令通道）。非口令通道命中
     * 受保护字段（取值面或检索面为 `P`）时输出掩码占位，绝不物化口令明文。
     * 条目或库会话不可用时返回 null（调用方回退原文）。
     */
    suspend fun resolveFieldReferences(
        entryId: String,
        rawText: String,
        consumerField: com.keepasskey.database.fieldref.FieldReferenceEngine.RefField
    ): String?

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
     *
     * **调用面约束（ISSUE-P3-148）**：本方法按库规模物化整份条目列表，只允许**确实需要全库**的
     * 调用方使用（选择器搜索、系统服务候选装配、健康检查等）。只处理**一条**已知 id 的调用方
     * 一律改用 [getKdbxEntry]——典型反例是自动填充二次确认页：它由 `EXTRA_ENTRY_ID` 明确指向单条，
     * 却曾复用选择器的整库装载路径，大库上每次确认多付一次与规模成正比的装载成本。
     */
    suspend fun getKdbxEntries(): List<com.keepasskey.core.model.KdbxEntry>

    /**
     * 按 id 取**单条**条目快照（ISSUE-P3-148：优先单条查询入口）。
     *
     * 与 [getKdbxEntries] 的语义差异在**成本面**而非内容面：实现方必须走「按 id 定位单条」的
     * 路径（如 `KdbxGroup.findEntry` 的深度优先短路搜索），**不得**物化整份条目列表再过滤。
     *
     * 会话锁定 / 库未打开 / id 非法 / 条目不存在一律返回 null——调用方沿用既有 fail-safe
     * 语义处理（自动填充侧不得因此崩溃或放行）。
     */
    suspend fun getKdbxEntry(entryId: String): com.keepasskey.core.model.KdbxEntry?

    // ISSUE-P3-305：原位于此处的敏感值读取通道（单条/批量取码、HOTP 推进、TOTP 配置原文、
    // 附件字节，以及 String 语义的下线通道）已整体迁往 [VaultSecretAccess]；本接口继承之，
    // 故既有调用点（`vaultRepository.getEntryPasswordChars(...)` 等）零改动。

    // ISSUE-P3-305：原位于此处的 Passkey 凭据面（RP/Credential ID 查询、新建与原地替换、
    // `excludeCredentials` 查重、签名计数器写回与原子递增）已整体迁往 [VaultPasskeyRepository]；
    // 本接口继承之，故既有调用点（`vaultRepository.findPasskeyByCredentialId(...)` 等）零改动。

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
     * ISSUE-P3-73：将当前内存数据库导出为通用明文 CSV（设置页「导出 CSV」用）。
     * 明文包含全部受保护字段（安全声明见导出确认对话框），锁定/关闭时返回 Failure。
     */
    suspend fun exportVaultCsvBytes(): com.keepasskey.core.result.KdbxResult<ByteArray>

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
