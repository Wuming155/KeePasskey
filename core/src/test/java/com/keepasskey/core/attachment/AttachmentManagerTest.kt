package com.keepasskey.core.attachment

import com.keepasskey.core.model.KdbxAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * AttachmentManager 附件缓存「用完即删」整改验证 (P3-12 / TASK-28)：
 * 专用子目录隔离、随机不可预测文件名、会话即弃、定向清理（不再全盘删除）。
 */
class AttachmentManagerTest {

    @Test
    fun `导出落在专用子目录且文件名随机不可预测`() {
        val cacheDir = newTempDir()
        try {
            val attachment = KdbxAttachment(name = "report_final.docx", data = byteArrayOf(1, 2, 3))
            val exported = AttachmentManager.exportToCache(cacheDir, attachment)

            assertTrue(exported.exists())
            assertEquals("attachment_view", exported.parentFile!!.name)
            // 随机 UUID 前缀：文件名不得等于（或仅经敏感字符替换后的）附件原名
            assertTrue(exported.name.endsWith("_report_final.docx"))
            assertNotEquals("report_final.docx", exported.name)
            // 内容完整性
            assertTrue(exported.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `中文附件名被安全净化_原名不出现在导出路径`() {
        val cacheDir = newTempDir()
        try {
            val exported = AttachmentManager.exportToCache(
                cacheDir,
                KdbxAttachment(name = "报告.docx", data = byteArrayOf(1))
            )
            assertTrue(exported.exists())
            // 敏感字符净化：中文名不得以原名形态进入导出路径
            assertFalse(exported.name.contains("报告"))
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `再次导出时会话即弃_清除上一轮遗留明文`() {
        val cacheDir = newTempDir()
        try {
            val first = AttachmentManager.exportToCache(
                cacheDir,
                KdbxAttachment(name = "a.bin", data = byteArrayOf(0x0A))
            )
            assertTrue(first.exists())

            val second = AttachmentManager.exportToCache(
                cacheDir,
                KdbxAttachment(name = "b.bin", data = byteArrayOf(0x0B))
            )
            assertTrue(second.exists())
            assertFalse("上一轮导出明文必须已被清除", first.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun deleteExported_用完即删() {
        val cacheDir = newTempDir()
        try {
            val exported = AttachmentManager.exportToCache(
                cacheDir,
                KdbxAttachment(name = "secret.txt", data = "top secret".toByteArray())
            )
            assertTrue(exported.exists())

            AttachmentManager.deleteExported(exported)
            assertFalse("用完即删后文件必须立即消失", exported.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun cleanCache_仅清理专用子目录_不误删其它缓存文件() {
        val cacheDir = newTempDir()
        try {
            val exported = AttachmentManager.exportToCache(
                cacheDir,
                KdbxAttachment(name = "x.bin", data = byteArrayOf(1))
            )
            // 其它组件在同一 cacheDir 下的无关文件（如 OkHttp 缓存等）
            val unrelated = File(cacheDir, "unrelated_cache.bin").apply { writeBytes(byteArrayOf(9)) }

            AttachmentManager.cleanCache(cacheDir)

            assertFalse(exported.exists())
            assertTrue("全盘粗暴删除缺陷回归锁：无关缓存文件不得被误删", unrelated.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `连续导出同名附件产生互异的新文件名`() {
        val cacheDir = newTempDir()
        try {
            val exported = AttachmentManager.exportToCache(
                cacheDir,
                KdbxAttachment(name = "same.bin", data = byteArrayOf(1))
            )
            val next = AttachmentManager.exportToCache(
                cacheDir,
                KdbxAttachment(name = "same.bin", data = byteArrayOf(2))
            )
            assertNotEquals(exported.name, next.name)
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}

private fun newTempDir(): File = Files.createTempDirectory("att_cache").toFile()
