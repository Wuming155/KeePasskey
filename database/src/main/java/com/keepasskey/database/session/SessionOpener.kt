package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 会话建立（ISSUE-P3-31 批次 D 结构拆分）。
 *
 * 自 [DatabaseSession] 原样抽出「新建 / 打开 / 打开流」三条入口：统一在 [mutex] 临界区内
 * 完成解密或建库、装配 [SessionCore]（活动文件、写盘通道、只读标志）、缓存凭据并置 OPENED。
 * 失败一律返回 [KdbxResult.Failure]，异常消息文案与拆分前逐字一致。
 */
internal class SessionOpener(
    private val core: SessionCore,
    private val credentials: SessionCredentialCache,
    private val fileWriter: SessionFileWriter,
    private val mutex: Mutex,
    /** ISSUE-P2-24：大附件落盘存储（可选）；为空时解析行为与既往逐字一致。 */
    private val binaryStore: BinaryStore? = null,
    /**
     * ISSUE-P2-77：换库前置释放（由 `DatabaseSession` 注入，语义与 `lock()` 的清理部分对齐）。
     *
     * **顺序硬约束**：必须在 `KdbxFile.load` / 落盘新库**之前**调用——否则
     * `FileBinaryStore.onSessionLocked()` 会把刚为新库落盘的附件一并删除（静默数据损坏）。
     * 由调用方在 [mutex] 临界区内同步执行（不取锁，避免 `lock()` 的互斥重入）。
     */
    private val releaseCurrentSession: () -> Unit = {}
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
    ): KdbxResult<Unit> = mutex.withLock {
        withContext(Dispatchers.Default) {
            // ISSUE-P2-77：建库即换库——先释放旧会话（擦除旧库明文树 + 驱逐派生缓存，
            // 与 `lock()` 语义对齐），再落盘新库。顺序不可颠倒：`FileBinaryStore.onSessionLocked()`
            // 会清空 `cacheDir/attachments`，若在新库附件落盘之后通知即造成静默数据损坏。
            releaseCurrentSession()
            try {
                val header = KdbxHeader.createDefault(
                    cipherUuid = cipherUuid,
                    useArgon2 = useArgon2
                )
                val rootGroup = KdbxGroup(
                    name = name.ifBlank { "Root" },
                    iconId = 48
                )
                val db = KdbxDatabase(
                    header = header,
                    databaseName = name,
                    databaseDescription = "Created by KeePasskey",
                    rootGroup = rootGroup
                )

                // 原子写盘落盘（ISSUE-P2-11：按会话备份偏好决定是否生成 .bak）
                withContext(Dispatchers.IO) {
                    fileWriter.writeAtomicByBackupPreference(file) { os ->
                        KdbxFile.save(os, db, passwordChars, keyFileData)
                    }
                }

                // 缓存主凭据供会话期写回使用
                core.activeFile = file
                core.activePathIdentifier = file.absolutePath
                core.saveWriter = { bytes ->
                    withContext(Dispatchers.IO) {
                        fileWriter.writeAtomicByBackupPreference(file) { os ->
                            os.write(bytes)
                        }
                    }
                }
                core.readOnlyMode = false
                credentials.cachePassword(passwordChars)
                // ISSUE-P3-21：把建库时使用的密钥文件因子纳入会话缓存——保存时必须用同一
                // 复合密钥重新派生（否则写出的库永远打不开），同时使既有导出通道
                // （exportKeyFileBytes）能把这份密钥文件交付用户。
                // 顺序不可颠倒：cachePassword 内部会先清空全部旧缓存。
                credentials.cacheKeyFile(keyFileData)
                core.database.value = db
                core.state.value = DatabaseSession.SessionState.OPENED

                KdbxResult.Success(Unit)
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "创建密码库失败: ${t.message}")
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
        associatedFile = file
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
        associatedFile: File? = null
    ): KdbxResult<Unit> = mutex.withLock {
        withContext(Dispatchers.Default) {
            // ISSUE-P2-77：换库前置释放（顺序硬约束见 [releaseCurrentSession] KDoc）——
            // 必须在 `KdbxFile.load` 之前执行，否则新库附件会被 `FileBinaryStore.onSessionLocked()` 删除。
            releaseCurrentSession()
            try {
                val db = inputStreamProvider().use { fis ->
                    // ISSUE-P2-24：大附件在解析期流式落盘（binaryStore 为空则行为与既往一致）
                    KdbxFile.load(fis, passwordChars, keyFileData, binaryStore)
                }

                core.activeFile = associatedFile
                core.activePathIdentifier = pathIdentifier
                core.saveWriter = saveWriter
                core.readOnlyMode = readOnly
                credentials.cachePassword(passwordChars)
                credentials.cacheKeyFile(keyFileData)

                core.database.value = db
                core.state.value = DatabaseSession.SessionState.OPENED
                KdbxResult.Success(Unit)
            } catch (t: Throwable) {
                KdbxResult.Failure(t, "解锁密码库失败: ${t.message}")
            }
        }
    }
}
