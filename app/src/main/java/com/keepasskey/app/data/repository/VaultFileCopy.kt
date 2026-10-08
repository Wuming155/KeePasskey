package com.keepasskey.app.data.repository

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 库文件「保留副本」的**命名与落点规则**（`ISSUE-P2-529` AC④）。
 *
 * 唯一真相源：四处破坏性取舍点（库身份覆盖、私有库移除、同名云端导入、冲突策略说明）
 * 共用同一套命名，避免各写一份而漂移。规则：
 * - 副本名 = `<原名> (副本 yyyyMMdd-HHmm).kdbx`，与**源文件同目录**；
 * - 同分钟重名（一分钟内多次另存）时追加序号：`<原名> (副本 yyyyMMdd-HHmm) 2.kdbx`、
 *   `… 3.kdbx`… 直到不冲突。
 *
 * 「同目录」是刻意选择（AC④）：应用私有目录内的库文件另存后仍落在 `filesDir`，而
 * `VaultDatabaseCatalog.buildDatabaseList` **按目录扫描**发现库，故副本无需额外登记即
 * 出现在密码库列表中；同时它仍是**密文 `.kdbx` 字节副本**，以其自身凭据可解，
 * 不引入任何新的明文落盘面（AC②）。
 */
internal object VaultCopyNaming {

    /** 副本命名标记（AC④ 口径；中英同形，便于跨语言检索与真机走查） */
    const val COPY_MARK = "副本"

    /** KDBX 文件扩展名（与全仓 `endsWith(".kdbx", ignoreCase = true)` 口径一致） */
    const val KDBX_SUFFIX = ".kdbx"

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** 由原文件名与时刻派生副本文件名（**纯函数**，不做任何文件系统 I/O，可 JVM 直测） */
    fun copyFileName(originalFileName: String, at: LocalDateTime): String {
        val base = originalFileName.dropKdbxSuffix()
        return "$base ($COPY_MARK ${at.format(TIMESTAMP)})$KDBX_SUFFIX"
    }

    /**
     * 副本落点：与源同目录；同名已存在时按 `(<副本 时刻>) 2.kdbx`、`… 3.kdbx` 递增。
     *
     * [source] 无父目录（相对名 / 裸文件名）时退化为当前目录下的同名字符串——
     * 调用方均为「已知绝对路径的库文件」，该分支只为不抛异常而存在。
     */
    fun uniqueCopyTarget(source: File, at: LocalDateTime): File {
        val dir = source.parentFile ?: return File(copyFileName(source.name, at))
        val base = source.name.dropKdbxSuffix()
        val stamp = at.format(TIMESTAMP)
        var candidate = File(dir, "$base ($COPY_MARK $stamp)$KDBX_SUFFIX")
        var ordinal = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($COPY_MARK $stamp) $ordinal$KDBX_SUFFIX")
            ordinal++
        }
        return candidate
    }

    private fun String.dropKdbxSuffix(): String =
        if (endsWith(KDBX_SUFFIX, ignoreCase = true)) dropLast(KDBX_SUFFIX.length) else this
}

/**
 * 库文件副本的**字节拷贝**（`ISSUE-P2-529`）。
 *
 * 只做「读源 → 写临时件 → 原子改名」一段：失败返回 `null` 且**绝不动源文件**——
 * 调用方（另存后移除的破坏性动作）据此**中止**，绝不出现「副本没落地、原件已删」的形态。
 * 写盘沿用本仓原子写纪律（临时件 → `flush` + `fd.sync()` → `renameTo`）。
 */
internal object VaultFileCopy {

    /** 另存为同目录副本；成功返回副本文件，读/写/落盘任一失败返回 `null`（源文件不受影响）。 */
    fun copyBeside(source: File, at: LocalDateTime = LocalDateTime.now()): File? {
        if (!source.isFile) return null
        val target = VaultCopyNaming.uniqueCopyTarget(source, at)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return try {
            source.inputStream().use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    (output as? FileOutputStream)?.fd?.sync()
                }
            }
            if (tmp.renameTo(target)) target else null
        } catch (_: IOException) {
            null
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
