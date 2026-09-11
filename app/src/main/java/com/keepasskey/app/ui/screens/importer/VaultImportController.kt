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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val debugLog: DebugLogBuffer
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutableUiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)

    /** 导入状态流（设置页直接 collect）。 */
    val uiState: StateFlow<ImportUiState> = mutableUiState.asStateFlow()

    /**
     * 启动一次导入：由设置页在 SAF 选择器返回 [uri] 后调用。
     *
     * 幂等保护：已有导入进行中时忽略重复调用（避免并发写同一库）。
     */
    fun startImport(
        source: ImportSource,
        uri: Uri,
        policy: ImportConflictPolicy = ImportConflictPolicy.SKIP_EXISTING
    ) {
        if (mutableUiState.value is ImportUiState.Parsing) return
        mutableUiState.value = ImportUiState.Parsing(source)
        scope.launch { mutableUiState.value = runImport(source, uri, policy) }
    }

    /** 报告展示完毕/用户取消后回到空闲态。 */
    fun reset() {
        mutableUiState.value = ImportUiState.Idle
    }

    /** [source] 对应解析器支持的扩展名（小写，不含点）；未注册时返回空集。 */
    fun acceptedExtensions(source: ImportSource): Set<String> =
        registry.find(source)?.supportedExtensions.orEmpty()

    private suspend fun runImport(
        source: ImportSource,
        uri: Uri,
        policy: ImportConflictPolicy
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
        var batch: ImportBatch? = null
        return try {
            when (val parsed = importer.parse(bytes, fileName ?: EMPTY_FILE_NAME)) {
                is KdbxResult.Failure -> {
                    // 异常类型 + 静态文案归入诊断日志（不含文件内容）：设备侧 SAX 行为差异
                    // 只能靠此线索定位（宿主 JVM 与 Android 解析器实现不同）。
                    debugLog.warn(
                        TAG,
                        "导入解析失败: ${parsed.error.javaClass.name}: ${parsed.error.message ?: "（无消息）"}"
                    )
                    ImportUiState.Failed(source, ImportFailureReason.classify(parsed.error))
                }
                is KdbxResult.Success -> {
                    batch = parsed.data
                    when (val persisted = vaultImporter.persist(parsed.data, policy)) {
                        is KdbxResult.Failure -> {
                            debugLog.warn(
                                TAG,
                                "导入落库失败: ${persisted.error.javaClass.name}: ${persisted.error.message ?: "（无消息）"}"
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
