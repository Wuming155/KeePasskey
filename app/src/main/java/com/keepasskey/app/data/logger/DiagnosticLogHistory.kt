package com.keepasskey.app.data.logger

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 诊断日志在应用私有目录的按天留存（默认 3 天，周期由用户在设置页选择）。
 *
 * 背景：可导出缓冲只驻留进程内存，自动填充服务与主进程同进程，系统回收 / 服务崩溃 /
 * 用户划掉应用都会让进程重启，缓冲随之清空。故把**写入时已脱敏**的整行异步追加到
 * `noBackupFilesDir` 的单个归档文件；导出时直接读该文件作为正文，天然覆盖保留周期内
 * 所有进程的链路，不与内存缓冲重复。
 *
 * 保留周期：每行以 `[yyyy-MM-dd]` 开头，启动时与导出时按配置天数裁剪过期行；无法解析
 * 日期的行保守保留，但整体仍受 [MAX_LINES] 行 / [MAX_BYTES] 字节约束。用户主动清除
 * 日志时删除文件。纯 JVM 依赖（无 Android 类）：未 [configure] 时 [snapshotForExport]
 * 返回 null，调用方退回进程内缓冲，单测不受影响。
 */
internal object DiagnosticLogHistory {

    private const val FILE_NAME = "diagnostic_history.log"
    private const val MAX_LINES = 4_000
    private const val MAX_BYTES = 1024 * 1024L
    private const val EXPORT_WAIT_SECONDS = 2L

    @Volatile
    private var directory: File? = null

    @Volatile
    private var retentionDays: Int = 3

    private val lock = Any()

    /** 文件写入串行化：崩溃路径的同步追加与后台 executor 不得交错写出半行。 */
    private val fileLock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DiagnosticLogHistory").apply { isDaemon = true }
    }

    /**
     * 进程启动时调用：同步设置目录与周期（无文件 IO），后台裁剪过期行。
     * 单线程 executor 保证裁剪先于后续 [append]，导出快照能看到全部已提交行。
     */
    fun configure(dir: File, days: Int) {
        synchronized(lock) {
            directory = dir
            retentionDays = days.coerceAtLeast(1)
        }
        val file = File(dir, FILE_NAME)
        executor.execute {
            val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            val kept = retain(lines, days)
            if (kept.size != lines.size) runCatching { atomicWrite(file, kept) }
        }
    }

    /** 设置页调整周期后即时生效（无需等下次进程启动）。 */
    fun setRetentionDays(days: Int) {
        synchronized(lock) { retentionDays = days.coerceAtLeast(1) }
    }

    /** 异步落盘；入参必须是写入时已脱敏的整行，失败静默绝不阻断业务。 */
    fun append(line: String) {
        val file = synchronized(lock) { directory?.let { File(it, FILE_NAME) } } ?: return
        executor.execute {
            runCatching {
                synchronized(fileLock) {
                    runCatching { file.parentFile?.mkdirs() }
                    file.appendText(line + "\n", Charsets.UTF_8)
                    if (file.length() > MAX_BYTES) {
                        val kept = retain(
                            runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()),
                            retentionDays
                        )
                        atomicWrite(file, kept)
                    }
                }
            }
        }
    }

    /**
     * 同步落盘：**只用于进程即将退出**（未捕获异常）的最后一笔记录。
     *
     * 异步 executor 的排队写入来不及在进程被系统回收前完成，崩溃现场会整段落空——正是
     * 「崩溃发生了但归档里没有栈」的成因。本入口在当前线程完成一次有界追加，不读全文件、
     * 不做裁剪（超限由下次启动的 [configure] 或后续 [append] 收敛），失败静默。
     */
    fun appendSync(line: String) {
        val file = synchronized(lock) { directory?.let { File(it, FILE_NAME) } } ?: return
        runCatching {
            synchronized(fileLock) {
                runCatching { file.parentFile?.mkdirs() }
                file.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    /**
     * 导出正文：文件 IO 留在 executor 线程，调用方只等待有界时间。
     * 返回 null 表示未启用磁盘留存（纯 JVM 单测 / 初始化失败），由调用方退回内存缓冲。
     */
    fun snapshotForExport(): String? {
        val file = synchronized(lock) { directory?.let { File(it, FILE_NAME) } } ?: return null
        val days = retentionDays
        return try {
            executor.submit(Callable<String?> {
                if (!file.exists()) return@Callable null
                val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
                retain(lines, days).joinToString("\n")
            }).get(EXPORT_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            null
        }
    }

    /** 用户主动清除日志时一并删除磁盘留存。 */
    fun clear() {
        val file = synchronized(lock) { directory?.let { File(it, FILE_NAME) } } ?: return
        executor.execute { runCatching { file.delete() } }
    }

    /** 按「今天往前 N 天」裁剪，并保留最近 [MAX_LINES] 行；日期不可解析的行保守保留。 */
    private fun retain(lines: List<String>, days: Int): List<String> {
        val cutoff = LocalDate.now().minusDays((days.coerceAtLeast(1) - 1).toLong())
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
        return lines.filter { line ->
            val date = line.substringAfter("[", "").substringBefore("]", "")
            date.length != 10 || date >= cutoff
        }.takeLast(MAX_LINES)
    }

    /** 临时文件 + 原子替换，避免裁剪窗口留下半行。 */
    private fun atomicWrite(file: File, lines: List<String>) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        runCatching {
            temp.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"), Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { runCatching { temp.delete() } }
    }
}
