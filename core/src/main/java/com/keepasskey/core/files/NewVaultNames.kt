package com.keepasskey.core.files

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** 新建库名称统一规范；展示名不能携带目录、驱动器或父路径。 */
object NewVaultNames {
    const val EXTENSION = ".kdbx"
    const val DEFAULT_NAME = "passwords.kdbx"

    fun normalize(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed != "." && trimmed != "..")
        require(trimmed.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() })
        val stem = if (trimmed.endsWith(EXTENSION, ignoreCase = true)) {
            trimmed.dropLast(EXTENSION.length)
        } else trimmed
        require(stem.isNotBlank() && stem != "." && stem != "..")
        return stem + EXTENSION
    }

    /** 拒绝链接/父目录逃逸；最终排他提交仍由底层完成。 */
    fun target(directory: File, name: String): File {
        val parent = directory.canonicalFile
        val target = File(parent, normalize(name))
        require(target.canonicalFile.parentFile == parent)
        return target
    }

    fun occupied(target: File): Boolean = Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)

    fun suggest(directory: File, requested: String = DEFAULT_NAME): String {
        val normalized = normalize(requested)
        if (!occupied(target(directory, normalized))) return normalized
        val stem = normalized.dropLast(EXTENSION.length)
        var suffix = 1
        while (occupied(target(directory, "$stem ($suffix)$EXTENSION"))) suffix++
        return "$stem ($suffix)$EXTENSION"
    }
}
