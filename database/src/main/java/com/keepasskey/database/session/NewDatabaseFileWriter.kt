package com.keepasskey.database.session

import java.io.File
import com.keepasskey.core.security.SecureFileWrite
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 新建专用提交：先完成唯一临时密文，再以 CREATE_NEW 排他创建目标并复制、fsync。
 * Android SELinux 禁止应用创建硬链接；普通 rename/ATOMIC_MOVE 不能承诺不覆盖。
 * 排他创建仅允许写入本次新目录项；碰撞不改原件、不触碰备份。崩溃可能留下不完整新件。
 * 失败清理只认本任务创建的 inode；没有身份可核实时保留残件，绝不凭存在性删除。
 * 临时目录仅限本应用拥有、其他写入遵守唯一临时名的目录；不防御同 UID 恶意进程。
 */
internal object NewDatabaseFileWriter {
    private val logger = Logger.getLogger(NewDatabaseFileWriter::class.java.name)

    fun write(target: File, writer: (OutputStream) -> Unit) {
        val parent = target.absoluteFile.parentFile ?: throw IOException("缺少新建目录")
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileAlreadyExistsException(target.name)
        }
        val parentCreated = !parent.exists()
        Files.createDirectories(parent.toPath())
        if (parentCreated) SecureFileWrite.restrictToOwnerOnly(parent, isDirectory = true)
        val temp = Files.createTempFile(parent.toPath(), ".vault-create-", ".tmp")
        val identity = Files.readAttributes(temp, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        try {
            SecureFileWrite.openOwnerOnlyOutput(temp.toFile()).use { output ->
                writer(output)
                output.flush()
                output.fd.sync()
            }
            checkOwned(temp.toFile(), identity)
            publishExclusive(temp.toFile(), target)
            syncDirectory(parent)
        } finally {
            // 只有仍是本任务创建的临时件才清理；绝不依据 target.exists() 删除目标。
            try {
                checkOwned(temp.toFile(), identity)
                Files.deleteIfExists(temp)
                syncDirectory(parent)
            } catch (e: Exception) {
                logger.log(Level.WARNING, "新建库临时件清理失败", e)
            }
        }
    }

    /** CREATE_NEW 的存在性检查与创建是同一文件系统操作，两个并发任务仅一个能取得写权。 */
    private fun publishExclusive(temp: File, target: File) {
        val attributes = if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else emptyArray()
        var identity: Any? = null
        var created = false
        var completed = false
        try {
            FileChannel.open(target.toPath(), setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                *attributes).use { output ->
                created = true
                identity = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS).fileKey()
                SecureFileWrite.restrictToOwnerOnly(target, isDirectory = false)
                FileChannel.open(temp.toPath(), StandardOpenOption.READ).use { input ->
                    var position = 0L
                    while (position < input.size()) {
                        val written = input.transferTo(position, input.size() - position, output)
                        if (written <= 0L) throw IOException("新建密文复制未取得进展")
                        position += written
                    }
                }
                output.force(true)
            }
            completed = true
        } finally {
            if (created && !completed && identity != null) {
                try {
                    checkOwned(target, identity)
                    Files.deleteIfExists(target.toPath())
                } catch (error: Exception) {
                    logger.log(Level.WARNING, "无法确认新建残件身份，保留供恢复", error)
                }
            }
        }
    }

    private fun checkOwned(file: File, identity: Any?) {
        val actual = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!actual.isRegularFile || (identity != null && actual.fileKey() != identity)) {
            throw IOException("新建库临时件归属变化")
        }
    }

    private fun syncDirectory(parent: File) {
        try {
            DirectorySync.default.sync(parent)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "新建库目录同步降级", e)
        }
    }
}
