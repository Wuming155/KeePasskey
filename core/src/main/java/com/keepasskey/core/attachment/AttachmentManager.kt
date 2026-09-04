package com.keepasskey.core.attachment

import com.keepasskey.core.model.KdbxAttachment
import java.io.File

/**
 * KDBX 条目二进制附件管理器与本地临时查看导出器。
 */
object AttachmentManager {

    /**
     * 将附件二进制内容导出至应用私有缓存目录供只读查看
     */
    fun exportToCache(cacheDir: File, attachment: KdbxAttachment): File {
        val safeName = attachment.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(cacheDir, safeName)
        outFile.writeBytes(attachment.data)
        return outFile
    }

    /**
     * 清理应用私有缓存中的临时附件文件
     */
    fun cleanCache(cacheDir: File) {
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }
}
