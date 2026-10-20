package com.keepasskey.app.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.sync.SyncSessionState
import com.keepasskey.app.sync.eraseDiscardedDatabase
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.orFallback
import com.keepasskey.app.ui.screens.importer.ImportStage
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.merge.KdbxMerger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.keepasskey.app.coroutines.guardedScope

/**
 * ISSUE-P3-384：从另一 `.kdbx` 导入 / 并入当前库的执行体。
 *
 * AC 对齐：
 * ① **全程密文**：SAF 读到的是密文；第二库以**自己的凭据**在内存 `KdbxFile.load` 解密，
 *   **严禁**解密为中间文件落盘；合并产物经会话原子写盘（当前库自身凭据加密）。
 * ② 合并语义复用 [KdbxMerger]（UUID 三方判定）。
 * ③ 与进行中同步会话互斥：整段「读第二库 → 合并 → 采用 → 保存」在
 *   [SyncSessionState.mutex] 内串行（与同步周期同一把锁）。
 * ④ 合并结果以 pykeepass 对拍验证（探针产物 + 外部脚本，见批次文档）。
 *
 * 合并语义（本批口径，批次文档 §2 有完整论证）：
 * - `base` = 空根（UUID 与当前库根相同，避免根组被误判冲突）；
 * - `local` = 当前会话库；`remote` = 第二库；
 * - 对端**独有**条目 / 分组并入；同 UUID 字段冲突 **KEEP_LOCAL**（不覆盖当前库），
 *   并在报告中如实给出 conflict 计数。
 */
@Singleton
class KdbxMergeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseSession: DatabaseSession,
    private val syncSessionState: SyncSessionState,
    stringsProvider: StringsProvider? = null,
    private val debugLog: DebugLogBuffer,
    private val keyFileAccess: KeyFileAccess? = null
) {
    private val strings: StringsProvider = stringsProvider.orFallback(context)

    private val scope = guardedScope(Dispatchers.Default, "KdbxMergeController")

    private val mutableUiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = mutableUiState.asStateFlow()

    private var mergeJob: Job? = null

    @Volatile
    private var generation = 0

    /** 待合并的 SAF Uri（凭据对话框期间存活）。 */
    private var pendingUri: Uri? = null

    /** 暂存待合并文件显示名（报告定位用，非敏感）。 */
    private var pendingDisplayName: String? = null

    /**
     * 用户选择 `.kdbx` 后进入凭据补录态（不直接合并）。
     * 返回 true 表示已接受该 Uri 并进入等待凭据。
     */
    fun beginWithUri(uri: Uri): Boolean {
        if (mutableUiState.value is ImportUiState.Parsing ||
            mutableUiState.value is ImportUiState.AwaitingMergeCredentials
        ) {
            return false
        }
        pendingUri = uri
        pendingDisplayName = resolveDisplayName(uri)
        mutableUiState.value = ImportUiState.AwaitingMergeCredentials(
            source = ImportSource.KDBX_MERGE,
            displayName = pendingDisplayName
        )
        debugLog.info(TAG, "KDBX 并入已选择文件，等待第二库凭据")
        return true
    }

    /**
     * 提交第二库凭据并执行并入。
     *
     * **所有权与清零（ISSUE-P3-580 立契约，对齐 `docs/architecture/敏感缓冲所有权契约.md` R2）**：
     * 两入参均为 **B 类借用引用**——本方法在**任何路径**（含 `pendingUri == null` 与状态不匹配两个
     * 早退分支）都**不清零原件**；只在本方法内**同步** `copyOf()` 出独立副本交后台 `runMerge` 消费，
     * 副本由 `runMerge` 的 `finally` 清零（副本是**有意**设计：后台消费期不受调用方并发改写影响）。
     * **原件清零责任在调用方**——生产链首环 `SettingsImportPresenter.submitMergeCredentials`
     * 于提交返回后统一 `fill(0)`（本方法返回时副本已独立，与原件无别名面，清零不会波及消费）。
     * 原 KDoc「借用语义：本方法消费后在 finally 清零」为失实声明，本批按实现更正。
     *
     * @param passwordChars 第二库主密码（借用：本方法只读，不清零、不驻留）
     * @param keyFileData 第二库密钥文件字节（可选；借用：同上）
     */
    fun submitCredentials(passwordChars: CharArray, keyFileData: ByteArray? = null) {
        launchMerge(passwordChars, keyFileData, keyFileUri = null)
    }

    /** 所选密钥文件按现有无大小上限契约读取；失败不调用解密或合并。 */
    fun submitCredentialsFromUri(passwordChars: CharArray, keyFileUri: Uri?) {
        launchMerge(passwordChars, keyFileData = null, keyFileUri = keyFileUri)
    }

    private fun launchMerge(passwordChars: CharArray, keyFileData: ByteArray?, keyFileUri: Uri?) {
        val uri = pendingUri
        if (uri == null) {
            // ISSUE-P3-580：借用引用——原件清零由调用方收口，早退分支同样不代擦
            mutableUiState.value = ImportUiState.Idle
            return
        }
        val current = mutableUiState.value
        if (current !is ImportUiState.AwaitingMergeCredentials) {
            return
        }
        val gen = ++generation
        mutableUiState.value = ImportUiState.Parsing(
            source = ImportSource.KDBX_MERGE,
            stage = ImportStage.READING
        )
        val pwd = passwordChars.copyOf()
        var key = keyFileData?.copyOf()
        // 立即进入 try/finally，确保用户立刻取消时也会清理已复制的秘密。
        mergeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (keyFileUri != null) {
                    when (val read = keyFileAccess?.read(keyFileUri.toString()) ?: KeyFileReadResult.Unreadable) {
                        is KeyFileReadResult.Success -> key = read.bytes
                        else -> {
                            if (gen == generation) {
                                mutableUiState.value = ImportUiState.Failed(ImportSource.KDBX_MERGE, ImportFailureReason.KEY_FILE)
                            }
                            return@launch
                        }
                    }
                }
                val finalState = runMerge(uri, pwd, key, gen)
                if (gen == generation && mutableUiState.value is ImportUiState.Parsing) {
                    mutableUiState.value = finalState
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                debugLog.warn(TAG, "并入凭据读取失败: ${e.javaClass.simpleName}")
                if (gen == generation) {
                    mutableUiState.value = ImportUiState.Failed(ImportSource.KDBX_MERGE, ImportFailureReason.KEY_FILE)
                }
            } finally {
                pwd.fill('0')
                key?.fill(0)
            }
        }
    }

    fun cancel() {
        val current = mutableUiState.value
        if (current !is ImportUiState.Parsing && current !is ImportUiState.AwaitingMergeCredentials) {
            return
        }
        generation++
        mergeJob?.cancel()
        mergeJob = null
        pendingUri = null
        mutableUiState.value = ImportUiState.Idle
    }

    fun reset() {
        generation++
        mergeJob?.cancel()
        pendingUri = null
        pendingDisplayName = null
        mutableUiState.value = ImportUiState.Idle
    }

    private suspend fun runMerge(
        uri: Uri,
        passwordChars: CharArray,
        keyFileData: ByteArray?,
        generation: Int
    ): ImportUiState {
        publishParsing(generation) { it.copy(stage = ImportStage.READING) }
        return try {
            val bytes = when (val read = readCapped(uri)) {
                is KdbxResult.Failure ->
                    return failMerge(generation, read.error.javaClass.simpleName)
                is KdbxResult.Success -> read.data
            }
            publishParsing(generation) { it.copy(stage = ImportStage.PARSING) }
            try {
                val otherDb = try {
                    KdbxFile.load(
                        ByteArrayInputStream(bytes),
                        passwordChars,
                        keyFileData,
                        null
                    )
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    debugLog.warn(TAG, "第二库打开失败: ${t.javaClass.simpleName}")
                    return failMerge(generation, t.javaClass.simpleName)
                }

                publishParsing(generation) { it.copy(stage = ImportStage.PERSISTING, processed = 0, total = 1) }

                // AC③：与同步会话互斥/串行
                val outcome = syncSessionState.mutex.withLock {
                    mergeUnderSessionLock(otherDb)
                }

                when (outcome) {
                    is KdbxResult.Success -> {
                        pendingUri = null
                        pendingDisplayName = null
                        ImportUiState.Done(outcome.data)
                    }
                    is KdbxResult.Failure -> {
                        debugLog.warn(TAG, "KDBX 并入落库失败: ${outcome.error.javaClass.simpleName}")
                        failMerge(generation, outcome.error.javaClass.simpleName)
                    }
                }
            } finally {
                bytes.fill(0)
            }
        } finally {
            passwordChars.fill('0')
            keyFileData?.fill(0)
        }
    }

    /**
     * 在会话互斥锁内完成「空底版三方合并 → 校验-采用 → 原子保存」。
     *
     * ISSUE-P3-395：装配/计数/警告组装下沉 [KdbxMergeOutcomeFactory]。
     * ISSUE-P3-397：对端树擦除必须按**身份集合**走 [eraseDiscardedDatabase]——
     * 合并树对单侧独有对象复用对端 `ProtectedString` 实例，裸 `clearSensitiveData()`
     * 会清零会话库仍在引用的字段（§9.6 #19 同型）。
     */
    private suspend fun mergeUnderSessionLock(otherDb: KdbxDatabase): KdbxResult<ImportOutcome> {
        val localDb = databaseSession.databaseFlow.value
        if (localDb == null) {
            otherDb.clearSensitiveData()
            return KdbxResult.Failure(
                IllegalStateException("无活动数据库"),
                strings.get(R.string.kdbx_merge_failed_no_vault)
            )
        }
        if (databaseSession.isReadOnly) {
            otherDb.clearSensitiveData()
            return KdbxResult.Failure(
                IllegalStateException("库处于只读态"),
                strings.get(R.string.kdbx_merge_failed_readonly)
            )
        }
        var adoptedDb: KdbxDatabase? = null
        try {
            val (baseLite, localLite, remoteLite) =
                KdbxMergeOutcomeFactory.buildMergeLites(localDb, otherDb)
            val merged = KdbxMerger.mergeDatabases(baseLite, localLite, remoteLite)
            val delta = KdbxMergeOutcomeFactory.mergeDelta(
                localRoot = localDb.rootGroup,
                localCustomIcons = localDb.customIcons,
                merged = merged
            )

            val replacement = localDb.copy(
                rootGroup = merged.mergedRoot,
                deletedObjects = merged.mergedDeletedObjects,
                customIcons = merged.mergedCustomIcons
            )
            val adopted = databaseSession.adoptDatabaseIfUnchanged(
                expectedAtCycleStart = localDb,
                replacement = replacement
            )
            if (!adopted) {
                // 未采用：merged 与 otherDb 均无存活别名于会话树
                eraseDiscardedDatabase(otherDb, live = localDb)
                return KdbxResult.Failure(
                    IllegalStateException("合并窗口内会话树已变化"),
                    strings.get(R.string.kdbx_merge_failed_conflict_window)
                )
            }
            adoptedDb = replacement

            val saveResult = databaseSession.save()
            if (saveResult is KdbxResult.Failure) {
                // 保存失败：内存树已被采用但未落盘——保留内存树，失败原因上浮（与漂移合并同口径）
                return KdbxResult.Failure(saveResult.error, strings.get(R.string.kdbx_merge_failed_save))
            }

            val outcome = KdbxMergeOutcomeFactory.mergeOutcome(delta)
            debugLog.info(
                TAG,
                "KDBX 并入完成 entriesAdded=${delta.entriesAdded} groupsAdded=${delta.groupsAdded} " +
                    "conflicts=${delta.conflicts} icons=${delta.customIconsAdded}"
            )
            return KdbxResult.Success(outcome)
        } finally {
            // ISSUE-P3-397：存活侧 = 采用后的会话树（与 SyncConflictAutoMerge 同口径）
            eraseDiscardedDatabase(otherDb, live = adoptedDb ?: databaseSession.databaseFlow.value)
        }
    }

    private fun publishParsing(generation: Int, transform: (ImportUiState.Parsing) -> ImportUiState.Parsing) {
        if (generation != this.generation) return
        mutableUiState.update { current ->
            if (current is ImportUiState.Parsing) transform(current) else current
        }
    }

    private fun failMerge(generation: Int, errorType: String): ImportUiState {
        pendingUri = null
        val reason = when {
            errorType.contains("IOException", ignoreCase = true) -> ImportFailureReason.IO
            errorType.contains("limit", ignoreCase = true) -> ImportFailureReason.LIMIT_EXCEEDED
            errorType.contains("readonly", ignoreCase = true) ||
                errorType.contains("no_vault", ignoreCase = true) -> ImportFailureReason.LOCKED
            errorType.contains("adopt", ignoreCase = true) ||
                errorType.contains("save", ignoreCase = true) ||
                errorType.contains("IllegalState", ignoreCase = true) -> ImportFailureReason.KDBX_MERGE
            else -> ImportFailureReason.MALFORMED
        }
        return ImportUiState.Failed(ImportSource.KDBX_MERGE, reason)
    }

    private suspend fun readCapped(uri: Uri): KdbxResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext KdbxResult.Failure(IOException("无法打开所选文件"))
            KdbxResult.Success(stream.use { it.readCapped(MAX_MERGE_BYTES) })
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            debugLog.warn(TAG, "读取并入文件失败: ${t.javaClass.simpleName}")
            KdbxResult.Failure(t)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun InputStream.readCapped(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("所选文件超出并入体积上限")
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "KdbxMergeController"
        const val MAX_MERGE_BYTES = 128 * 1024 * 1024
    }
}
