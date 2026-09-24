package com.keepasskey.app.data.binary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.exception.KdbxAttachmentSpillMissingException
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ISSUE-P2-310 AC②：附件落盘缓存被「设置 → 清空缓存」（不 force-stop）回收后，
 * 编辑保存必须被**类型化异常**拦截（保存 fail-closed），而不是产出
 * 「字段头声明 N 字节、实际写出 0 字节」的自相矛盾内层头把整库打不开。
 *
 * ## 为什么这一层只能在设备上取证（AVD Pixel_10，禁实体机——§263 卸载风险）
 *
 * 宿主 JVM 拿不到真文件系统的 `cacheDir` 生命周期（系统回收 / 设置页清空只作用于
 * 设备侧），也拿不到真实 `FileBinaryStore`（`cacheDir/attachments` 落盘 + 0600 基线）。
 * 本用例用真实 [FileBinaryStore] 与 `filesDir` 正式库文件跑完整生产链路：
 * 打开（附件 > 1 MiB 阈值 ⇒ 解析期真落盘）→ 清空附件缓存 → 编辑保存 → 断言拦截与文件完好。
 */
@RunWith(AndroidJUnit4::class)
class AttachmentSpillEvictionSaveGuardDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "SpillEvictGuard#2026".toCharArray()
    private val attachmentBytes = 5L * 1024 * 1024

    /** 构造含 5 MiB 附件的正式库文件（存 `filesDir`——「清空缓存」不清 filesDir）。 */
    private fun newVaultFile(): File {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("E1", isProtected = false)),
                        attachments = listOf(KdbxAttachment("big.bin", data = ByteArray(attachmentBytes.toInt()) { (it % 251).toByte() }))
                    )
                )
            )
        )
        // 不传 store：附件内联进文件字节，对齐「此前已保存的正式库文件」形态
        val bytes = ByteArrayOutputStream().also { KdbxFile.save(it, db, password) }.toByteArray()
        val file = File(context.filesDir, "p2310-guard-${System.nanoTime()}.kdbx")
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun 附件缓存被清空后保存被类型化异常拦截且正式库文件完好() = runBlocking<Unit> {
        val store = FileBinaryStore(context)
        store.clear() // 对齐冷启动清理语义，保证空起点

        val vaultFile = newVaultFile()
        val session = DatabaseSession(binaryStore = store)
        assertTrue(
            "夹具打开失败",
            session.open(vaultFile, password) is KdbxResult.Success
        )
        val attachment = session.databaseFlow.value!!.rootGroup.entries.single().attachments.single()
        assertEquals("5 MiB 附件必须在解析期真落盘", attachmentBytes, attachment.size)

        // ① 阳性对照：缓存完好时编辑保存成功（既有语义不变）
        assertTrue("缓存完好时保存必须成功", session.save() is KdbxResult.Success)
        // 基线取阳性对照保存之后（该次保存已按落盘池重写文件字节）
        val originalBytes = vaultFile.readBytes()

        // ② 模拟「设置 → 清空缓存（不 force-stop）」：cacheDir 附件目录整体删除
        store.clear()

        // ③ 再次保存：必须被类型化异常拦截，而非产出矛盾内层头
        val result = session.save()
        assertTrue("保存必须失败: $result", result is KdbxResult.Failure)
        assertTrue(
            "必须为类型化异常而非笼统失败: ${(result as KdbxResult.Failure).error}",
            result.error is KdbxAttachmentSpillMissingException
        )

        // ④ 正式库文件完好：字节未变，且重新打开（附件重新落盘）后附件逐字节可读
        assertArrayEquals("失败保存不得触碰正式库文件", originalBytes, vaultFile.readBytes())
        val reopen = DatabaseSession(binaryStore = FileBinaryStore(context))
        assertTrue(
            "失败保存后正式库必须仍可打开",
            reopen.open(vaultFile, password) is KdbxResult.Success
        )
        val reopenedAttachment = reopen.databaseFlow.value!!.rootGroup.entries.single().attachments.single()
        assertEquals(attachmentBytes, reopenedAttachment.size)
        assertArrayEquals(
            "附件字节必须逐字节完好",
            ByteArray(attachmentBytes.toInt()) { (it % 251).toByte() },
            reopenedAttachment.data
        )

        // 清理夹具
        reopen.lock()
        session.lock()
        store.clear()
        vaultFile.delete()
    }
}
