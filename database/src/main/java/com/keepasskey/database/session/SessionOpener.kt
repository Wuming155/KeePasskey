package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.result.suppressCleanupFailure
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import com.keepasskey.database.io.WipableByteArrayOutputStream
import java.nio.file.FileAlreadyExistsException
import java.io.FileInputStream
import java.io.InputStream

/**
 * 会话建立（ISSUE-P3-31 批次 D 结构拆分）。
 *
 * 自 [DatabaseSession] 原样抽出「新建 / 打开 / 打开流」三条入口：统一在 [mutex] 临界区内
 * 完成解密或建库、装配 [SessionCore]（活动文件、写盘通道、只读标志）、缓存凭据并置 OPENED。
 * 失败一律返回 [KdbxResult.Failure]；新建失败保留旧会话。
 */
internal class SessionOpener(
    private val core: SessionCore,
    private val credentials: SessionCredentialCache,
    private val fileWriter: SessionFileWriter,
    private val mutex: Mutex,
    /** ISSUE-P2-24：大附件落盘存储（可选）；为空时解析行为与既往逐字一致。 */
    private val binaryStore: BinaryStore? = null,
    /**
     * ISSUE-P3-368：打开链进度上报（0..1 确定进度 / null 分段不确定段）。
     * 由 `DatabaseSession` 注入 StateFlow 写入口；只承载数值，不捕获任何敏感引用（AC③）。
     */
    private val progress: (Float?) -> Unit = {},
    /**
     * ENG-20261027-02 / ISSUE-P2-77：换库/重载释放旧会话（由 `DatabaseSession` 注入）。
     *
     * 仅在候选数据库成功解析并准备替换活动库时调用（禁止在解析前释放）。
     * [liveCandidate] 作为存活侧传入，使旧库附件清理只回收不再被候选引用的 spill key，
     * 并排除全局 clearAll 误清候选附件。
     */
    private val releaseCurrentSession: (liveCandidate: KdbxDatabase?) -> Unit = {}
) {

    /**
     * 创建全新密码库文件并打开会话。
     *
     * ISSUE-P3-21（复合密钥三分支）：[keyFileData] 为「主密码 + 密钥文件」的第二因子，
     * 字节为**借用语义**：本方法只在 `KdbxFile.save` 内参与复合密钥派生，并按会话保存需要
     * 克隆进凭据缓存；不持有、不擦除调用方数组，调用方用毕自行清零。传 null 即「仅主密码」库。
     *
     * ISSUE-P2-85：新增 [cipherUuid]（外层加密算法）。此前外层算法**恒为** `AES_256_CBC`
     * 硬编码，建库向导选择的 ChaCha20 / Twofish 被静默丢弃（详见 `CreateVaultPreset`）。
     * 默认值保持 `AES_256_CBC`（KDBX4 官方默认）以便「文件缺失即初始化」等无预设入口沿用；
     * **带用户预设的建库路径必须显式传入**。
     */
    suspend fun create(
        file: File,
        name: String,
        passwordChars: CharArray,
        useArgon2: Boolean = true,
        keyFileData: ByteArray? = null,
        cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC
    ): KdbxResult<Unit> = createNew(
        name, passwordChars, useArgon2, keyFileData, cipherUuid,
        pathIdentifier = file.absolutePath,
        associatedFile = file,
        saveWriter = { bytes ->
            withContext(Dispatchers.IO) {
                fileWriter.writeAtomicByBackupPreference(file) { it.write(bytes) }
            }
        },
        trackedSaveWriter = { bytes, receipt ->
            withContext(Dispatchers.IO) {
                fileWriter.writeAtomicTracked(file, receipt) { it.write(bytes) }
            }
        },
        writeNew = { db ->
            serializeCreatedAndWrite(db, passwordChars, keyFileData) { bytes ->
                NewDatabaseFileWriter.write(file) { it.write(bytes) }
            }
        }
    )

    /** SAF 新建：调用方只允许写入本流程 CreateDocument 产物，并须在返回前验证读回。 */
    suspend fun createStream(
        pathIdentifier: String,
        name: String,
        passwordChars: CharArray,
        useArgon2: Boolean,
        keyFileData: ByteArray?,
        cipherUuid: KdbxUuid,
        writeNew: suspend (ByteArray) -> Unit,
        saveWriter: suspend (ByteArray) -> Unit,
        trackedSaveWriter: (suspend (ByteArray, SessionWriteReceipt) -> Unit)? = null
    ): KdbxResult<Unit> = createNew(
        name, passwordChars, useArgon2, keyFileData, cipherUuid,
        pathIdentifier, null, saveWriter, trackedSaveWriter
    ) { db ->
        serializeCreatedAndWrite(db, passwordChars, keyFileData) { bytes ->
            writeNew(bytes)
        }
    }

    /** 同步返回独立副本；调用者在任何挂起点之前建立 finally 所有者。 */
    private fun serializeCreated(db: KdbxDatabase, passwordChars: CharArray, keyFileData: ByteArray?): ByteArray {
        val output = WipableByteArrayOutputStream()
        return try {
            KdbxFile.save(output, db, passwordChars, keyFileData)
            output.toByteArray()
        } finally {
            output.wipe()
            output.close()
        }
    }

    /**
     * `ISSUE-P1-631`：新建链路的「序列化 → 写出」——**成功返回写出的字节**（所有权移交调用方：
     * 登记持久化基线后清零）；写出抛异常时在本函数内清零并重抛（失败路径不让整库密文外泄）。
     */
    private suspend fun serializeCreatedAndWrite(
        db: KdbxDatabase,
        passwordChars: CharArray,
        keyFileData: ByteArray?,
        write: suspend (ByteArray) -> Unit
    ): ByteArray {
        val bytes = serializeCreated(db, passwordChars, keyFileData)
        try {
            withContext(Dispatchers.IO) { write(bytes) }
        } catch (t: Throwable) {
            bytes.fill(0)
            throw t
        }
        return bytes
    }

    /** 不发布半成品会话；新建空树不解析附件，旧会话释放可安全延后至提交成功。 */
    private suspend fun createNew(
        name: String,
        passwordChars: CharArray,
        useArgon2: Boolean,
        keyFileData: ByteArray?,
        cipherUuid: KdbxUuid,
        pathIdentifier: String,
        associatedFile: File?,
        saveWriter: suspend (ByteArray) -> Unit,
        trackedSaveWriter: (suspend (ByteArray, SessionWriteReceipt) -> Unit)? = null,
        /**
         * `ISSUE-P1-631`：写出新库并**返回写出的字节**（待调用方登记持久化基线后清零；
         * 写出失败由实现自行清零并重抛——见 [serializeCreatedAndWrite]）。
         */
        writeNew: suspend (KdbxDatabase) -> ByteArray
    ): KdbxResult<Unit> = mutex.withLock {
        // 落盘与会话切换是同一临界操作，提交后不能因调用方取消留下未登记的成功产物。
        withContext(NonCancellable) {
            withContext(Dispatchers.Default) {
                var candidate: KdbxDatabase? = null
                var ownedPassword: CharArray? = null
                var ownedKeyFile: ByteArray? = null
                // ISSUE-P1-631：新库写出字节——先登记持久化基线，finally 清零
                var createdBytes: ByteArray? = null
                try {
                    val candidateTicket = SessionCredentialTicket(pathIdentifier)
                    // 先分配候选副本；clone 失败时旧会话尚未释放，已分配副本由 finally 擦除。
                    ownedPassword = passwordChars.copyOf()
                    ownedKeyFile = keyFileData?.copyOf()
                    val db = KdbxDatabase(
                        header = KdbxHeader.createDefault(cipherUuid = cipherUuid, useArgon2 = useArgon2),
                        databaseName = name,
                        databaseDescription = "Created by KeePasskey",
                        rootGroup = KdbxGroup(name = name.ifBlank { "Root" }, iconId = 48)
                    )
                    candidate = db
                    createdBytes = writeNew(db)
                    releaseCurrentSession(candidate)
                    core.activeFile = associatedFile
                    core.activePathIdentifier = pathIdentifier
                    core.saveWriter = saveWriter
                    core.trackedSaveWriter = trackedSaveWriter
                    core.credentialWriteUncertain.value = false
                    core.readOnlyMode = false
                    // adoptOwnedCredentials 接收拥有型数组，无分配移交；与 ISSUE-P1-593 共用。
                    credentials.adoptOwnedCredentials(ownedPassword, ownedKeyFile)
                    ownedPassword = null
                    ownedKeyFile = null
                    core.database.value = db
                    core.adoptCredentialTicket(candidateTicket)
                    core.state.value = DatabaseSession.SessionState.OPENED
                    // ISSUE-P1-631：新建并写出成功的字节即本会话初始持久化基线（克隆归会话）
                    core.retainPersistedBaselineBestEffort(createdBytes, pathIdentifier)
                    candidate = null // 明文树移交会话，失败分支不能擦除旧树。
                    KdbxResult.Success(Unit)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val code = when (t) {
                        is FileAlreadyExistsException -> KdbxError.CREATE_COLLISION
                        is UnsupportedOperationException -> KdbxError.CREATE_UNSUPPORTED
                        else -> KdbxError.CREATE_FAILED
                    }
                    KdbxResult.Failure(t, code = code)
                } finally {
                    candidate?.clearSensitiveData()
                    ownedPassword?.fill('0')
                    ownedKeyFile?.fill(0)
                    createdBytes?.fill(0)
                }
            }
        }
    }

    /**
     * 打开并解密已有 KDBX 文件（支持直接传入 File）。
     */
    suspend fun open(
        file: File,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        readOnly: Boolean = false
    ): KdbxResult<Unit> = openStream(
        pathIdentifier = file.absolutePath,
        inputStreamProvider = {
            if (!file.exists()) {
                throw java.io.FileNotFoundException("文件不存在: ${file.absolutePath}")
            }
            FileInputStream(file)
        },
        saveWriter = { bytes ->
            withContext(Dispatchers.IO) {
                fileWriter.writeAtomicByBackupPreference(file) { os ->
                    os.write(bytes)
                }
            }
        },
        passwordChars = passwordChars,
        keyFileData = keyFileData,
        readOnly = readOnly,
        associatedFile = file,
        trackedSaveWriter = { bytes, receipt ->
            withContext(Dispatchers.IO) {
                fileWriter.writeAtomicTracked(file, receipt) { it.write(bytes) }
            }
        }
    )

    /**
     * 打开并解密 KDBX 流（支持系统 SAF Uri、网络缓存及普通 File 等多元数据源）。
     * [inputStreamProvider] 每次按需提供新鲜可读输入流；
     * [saveWriter] 保存时的二进制写出通道（如 ContentResolver.openOutputStream 或原子写盘）。
     */
    suspend fun openStream(
        pathIdentifier: String,
        inputStreamProvider: suspend () -> InputStream,
        saveWriter: (suspend (ByteArray) -> Unit)? = null,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        readOnly: Boolean = false,
        associatedFile: File? = null,
        trackedSaveWriter: (suspend (ByteArray, SessionWriteReceipt) -> Unit)? = null
    ): KdbxResult<Unit> = mutex.withLock {
        withContext(Dispatchers.Default) {
            // ISSUE-P3-368：进入打开链先清陈旧进度（null = 不确定段），避免上一次进度残留首帧
            progress(null)
            val spillTransaction = KdbxFile.beginAttachmentSpillTransaction(binaryStore)
            var candidate: KdbxDatabase? = null
            var ownedPassword: CharArray? = null
            var ownedKeyFile: ByteArray? = null
            // ISSUE-P1-631：解析旁路捕获的字节镜像（打开 / 重载成功即登记为持久化基线）
            var baselineCapture: PersistedBaselineCapturingInputStream? = null
            try {
                val candidateTicket = SessionCredentialTicket(pathIdentifier)
                // ENG-20261027-02：分配独立候选凭据副本供解析与采用；解析失败时旧会话凭据未受触碰，副本在 finally 清零
                ownedPassword = passwordChars?.copyOf()
                ownedKeyFile = keyFileData?.copyOf()
                // 文件总长已知（本地 File）⇒ 载荷段确定进度；SAF 等未知长度流传 null 退分段不确定
                val totalBytes = associatedFile?.length()?.takeIf { it > 0 }
                val db = inputStreamProvider().use { fis ->
                    // ISSUE-P2-24：大附件在解析期流式落盘（binaryStore 为空则行为与既往一致）
                    // ISSUE-P1-631：捕获流套在解析链最外层——解析器消费什么就镜像什么，
                    // 不重读文件、不重加密；超出留存上限只放弃捕获，不影响本次解析
                    val capturing = PersistedBaselineCapturingInputStream(fis, MAX_PERSISTED_BASELINE_BYTES)
                    baselineCapture = capturing
                    spillTransaction.load(capturing, ownedPassword, ownedKeyFile, totalBytes, progress)
                        .also { candidate = it }
                }
                candidate = db

                // ENG-20261027-02：候选读取、凭据验证与附件事务均成功后，才在临界区内释放旧会话并替换为活动会话
                releaseCurrentSession(candidate)
                core.activeFile = associatedFile
                core.activePathIdentifier = pathIdentifier
                core.saveWriter = saveWriter
                core.trackedSaveWriter = trackedSaveWriter
                core.credentialWriteUncertain.value = false
                core.readOnlyMode = readOnly
                credentials.adoptOwnedCredentials(ownedPassword, ownedKeyFile)
                ownedPassword = null
                ownedKeyFile = null

                core.database.value = db
                core.adoptCredentialTicket(candidateTicket)
                core.state.value = DatabaseSession.SessionState.OPENED
                spillTransaction.commit()
                // ISSUE-P1-631：登记本次版本的持久化基线（克隆归会话；捕获缓冲随即清零）
                val captured = baselineCapture?.capturedBytes()
                baselineCapture?.wipe()
                try {
                    core.retainPersistedBaselineBestEffort(captured, pathIdentifier)
                } finally {
                    captured?.fill(0)
                }
                candidate = null // 数据库与 spill key 已由活动会话接管
                KdbxResult.Success(Unit)
            } catch (t: Throwable) {
                // ISSUE-P1-631：失败 / 取消路径清零捕获缓冲（整库密文的第二份副本，不得留给 GC）
                baselineCapture?.wipe()
                if (candidate != null && core.database.value === candidate) {
                    core.database.value = null
                }
                suppressCleanupFailure(t) { candidate?.clearSensitiveData() }
                suppressCleanupFailure(t) { spillTransaction.purge() }
                // ENG-20261027-02：若已有活动会话（例如重载或换库失败），保持原树、脏态、文件通道与凭据不变；
                // 仅在无活动会话（初次打开失败）时将状态置为 LOCKED 并清理引用。
                if (core.database.value == null) {
                    suppressCleanupFailure(t) { credentials.clear() }
                    core.invalidateSessionTicket()
                    core.activeFile = null
                    core.activePathIdentifier = null
                    core.saveWriter = null
                    core.trackedSaveWriter = null
                    core.readOnlyMode = false
                    core.state.value = DatabaseSession.SessionState.LOCKED
                }
                if (t is CancellationException) throw t
                // ISSUE-P3-368：失败清进度（UI 随 isLoading 回落隐藏，不留半程残值）
                progress(null)
                // ISSUE-P2-521：文件损坏（KdbxCorruptFileException 族）与凭据错误分型——
                // 前者可引导用户从滚动备份恢复，后者不得出现该入口
                val code = if (t is com.keepasskey.database.exception.KdbxCorruptFileException) {
                    KdbxError.UNLOCK_CORRUPT_FILE
                } else {
                    KdbxError.UNLOCK_FAILED
                }
                KdbxResult.Failure(t, code = code)
            } finally {
                ownedPassword?.fill('0')
                ownedKeyFile?.fill(0)
            }
        }
    }
}
