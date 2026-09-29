package com.keepasskey.app.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.sync.SyncSessionState
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.orFallback
import com.keepasskey.app.ui.screens.importer.ImportStage
import com.keepasskey.app.ui.screens.importer.ImportUiState
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.merge.KdbxDatabaseLite
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val debugLog: DebugLogBuffer
) {
    private val strings: StringsProvider = stringsProvider.orFallback(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * @param passwordChars 第二库主密码（借用语义：本方法消费后在 finally 清零）
     * @param keyFileData 第二库密钥文件（可选；借用语义，消费后清零）
     */
    fun submitCredentials(passwordChars: CharArray, keyFileData: ByteArray? = null) {
        val uri = pendingUri
        if (uri == null) {
            passwordChars.fill('0')
            keyFileData?.fill(0)
            mutableUiState.value = ImportUiState.Idle
            return
        }
        val current = mutableUiState.value
        if (current !is ImportUiState.AwaitingMergeCredentials && current !is ImportUiState.Parsing) {
            passwordChars.fill('0')
            keyFileData?.fill(0)
            return
        }
        val gen = ++generation
        mutableUiState.value = ImportUiState.Parsing(
            source = ImportSource.KDBX_MERGE,
            stage = ImportStage.READING
        )
        val pwd = passwordChars.copyOf()
        val key = keyFileData?.copyOf()
        mergeJob = scope.launch {
            val finalState = runMerge(uri, pwd, key, gen)
            if (gen == generation && mutableUiState.value is ImportUiState.Parsing) {
                mutableUiState.value = finalState
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
     * 第二库敏感树在 finally 中 `clearSensitiveData()`。
     */
    private suspend fun mergeUnderSessionLock(otherDb: KdbxDatabase): KdbxResult<ImportOutcome> {
        val localDb = databaseSession.databaseFlow.value
            ?: return KdbxResult.Failure(
                IllegalStateException("无活动数据库"),
                strings.get(R.string.kdbx_merge_failed_no_vault)
            )
        if (databaseSession.isReadOnly) {
            otherDb.clearSensitiveData()
            return KdbxResult.Failure(
                IllegalStateException("库处于只读态"),
                strings.get(R.string.kdbx_merge_failed_readonly)
            )
        }
        try {
            val localLite = KdbxDatabaseLite(
                rootGroup = localDb.rootGroup,
                deletedObjects = localDb.deletedObjects,
                customIcons = localDb.customIcons
            )
            // base = 空根（UUID 与 local 根相同）：对端独有对象视为新增；
            // 同 UUID 冲突由 KdbxMerger 产出 conflict 清单，此处 KEEP_LOCAL。
            val emptyRoot = localDb.rootGroup.copy(
                entries = emptyList(),
                subgroups = emptyList()
            )
            val baseLite = KdbxDatabaseLite(
                rootGroup = emptyRoot,
                deletedObjects = emptyList(),
                customIcons = emptyList()
            )
            val remoteLite = KdbxDatabaseLite(
                rootGroup = otherDb.rootGroup,
                deletedObjects = otherDb.deletedObjects,
                customIcons = otherDb.customIcons
            )
            val merged = KdbxMerger.mergeDatabases(baseLite, localLite, remoteLite)

            val localEntryIds = localDb.rootGroup.allEntries().map { it.id }.toSet()
            val localGroupIds = localDb.rootGroup.allGroups().map { it.id }.toSet()
            val mergedEntryIds = merged.mergedRoot.allEntries().map { it.id }.toSet()
            val mergedGroupIds = merged.mergedRoot.allGroups().map { it.id }.toSet()
            val entriesAdded = (mergedEntryIds - localEntryIds).size
            val groupsAdded = (mergedGroupIds - localGroupIds).size
            // KEEP_LOCAL：冲突条目在合并树中保留的是本地实例 ⇒ 以「对端独有且被并入」计数
            val conflicts = merged.conflicts.size
            val customIconsBefore = localDb.customIcons.map { it.uuid }.toSet()
            val customIconsAdded = merged.mergedCustomIcons.count { it.uuid !in customIconsBefore }

            // 校验-采用（与同步合并同一原语）
            val adopted = databaseSession.adoptDatabaseIfUnchanged(
                expectedAtCycleStart = localDb,
                replacement = localDb.copy(
                    rootGroup = merged.mergedRoot,
                    deletedObjects = merged.mergedDeletedObjects,
                    customIcons = merged.mergedCustomIcons
                )
            )
            if (!adopted) {
                return KdbxResult.Failure(
                    IllegalStateException("合并窗口内会话树已变化"),
                    strings.get(R.string.kdbx_merge_failed_conflict_window)
                )
            }

            val saveResult = databaseSession.save()
            if (saveResult is KdbxResult.Failure) {
                // 保存失败：内存树已被采用但未落盘——保留内存树，失败原因上浮（与漂移合并同口径）
                return KdbxResult.Failure(saveResult.error, strings.get(R.string.kdbx_merge_failed_save))
            }

            val warnings = mutableListOf<ImportWarning>()
            if (conflicts > 0) {
                warnings += ImportWarning(
                    location = "kdbx-merge",
                    reason = "conflicts_kept_local:$conflicts"
                )
            }
            val outcome = ImportOutcome(
                source = ImportSource.KDBX_MERGE,
                parsed = entriesAdded + groupsAdded + conflicts,
                sourceSkipped = 0,
                imported = entriesAdded,
                updated = 0,
                skipped = conflicts,
                failed = 0,
                movedToRecycleBin = 0,
                warnings = warnings
            )
            // 附带非敏感诊断计数（经 warnings reason 携带，报告 UI 可展示）
            debugLog.info(
                TAG,
                "KDBX 并入完成 entriesAdded=$entriesAdded groupsAdded=$groupsAdded conflicts=$conflicts icons=$customIconsAdded"
            )
            return KdbxResult.Success(outcome)
        } finally {
            // 第二库敏感树必须销毁（AC①：内存路径，不落中间文件）
            otherDb.clearSensitiveData()
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
