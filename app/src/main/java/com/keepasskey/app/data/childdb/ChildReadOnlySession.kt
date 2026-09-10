package com.keepasskey.app.data.childdb

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.exception.KdbxUnsupportedVersionException
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 单个挂载的**只读子库会话**：真实解密子库 `.kdbx` 并产出条目投影。
 *
 * ## 真解密（非空壳）
 *
 * 复用 `database` 模块唯一既有的解析入口 [KdbxFile.load]（与根库会话同一条加解密管线：
 * 头部 HMAC 认证 → HMAC 块流 → 解密 → 内层 Header → XML 流式解析），
 * **不重写任何 KDBX 解析逻辑**、不返回假数据：条目投影来自真实解密出的对象树。
 * 密钥派生（Argon2/AES-KDF）与解析均为 CPU 密集，统一在 [Dispatchers.Default] 执行。
 *
 * ## 只读边界（设计要点 2）
 *
 * 首版**仅只读**：会话只读字节、只产出投影，没有任何写回子库的路径。
 * 「一次保存写两个文件」的原子性现有 `DatabaseSession` 单文件事务模型不覆盖
 * （`save()` 只写 `currentFile`），因此本类**不提供** `save` / 字段写入 / 条目创建 API；
 * [ChildDatabaseMount.readOnly] 恒为 [CHILD_DATABASE_READ_ONLY]。
 * 将来做可写挂载的前置条件是：`database` 层出现跨文件两阶段提交（同目录双临时文件 +
 * 双 rename + 失败回滚），而非在 app 层拼两次 `save()`。
 *
 * ## 敏感数据边界
 *
 * - 凭据只经 [ChildDatabaseCredentialStore]（独立通道）取用，副本由该通道自动清零；
 * - 解密树**不做常驻**：投影（非敏感展示字段）生成后立即 `clearSensitiveData()` 定点擦除；
 * - 投影刻意不携带密码字段（只有 `hasPassword` 布尔事实），不提供任何读取子库密码的 API
 *   ——首版无需该能力，少一条明文通道即少一份暴露面；
 * - [terminate] 是**同步**终止路径（供根库锁定的 `SessionLockObserver` 回调直接调用）：
 *   只做内存清零（世代失效 + 投影丢弃 + 凭据槽位清零），无 IO、无挂起、不回调会话 API。
 *
 * ## 世代（generation）竞态防护
 *
 * `terminate()` 可能与正在进行的解密并发（用户解锁子库的同一瞬间根库超时熔断）。
 * 每次打开先领取代号，落库前比对：代号已过期则丢弃结果并返回
 * [ChildDatabaseFailureReason.TERMINATED]，绝不把「锁定之后才解密完」的数据装进会话。
 */
internal class ChildReadOnlySession(
    private val mount: ChildDatabaseMount,
    private val streamSource: ChildDatabaseStreamSource,
    private val credentials: ChildDatabaseCredentialStore
) {

    private val stateLock = Any()

    /** 挂载身份（与挂载记录一致，供管理器登记 / 摘除会话） */
    val mountId: String
        get() = mount.id

    /** 世代号：任何 [terminate] 或新一次打开都会使其自增，令在途结果失效 */
    private var generation: Long = 0L

    private var snapshot: ChildDatabaseSnapshot? = null

    private val stateFlow = MutableStateFlow<ChildDatabaseMountState>(ChildDatabaseMountState.Closed)

    /** 当前状态（初始态为 [ChildDatabaseMountState.Closed]） */
    val state: StateFlow<ChildDatabaseMountState> = stateFlow.asStateFlow()

    /**
     * 以显式提供的凭据打开：凭据写入独立通道（克隆语义）后立即尝试解密。
     *
     * [passwordChars] / [keyFileData] 为借用语义——本类经通道克隆，调用方持有者负责清零。
     * 解密失败（含凭据被拒）时不保留任何凭据。
     */
    suspend fun open(
        passwordChars: CharArray?,
        keyFileData: ByteArray?
    ): KdbxResult<ChildDatabaseSnapshot> {
        credentials.store(mount.credentialRefId, passwordChars, keyFileData)
        return openWithStoredCredentials()
    }

    /** 以独立通道中已存的凭据打开（进程内刷新 / 重开）；无凭据时如实返回凭据缺失 */
    suspend fun openWithStoredCredentials(): KdbxResult<ChildDatabaseSnapshot> {
        val token = beginAttempt()
        return settle(token, runAttempt())
    }

    /** 当前投影（未打开时为 null；投影内无密钥材料） */
    fun currentSnapshot(): ChildDatabaseSnapshot? = synchronized(stateLock) { snapshot }

    /** 同步终止：世代失效、投影丢弃、独立凭据通道清零（幂等，可被锁定回调直接调用） */
    fun terminate() {
        synchronized(stateLock) {
            generation += 1
            snapshot = null
            stateFlow.value = ChildDatabaseMountState.Closed
        }
        credentials.clear(mount.credentialRefId)
    }

    private fun beginAttempt(): Long = synchronized(stateLock) {
        generation += 1
        stateFlow.value = ChildDatabaseMountState.Opening
        generation
    }

    private fun isStale(token: Long): Boolean = synchronized(stateLock) { generation != token }

    private suspend fun runAttempt(): Attempt = try {
        // 显式指定类型实参：两条分支的公共父类型即 Attempt，避免依赖推断
        credentials.useCredentials<Attempt>(mount.credentialRefId) { password, keyFile ->
            if (password == null && keyFile == null) {
                // 无凭据：绝不用空复合密钥去试探（那只会得到误导性的「凭据被拒」）
                Attempt.CredentialMissing
            } else {
                loadProjection(password, keyFile)
            }
        }
    } catch (t: Throwable) {
        classify(t)
    }

    private suspend fun loadProjection(password: CharArray?, keyFile: ByteArray?): Attempt = try {
        val projected = withContext(Dispatchers.Default) {
            streamSource.open(mount).use { input ->
                KdbxFile.load(input, password, keyFile)
            }.let { database ->
                try {
                    project(database)
                } finally {
                    // 子库明文树不驻留内存：投影完成后立即定点擦除
                    database.clearSensitiveData()
                }
            }
        }
        Attempt.Loaded(projected)
    } catch (t: Throwable) {
        classify(t)
    }

    private fun settle(token: Long, attempt: Attempt): KdbxResult<ChildDatabaseSnapshot> {
        // 过期结果一律丢弃且不改状态：terminate() 已把状态置回 Closed 并清零凭据
        if (isStale(token)) return failure(ChildDatabaseFailureReason.TERMINATED, null)
        return when (attempt) {
            is Attempt.Loaded -> acceptLoaded(attempt)
            Attempt.CredentialMissing -> {
                stateFlow.value = ChildDatabaseMountState.Closed
                failure(ChildDatabaseFailureReason.CREDENTIAL_MISSING, null)
            }

            is Attempt.Rejected -> {
                // 错误凭据绝不保留：立即走独立清零路径，用户须重新输入
                credentials.clear(mount.credentialRefId)
                stateFlow.value = ChildDatabaseMountState.CredentialRejected
                failure(ChildDatabaseFailureReason.CREDENTIAL_REJECTED, attempt.error)
            }

            is Attempt.Unavailable -> {
                stateFlow.value = ChildDatabaseMountState.SourceUnavailable
                failure(ChildDatabaseFailureReason.SOURCE_UNAVAILABLE, attempt.error)
            }

            is Attempt.Broken -> {
                stateFlow.value = ChildDatabaseMountState.Failed(attempt.reason)
                failure(attempt.reason, attempt.error)
            }
        }
    }

    private fun acceptLoaded(attempt: Attempt.Loaded): KdbxResult<ChildDatabaseSnapshot> {
        synchronized(stateLock) { snapshot = attempt.snapshot }
        stateFlow.value = ChildDatabaseMountState.Opened(attempt.snapshot)
        return KdbxResult.Success(attempt.snapshot)
    }

    private fun failure(
        reason: ChildDatabaseFailureReason,
        cause: Throwable?
    ): KdbxResult<ChildDatabaseSnapshot> =
        KdbxResult.Failure(ChildDatabaseException(reason, cause))

    /**
     * 异常→原因分型。
     *
     * `KdbxInvalidCredentialsException` / `KdbxCorruptFileException` / `KdbxUnsupportedVersionException`
     * 均为 [IOException] 子类，必须**先于** [IOException] 判定，否则具体分型会被泛化吞掉。
     */
    private fun classify(t: Throwable): Attempt = when (t) {
        is KdbxInvalidCredentialsException -> Attempt.Rejected(t)
        is KdbxUnsupportedVersionException ->
            Attempt.Broken(ChildDatabaseFailureReason.UNSUPPORTED_VERSION, t)

        is KdbxCorruptFileException -> Attempt.Broken(ChildDatabaseFailureReason.CORRUPT_FILE, t)
        is FileNotFoundException -> Attempt.Unavailable(t)
        is SecurityException -> Attempt.Unavailable(t)
        is IOException -> Attempt.Broken(ChildDatabaseFailureReason.IO_ERROR, t)
        else -> Attempt.Broken(ChildDatabaseFailureReason.UNKNOWN, t)
    }

    /**
     * 遍历子库对象树生成只读投影。
     *
     * 路径口径：**不含子库根分组名**——根分组下的直接条目路径为 [ROOT_LEVEL_GROUP_PATH]（空串），
     * 子分组条目为 `子分组/更深分组`；[ChildDatabaseSnapshot.groupCount] 则含根分组。
     */
    private fun project(database: KdbxDatabase): ChildDatabaseSnapshot {
        val collected = mutableListOf<ChildDatabaseEntryProjection>()
        database.rootGroup.entries.forEach { collected.add(it.toProjection(ROOT_LEVEL_GROUP_PATH)) }
        database.rootGroup.subgroups.forEach { collectEntries(it, it.name, collected) }
        return ChildDatabaseSnapshot(
            mountId = mount.id,
            mountAlias = mount.alias,
            databaseName = database.databaseName,
            groupCount = database.rootGroup.allGroups().size,
            entries = collected,
            openedAtEpochMillis = System.currentTimeMillis()
        )
    }

    private fun collectEntries(
        group: KdbxGroup,
        path: String,
        into: MutableList<ChildDatabaseEntryProjection>
    ) {
        group.entries.forEach { into.add(it.toProjection(path)) }
        group.subgroups.forEach { collectEntries(it, path + GROUP_PATH_SEPARATOR + it.name, into) }
    }

    /** 单条目投影：只读展示字段 + 「是否有密码」布尔事实，绝不读取密码本体 */
    private fun KdbxEntry.toProjection(groupPath: String): ChildDatabaseEntryProjection =
        ChildDatabaseEntryProjection(
            mountId = mount.id,
            mountAlias = mount.alias,
            entryUuid = id.toHexString(),
            title = title,
            username = userName,
            url = url,
            notes = notes,
            groupPath = groupPath,
            tags = tags,
            iconId = iconId,
            hasPassword = fields[KdbxConstants.Fields.PASSWORD]?.isEmpty == false
        )

    /** 一次打开尝试的分型结果（内部中间态，不对外暴露） */
    private sealed interface Attempt {

        /** 解密 + 投影成功 */
        data class Loaded(val snapshot: ChildDatabaseSnapshot) : Attempt

        /** 独立通道内无凭据 */
        data object CredentialMissing : Attempt

        /** 凭据被拒（主密码 / 密钥文件错误） */
        data class Rejected(val error: Throwable) : Attempt

        /** 来源不可读 */
        data class Unavailable(val error: Throwable) : Attempt

        /** 其他失败（损坏 / 版本 / IO / 未知） */
        data class Broken(val reason: ChildDatabaseFailureReason, val error: Throwable) : Attempt
    }

    private companion object {
        /** 子库根分组下直接条目的路径（空串：层级由挂载别名与库名表达） */
        const val ROOT_LEVEL_GROUP_PATH = ""

        /** 分组路径分隔符 */
        const val GROUP_PATH_SEPARATOR = "/"
    }
}
