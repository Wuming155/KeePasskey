package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.testutil.stripCommentsOnly
import com.keepasskey.database.xml.AttachmentSizeLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P3-576`：自定义图标上传通道的尺寸双闸门纯 JVM 锁定；
 * `ISSUE-P3-617`：附件通道同型接线一并由本类末两例锁定。
 *
 * 覆盖：
 * ① 申报长度超过图标上限 ⇒ 拒绝（读前闸门判据）——「超限不读流」由源码静态守卫（本类末例）锁定；
 * ② 申报长度未超限 / 恰好等于上限 ⇒ 放行（与附件通道同为 `>` 判）；
 * ③ 申报长度未知（描述符 null 或 `UNKNOWN_LENGTH` = -1）⇒ 放行，交累计上限兜底；
 * ④ 累计上限由 `readBoundedBytes` 在读取中途封顶（`ISSUE-P3-617` 起替代旧「整流后判实读大小」
 *    形态——旧形态先分配完整超限缓冲；超限抛 `StreamLimitExceededException`，内部缓冲已清零）；
 * ⑤ 上限同源派生（恰为附件上限 1/4）且远小于附件上限；
 * ⑥ 源码守卫：图标选择器内「申报长度判定先于读流」——顺序反转即「先读爆内存再判超限」；
 *    且读流一律经 `readBoundedBytes`（无 `readBytes()` 整流残留），超限走图标超限文案。
 *
 * 宿主侧无 Robolectric，构造不出 `ContentResolver`（SAF 流不可在 JVM 复现）⇒ 判定与接线分别以
 * 纯函数与源码静态守卫锁定；设备侧走查按本批任务禁令未执行（见批次文档 §491.5）。
 */
class EntryEditIconUploadGateTest {

    @Test
    fun `申报长度超过图标上限时拒绝`() {
        assertTrue(exceedsCustomIconUploadLimit(CAP + 1))
        // 附件上限量级必然远超图标上限（防「顺手复用 64 MiB」回退到无鉴别力的上界）
        assertTrue(exceedsCustomIconUploadLimit(AttachmentSizeLimits.MAX_ATTACHMENT_BYTES))
    }

    @Test
    fun `申报长度未超限与边界值恰好等于上限时放行`() {
        assertFalse(exceedsCustomIconUploadLimit(CAP))
        assertFalse(exceedsCustomIconUploadLimit(CAP - 1))
        assertFalse(exceedsCustomIconUploadLimit(0L))
    }

    @Test
    fun `申报长度未知时放行并交由读后闸门兜底`() {
        assertFalse(exceedsCustomIconUploadLimit(null))
        // AssetFileDescriptor.UNKNOWN_LENGTH = -1（描述符申报不了长度）
        assertFalse(exceedsCustomIconUploadLimit(-1L))
    }

    @Test
    fun `读后实读大小超限时拒绝且边界与申报口径一致`() {
        assertTrue(exceedsCustomIconUploadLimit((CAP + 1).toInt()))
        assertFalse(exceedsCustomIconUploadLimit(CAP.toInt()))
        assertFalse(exceedsCustomIconUploadLimit(0L))
    }

    @Test
    fun `图标上限与附件上限同源派生且远小于`() {
        assertEquals(
            "图标上限须由附件上限派生（同源，禁另立字面量）",
            AttachmentSizeLimits.MAX_ATTACHMENT_BYTES / 4,
            AttachmentSizeLimits.MAX_CUSTOM_ICON_UPLOAD_BYTES
        )
        assertTrue(
            "图标上限须远小于附件上限",
            AttachmentSizeLimits.MAX_CUSTOM_ICON_UPLOAD_BYTES * 4 <= AttachmentSizeLimits.MAX_ATTACHMENT_BYTES
        )
    }

    @Test
    fun `图标选择器源码中申报长度判定先于读流且读流带累计上限`() {
        val picker = stripCommentsOnly(readSource(PICKERS_SOURCE))
            .substringAfter("fun rememberCustomIconPicker")
        val declaredIdx = picker.indexOf("openAssetFileDescriptor")
        val readIdx = picker.indexOf("openInputStream")
        assertTrue("图标选择器缺少读前申报长度拦截（openAssetFileDescriptor）", declaredIdx >= 0)
        assertTrue("图标选择器缺少读流调用（openInputStream，源码形态已变更）", readIdx >= 0)
        assertTrue(
            "申报长度拦截必须先于读流——顺序反转即「读爆内存之后才判超限」",
            declaredIdx < readIdx
        )
        assertTrue(
            "申报长度判定未走纯函数 exceedsCustomIconUploadLimit(declaredLength)",
            picker.contains("exceedsCustomIconUploadLimit(declaredLength)")
        )
        assertTrue(
            "读流必须经带累计上限的 readBoundedBytes（ISSUE-P3-617：readBytes() 整流" +
                "会在累计上限生效前分配完整超限缓冲）",
            picker.contains("readBoundedBytes(MAX_CUSTOM_ICON_UPLOAD_BYTES)")
        )
        assertTrue(
            "图标选择器不得残留无上限整流 readBytes()",
            !picker.contains(".readBytes()")
        )
        assertTrue(
            "累计超限必须捕获 StreamLimitExceededException 并拒绝（内部缓冲由读取器清零）",
            picker.contains("StreamLimitExceededException")
        )
        assertTrue("超限提示未走图标超限文案 iconTooLargeMessage()", picker.contains("iconTooLargeMessage()"))
    }

    @Test
    fun `附件选择器源码中读流同样带累计上限`() {
        // ISSUE-P3-617：附件通道与图标通道同型（读前申报长度快拒 + 累计上限兜底），
        // 旧「整流后判 bytes.size」形态先分配完整超限缓冲，本守卫锁死新形态。
        val picker = stripCommentsOnly(readSource(PICKERS_SOURCE))
            .substringAfter("fun rememberAttachmentPicker")
            .substringBefore("fun rememberDecodedCustomIcons")
        assertTrue("附件选择器缺少读前申报长度拦截", picker.contains("openAssetFileDescriptor"))
        assertTrue(
            "附件读流必须经带累计上限的 readBoundedBytes（上限为附件上界）",
            picker.contains("readBoundedBytes(MAX_ATTACHMENT_BYTES)")
        )
        assertTrue(
            "附件选择器不得残留无上限整流 readBytes()",
            !picker.contains(".readBytes()")
        )
        assertTrue(
            "附件累计超限必须捕获 StreamLimitExceededException 并拒绝",
            picker.contains("StreamLimitExceededException")
        )
        assertTrue("附件超限提示未走 attachmentTooLargeMessage()", picker.contains("attachmentTooLargeMessage()"))
    }

    /** 源码全文（路径相对仓库根；app 模块测试工作目录为 app/，向上回溯定位仓库根）。 */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        /** 被测上界（直取生产常量，禁在测试里另写一份数字）。 */
        val CAP: Long = AttachmentSizeLimits.MAX_CUSTOM_ICON_UPLOAD_BYTES

        const val PICKERS_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditPickers.kt"

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先（同 SensitiveWindowHardeningTest 口径）。 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
