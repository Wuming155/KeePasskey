package com.keepasskey.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ISSUE-P1-631`（读取版本钉扎）：**ABC / ABA 与弱元数据**专项验证。
 *
 * 背景：钉扎早期版本只比对 size / mtime。SAF provider 不暴露 `last_modified` 时（本仓真机探针
 * 即此情形），判据退化为 **size 单维**——「同尺寸改内容」与「改完再复原 size+mtime」都能骗过它。
 * 该风险不是理论：真机 SAF 用例曾因 provider 缺少 mtime 而必须靠**改尺寸**才能造出可判漂移。
 *
 * 现版本把**整份密文的 SHA-256** 提升为主判据（内容级），本件逐项钉住其行为与**残余盲区**：
 * 1. 同 size、不同内容 ⇒ 必须判拒（元数据判据会误判为「未变」）；
 * 2. ABA：改内容后把 size 与 mtime 双双复原 ⇒ 必须判拒；
 * 3. 字节完全相同、仅 mtime 变动 ⇒ 放行（合并输入未被替换，拒绝只会制造无谓失败）；
 * 4. 路径标识 / 通道类型变化 ⇒ 判拒（换库 / 换通道）；
 * 5. 文件消失 ⇒ 判拒；
 * 6. **内容不可读出** ⇒ 明确退化到元数据判据，并在此**钉住盲区**（同 size + 同 mtime 的改内容
 *    无法识别）——这是「provider 不给出任何可信身份」时代的残余面，已登记限界，不得声称已消除。
 */
class VaultFileReadPinContentIdentityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun file(name: String, content: ByteArray): File =
        File(tempFolder.newFolder(), name).apply { writeBytes(content) }

    /** 读取时点捕获：内容取自调用方实际用于合并的那份字节 */
    private fun pinOf(path: String, target: File, content: ByteArray?) =
        VaultFileReadPin.capture(
            context = null,
            pathId = path,
            localFile = target,
            content = content,
            reReadContent = { runCatching { target.readBytes() }.getOrNull() }
        )

    @Test
    fun `同尺寸不同内容必须判拒（元数据判据会漏检）`() {
        val original = ByteArray(4096) { 0x11.toByte() }
        val target = file("pin-a.kdbx", original)
        // 路径标识与 File 通道的真实路径一致（生产即 pathId == file.absolutePath）
        val path = target.absolutePath
        val pin = pinOf(path, target, original)

        // 前提：尺寸与（秒级）mtime 都刻意保持不变，只有内容不同
        val mtimeBefore = target.lastModified()
        target.writeBytes(ByteArray(4096) { 0x22.toByte() })
        target.setLastModified(mtimeBefore)
        assertTrue("前提：尺寸必须不变", target.length() == 4096L)

        assertFalse(
            "同尺寸改内容必须判拒（内容级判据；size/mtime 单维会漏检）",
            pin.isStillCurrent(context = null, currentPathId = path, currentFile = target)
        )
        assertTrue(
            "钉住元数据单维的漏检面：size 与 mtime 都没变，单维复核必然放行 ⇒ 内容级判据才是主判据",
            pin.isMetadataStillCurrent(context = null, currentPathId = path, currentFile = target)
        )
    }

    @Test
    fun `ABA 复原尺寸与修改时间仍必须判拒`() {
        val original = ByteArray(2048) { 0x33.toByte() }
        val target = file("pin-aba.kdbx", original)
        val path = target.absolutePath
        val pin = pinOf(path, target, original)
        val originalMtime = target.lastModified()

        // ABA：内容改为他值 → 再恢复成同样的长度与同样的 mtime
        target.writeBytes(ByteArray(2048) { 0x44.toByte() })
        target.setLastModified(originalMtime)
        assertTrue("前提：ABA 后长度复原", target.length() == 2048L)
        assertEquals("前提：ABA 后 mtime 复原", originalMtime, target.lastModified())

        assertFalse(
            "ABA（size + mtime 复原）必须判拒——纯元数据判据无法识别",
            pin.isStillCurrent(context = null, currentPathId = path, currentFile = target)
        )
    }

    @Test
    fun `字节相同仅修改时间变动必须放行`() {
        val original = ByteArray(1024) { 0x55.toByte() }
        val target = file("pin-same.kdbx", original)
        val path = target.absolutePath
        val pin = pinOf(path, target, original)

        // 重写同样字节并把 mtime 推到很早 / 很晚，制造「元数据说变了、内容没变」
        target.writeBytes(original)
        target.setLastModified(target.lastModified() - 60_000L)

        assertTrue(
            "字节相同即合并输入未被替换，必须放行（拒绝只会制造无谓失败）",
            pin.isStillCurrent(context = null, currentPathId = path, currentFile = target)
        )
    }

    @Test
    fun `路径标识变化必须判拒`() {
        val target = file("pin-path.kdbx", ByteArray(64) { 0x66.toByte() })
        val pin = pinOf(target.absolutePath, target, target.readBytes())
        assertFalse(
            "路径标识变化（换库）必须判拒——此处内容完全相同，只有路径不同",
            pin.isStillCurrent(context = null, currentPathId = "/vaults/other.kdbx", currentFile = target)
        )
    }

    @Test
    fun `通道类型变化必须判拒`() {
        val target = file("pin-channel.kdbx", ByteArray(64) { 0x77.toByte() })
        val pin = pinOf(target.absolutePath, target, target.readBytes())
        assertFalse(
            "通道类型变化（File → SAF）必须判拒",
            pin.isStillCurrent(context = null, currentPathId = target.absolutePath, currentFile = null)
        )
    }

    @Test
    fun `文件消失必须判拒`() {
        val target = file("pin-gone.kdbx", ByteArray(64) { 0x88.toByte() })
        val pin = pinOf(target.absolutePath, target, target.readBytes())
        assertTrue("前提：删除成功", target.delete())
        assertFalse(
            "目标文件消失必须判拒",
            pin.isStillCurrent(context = null, currentPathId = target.absolutePath, currentFile = target)
        )
    }

    @Test
    fun `内容不可读出时退化为元数据判据并钉住盲区`() {
        val target = file("pin-blind.kdbx", ByteArray(512) { 0x99.toByte() })
        // content = null 模拟「读取时点内容不可得」——此时没有内容级身份可用
        val pin = pinOf(target.absolutePath, target, content = null)

        // 盲区①：同尺寸改内容 ⇒ 元数据判据判不出（如实钉住，不得声称已消除）
        val mtimeBefore = target.lastModified()
        target.writeBytes(ByteArray(512) { 0xAA.toByte() })
        target.setLastModified(mtimeBefore)
        assertTrue(
            "钉住盲区：内容不可得时，同 size + 同 mtime 的改内容无法识别（已登记限界）",
            pin.isMetadataStillCurrent(context = null, currentPathId = target.absolutePath, currentFile = target)
        )

        // 非盲区：尺寸变化仍可判出
        target.writeBytes(ByteArray(513) { 0xAA.toByte() })
        assertFalse(
            "内容不可得时尺寸变化仍必须判拒",
            pin.isMetadataStillCurrent(context = null, currentPathId = target.absolutePath, currentFile = target)
        )
    }
}
