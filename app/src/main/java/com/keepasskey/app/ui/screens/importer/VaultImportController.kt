package com.keepasskey.app.ui.screens.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.keepasskey.app.data.importer.ImportBatch
import com.keepasskey.app.data.importer.ImportConflictPolicy
import com.keepasskey.app.data.importer.ImportFailureReason
import com.keepasskey.app.data.importer.ImportLimits
import com.keepasskey.app.data.importer.ImportLimitExceededException
import com.keepasskey.app.data.importer.ImporterRegistry
import com.keepasskey.app.data.importer.ImportSource
import com.keepasskey.app.data.importer.KdbxMergeController
import com.keepasskey.app.data.importer.VaultImporter
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
import kotlinx.coroutines.withContext
import com.keepasskey.app.coroutines.guardedScope

/**
 * ISSUE-P3-19 交付物 1.3：**导入执行入口**（设置页调用面）。
 *
 * 设计要点（让设置页接线量最小）：控制器自带 [uiState]（[StateFlow]），调用方只需
 * `startImport(source, uri)` 并在 Compose 中 `collectAsState()` 渲染 [ImportReportDialog]，
 * 无需在 `SettingsViewModel` 里再搭一层状态机。
 *
 * 生命周期：`@Singleton` + **自带受控 `CoroutineScope`**（`SupervisorJob + Dispatchers.Default`，
 * 工程规则禁止裸 `GlobalScope`）——导入属关键写操作，不随页面销毁而取消，避免半截落库。
 *
 * 敏感数据纪律：
 * - 文件字节在 `finally` 中清零（`bytes.fill(0)`），尽早移除最原始的明文副本；
 * - 解析产出的 [ImportBatch] 在 `finally` 中兜底 `clear()`（[VaultImporter.persist] 内已清一次）；
 * - 日志只写数据源标识、计数与异常**类型名**，绝不写异常消息（可能夹带路径/字段）。
 */
@Singleton
class VaultImportController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ImporterRegistry,
    private val vaultImporter: VaultImporter,
    private val debugLog: DebugLogBuffer,
    /** ISSUE-P3-384：`.kdbx` 并入执行体（KDBX_MERGE 路径，不经 EntryImporter）。 */
    private val kdbxMergeController: KdbxMergeController
) {

    private val scope = guardedScope(Dispatchers.Default)

    private val mutableUiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)

    /** 导入状态流（设置页直接 collect）。 */
    val uiState: StateFlow<ImportUiState> = mutableUiState.asStateFlow()

    /**
     * ISSUE-P3-384：`.kdbx` 并入状态流（独立控制器；无依赖时恒 Idle）。
     * 设置页把两条状态流合并渲染（明文导入 vs 完整库并入）。
     */
    val mergeUiState: StateFlow<ImportUiState> = kdbxMergeController.uiState

    /** 进行中的导入作业（ISSUE-P2-354 AC④：取消通道持有它执行协程 cancellation）。 */
    private var importJob: Job? = null

    /**
     * 导入代际（ISSUE-P2-354 AC④）：`startImport` / `cancelImport` 各自递增。
     * 过期作业（被取消或已被新一轮替代）的**一切状态回写**——阶段、计数、终态——
     * 一律按代际比对丢弃：否则取消后旧协程的迟到回调会污染新一轮的进度或复活已关闭的报告。
     * 取消与启动都只发生在 UI 线程（`++` 无并发写），`@Volatile` 只为跨线程可见性。
     */
    @Volatile
    private var generation = 0

    /**
     * 启动一次导入：由设置页在 SAF 选择器返回 [uri] 后调用。
     *
     * 幂等保护：已有导入进行中时忽略重复调用（避免并发写同一库）。
     * `ImportSource.KDBX_MERGE` 转交 [kdbxMergeController.beginWithUri]（进入凭据补录，不立即合并）。
     */
    fun startImport(
        source: ImportSource,
        uri: Uri,
        policy: ImportConflictPolicy = ImportConflictPolicy.SKIP_EXISTING
    ) {
        if (source == ImportSource.KDBX_MERGE) {
            kdbxMergeController.beginWithUri(uri)
            return
        }
        if (mutableUiState.value is ImportUiState.Parsing) return
        val gen = ++generation
        mutableUiState.value = ImportUiState.Parsing(source, stage = ImportStage.READING)
        importJob = scope.launch {
            val finalState = runImport(source, uri, policy, gen)
            // 取消与完成可能竞态：代际已变（被取消 / 被新一轮替代）或状态已离开 Parsing 时不回写终态
            if (gen == generation && mutableUiState.value is ImportUiState.Parsing) {
                mutableUiState.value = finalState
            }
        }
    }

    /** ISSUE-P3-384：提交第二库凭据并启动 `.kdbx` 并入（借用语义：本方法透传后由合并控制器清零）。 */
    fun submitMergeCredentials(passwordChars: CharArray, keyFileData: ByteArray? = null) {
        kdbxMergeController.submitCredentials(passwordChars, keyFileData)
    }

    /** ISSUE-P3-384：取消等待凭据 / 进行中的并入。 */
    fun cancelMerge() {
        kdbxMergeController.cancel()
    }

    /** ISSUE-P3-384：并入报告展示完毕后回到空闲。 */
    fun resetMerge() {
        kdbxMergeController.reset()
    }

    /**
     * 取消进行中的导入（ISSUE-P2-354 AC④：协程 cancellation 通道；导入对话框「取消」按钮）。
     *
     * 非 Parsing 态（空闲 / 报告已出）为 no-op。取消语义如实声明：**已落库条目保留**
     * （`ImportPersistRun` 单条保存原子，不留半截文件），未完成部分不再写入；
     * 文件字节与批次敏感数组由 `runImport` 的 `finally` 随取消路径清零。
     * 代际先行递增，确保旧协程即使已越过挂起点也无法再回写任何状态。
     */
    fun cancelImport() {
        if (mutableUiState.value !is ImportUiState.Parsing) return
        generation++
        importJob?.cancel()
        importJob = null
        mutableUiState.value = ImportUiState.Idle
    }

    /** 报告展示完毕/用户取消后回到空闲态。 */
    fun reset() {
        mutableUiState.value = ImportUiState.Idle
    }

    /**
     * 仅当 [generation] 仍是当前代际且状态仍在 [ImportUiState.Parsing] 时，
     * 把状态变换写回（阶段推进 / 落库计数）。过期作业与已取消导入的回调在此被丢弃。
     */
    private fun publishParsing(generation: Int, transform: (ImportUiState.Parsing) -> ImportUiState.Parsing) {
        if (generation != this.generation) return
        mutableUiState.update { current ->
            if (current is ImportUiState.Parsing) transform(current) else current
        }
    }

    /** [source] 对应解析器支持的扩展名（小写，不含点）；未注册时返回空集。 */
    fun acceptedExtensions(source: ImportSource): Set<String> =
        registry.find(source)?.supportedExtensions.orEmpty()

    private suspend fun runImport(
        source: ImportSource,
        uri: Uri,
        policy: ImportConflictPolicy,
        generation: Int
    ): ImportUiState {
        val importer = registry.find(source)
            ?: return ImportUiState.Failed(source, ImportFailureReason.SOURCE_UNAVAILABLE)
        val fileName = resolveDisplayName(uri)
        if (!extensionAccepted(importer.supportedExtensions, fileName)) {
            return ImportUiState.Failed(source, ImportFailureReason.UNSUPPORTED_FILE)
        }
        val bytes = when (val read = readBytes(uri)) {
            is KdbxResult.Failure -> return ImportUiState.Failed(source, ImportFailureReason.classify(read.error))
            is KdbxResult.Success -> read.data
        }
        // 阶段推进：读取完成 → 解析（READING 段已由 startImport 置好）
        publishParsing(generation) { it.copy(stage = ImportStage.PARSING) }
        var batch: ImportBatch? = null
        return try {
            when (val parsed = importer.parse(bytes, fileName ?: EMPTY_FILE_NAME)) {
                is KdbxResult.Failure -> {
                    // 只归入异常**类型**：设备侧 SAX 解析器实现差异只能靠异常类型定位
                    // （宿主 JVM 与 Android 解析器实现不同）；异常 message 可能回显文件片段 /
                    // 路径等外部输入内容，按日志卫生铁律不得写入日志（ISSUE-P2-69）。
                    debugLog.warn(
                        TAG,
                        "导入解析失败: ${parsed.error.javaClass.name}"
                    )
                    ImportUiState.Failed(source, ImportFailureReason.classify(parsed.error))
                }
                is KdbxResult.Success -> {
                    batch = parsed.data
                    // 阶段推进：解析完成 → 落库（总数 = 本批条目数，计数经 onProgress 逐条推进）
                    publishParsing(generation) {
                        it.copy(stage = ImportStage.PERSISTING, processed = 0, total = parsed.data.entries.size)
                    }
                    when (val persisted = vaultImporter.persist(
                        parsed.data,
                        policy,
                        onProgress = { done, total ->
                            publishParsing(generation) { parsing ->
                                parsing.copy(stage = ImportStage.PERSISTING, processed = done, total = total)
                            }
                        }
                    )) {
                        is KdbxResult.Failure -> {
                            debugLog.warn(
                                TAG,
                                "导入落库失败: ${persisted.error.javaClass.name}"
                            )
                            ImportUiState.Failed(source, ImportFailureReason.classify(persisted.error))
                        }
                        is KdbxResult.Success -> ImportUiState.Done(persisted.data)
                    }
                }
            }
        } finally {
            bytes.fill(0)
            batch?.entries?.forEach { it.clear() }
        }
    }

    /** 读取文件字节（带上限闸门）；失败归一为 [KdbxResult.Failure]。 */
    private suspend fun readBytes(uri: Uri): KdbxResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext KdbxResult.Failure(IOException(OPEN_STREAM_FAILED))
            KdbxResult.Success(stream.use { it.readCapped(ImportLimits.MAX_IMPORT_BYTES) })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            debugLog.warn(TAG, "读取导入文件失败: ${t.javaClass.simpleName}")
            KdbxResult.Failure(t)
        }
    }

    /** 取 SAF 显示名（扩展名校验用）；不可得时返回 null（此时放行，由内容解析 fail-closed）。 */
    private fun resolveDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (t: Throwable) {
        debugLog.warn(TAG, "读取文件名失败: ${t.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "VaultImportController"
        const val EMPTY_FILE_NAME = ""
        const val OPEN_STREAM_FAILED = "无法打开所选文件"
        const val FILE_TOO_LARGE = "所选文件超出导入体积上限"
        const val READ_CHUNK_BYTES = 64 * 1024
        const val EXTENSION_SEPARATOR = '.'

        /**
         * 扩展名闸门：仅在**能取到扩展名**时校验（文件名缺失或无名后缀一律放行，
         * 由解析器按内容 fail-closed——内容判定才是权威，命名只是早期提示）。
         */
        fun extensionAccepted(supported: Set<String>, fileName: String?): Boolean {
            val extension = fileName?.substringAfterLast(EXTENSION_SEPARATOR, "")?.lowercase()
            if (extension.isNullOrEmpty()) return true
            return extension in supported
        }
    }

    /** 带上限的分块读取：超限即 [ImportLimitExceededException]（fail-closed，不吃爆内存）。 */
    private fun InputStream.readCapped(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw ImportLimitExceededException(FILE_TOO_LARGE)
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }
}
