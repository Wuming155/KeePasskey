package com.keepasskey.core.attachment

import com.keepasskey.core.model.KdbxAttachment
import java.io.File
import java.util.UUID

/**
 * KDBX 条目二进制附件管理器与本地临时查看导出器。
 *
 * P3-12 整改（TASK-28）：附件明文（可能含敏感文档）导出缓存后原实现既不加密也无清理契约，
 * 明文残留在磁盘上直至系统或用户手动清理。现遵循「用完即删」纵深防线：
 * 1. **专用子目录**：所有导出集中写入 `cacheDir/attachment_view/`，与其它缓存隔离，
 *    清理不再全盘粗暴删除（原 cleanCache 会误删 cacheDir 下任意无关文件）；
 * 2. **不可预测文件名**：随机 UUID 前缀杜绝按附件名猜测/劫持导出路径；
 * 3. **会话即弃**：每次新导出前，删除上一轮导出遗留的明文文件（含进程上次异常退出残留），
 *    同一时刻至多保留当前查看中的导出文件；
 * 4. **用完即删 API**：查看方在消费完毕（预览关闭/界面销毁）后调用 [deleteExported] 立即删除；
 * 5. **JVM 退出兜底**：导出文件注册 deleteOnExit，异常路径下亦不跨进程残留。
 * （应用私有 cacheDir 本身受 Android 沙箱保护，加密缓存属可选加固；本轮以即时删除收敛暴露窗口。）
 */
object AttachmentManager {

    /** 附件查看专用缓存子目录名（相对 cacheDir） */
    private const val CACHE_SUBDIR = "attachment_view"

    /**
     * 将附件二进制内容导出至应用私有缓存目录供只读查看。
     * 导出前自动清除上一轮遗留的明文文件（会话即弃），并注册 JVM 退出兜底删除。
     */
    fun exportToCache(cacheDir: File, attachment: KdbxAttachment): File {
        val dir = cacheDirDir(cacheDir)
        // 会话即弃：新导出前删除上一轮（或上次进程）遗留的明文导出
        dir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }

        val safeName = attachment.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        // 随机 UUID 前缀：文件名不可预测，杜绝路径猜测与符号链接劫持
        val outFile = File(dir, "${UUID.randomUUID()}_$safeName")
        outFile.writeBytes(attachment.data)
        outFile.deleteOnExit()
        return outFile
    }

    /**
     * 用完即删：查看方消费完毕后立即删除导出的明文临时文件。
     */
    fun deleteExported(exportedFile: File) {
        exportedFile.delete()
    }

    /**
     * 清理附件查看专用缓存目录（仅限本管理器子目录，不做全盘删除）。
     */
    fun cleanCache(cacheDir: File) {
        cacheDirDir(cacheDir).listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }

    private fun cacheDirDir(cacheDir: File): File =
        File(cacheDir, CACHE_SUBDIR).apply { mkdirs() }
}
