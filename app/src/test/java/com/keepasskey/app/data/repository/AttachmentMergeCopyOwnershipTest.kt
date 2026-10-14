package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-600：合并提交时草稿数组 → 会话附件为**独立副本**（不共享借用视图）。
 *
 * 守护：擦除草稿数组（锁定 / 销毁路径）不得影响已提交的会话附件字节。
 */
class AttachmentMergeCopyOwnershipTest {

    @Test
    fun `新附件合并为会话独立副本且擦除草稿不影响会话`() {
        val draftBytes = "FRESH-DRAFT".toByteArray()
        val ui = UiVaultEntry(
            id = "00000000-0000-0000-0000-000000000000",
            title = "t",
            username = "u",
            url = "",
            notes = "",
            attachments = listOf(
                UiAttachment(id = "1", fileName = "n.bin", fileSizeFormatted = "11 B", data = draftBytes)
            ),
            customFields = emptyList()
        )

        val merged = AttachmentMergePolicy.merge(KdbxEntry(), ui)

        val sessionData = merged.single().data
        assertTrue("字节内容必须保留", sessionData.contentEquals(draftBytes))
        assertFalse("会话副本不得与草稿数组同一实例（借用视图共享会被清零）", sessionData === draftBytes)

        draftBytes.fill(0)
        assertTrue(
            "擦除草稿数组后会话附件字节必须完好",
            merged.single().data.contentEquals("FRESH-DRAFT".toByteArray())
        )
    }
}
