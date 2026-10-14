package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-600：编辑页附件草稿的所有权与擦除闭环。
 *
 * 守护：同名替换 / 移除 / 锁定销毁只擦除**草稿独占**（`data != null`）的数组，
 * 已落库附件投影（`data == null`）不被触碰。
 */
class AttachmentDraftOwnershipTest {

    private var state = EntryEditUiState()

    private val draft = EntryEditAttachmentDraft(
        strings = StringsProvider { _, _ -> "now" },
        state = { state },
        update = { transform -> state = transform(state) }
    )

    @Test
    fun `同名替换擦除草稿自有的旧数组且保留新数组`() {
        val old = "OLD".toByteArray()
        draft.add("a.bin", "3 B", old)

        val fresh = "NEWDATA".toByteArray()
        draft.add("a.bin", "7 B", fresh)

        assertTrue("被替换的旧草稿数组必须归零", old.all { it == 0.toByte() })
        assertEquals(1, state.attachments.size)
        assertTrue(state.attachments.single().data!!.contentEquals(fresh))
    }

    @Test
    fun `移除擦除草稿自有数组`() {
        val data = "SECRET".toByteArray()
        draft.add("a.bin", "6 B", data)
        val id = state.attachments.single().id

        draft.remove(id)

        assertTrue("被移除的草稿数组必须归零", data.all { it == 0.toByte() })
        assertTrue(state.attachments.isEmpty())
    }

    @Test
    fun `锁定销毁擦除全部草稿自有数组并移除草稿`() {
        val a = "AAA".toByteArray()
        val b = "BBBB".toByteArray()
        draft.add("a.bin", "3 B", a)
        draft.add("b.bin", "4 B", b)

        draft.wipeDrafts()

        assertTrue(a.all { it == 0.toByte() })
        assertTrue(b.all { it == 0.toByte() })
        assertTrue("草稿应从 UI 态移除", state.attachments.isEmpty())
    }

    @Test
    fun `已落库附件投影不被擦除也不被移除`() {
        state = state.copy(
            attachments = listOf(
                UiAttachment(id = "x", fileName = "p.bin", fileSizeFormatted = "1 B", refIndex = 0, data = null)
            )
        )

        draft.wipeDrafts()

        assertEquals("库内投影（data == null）不得被移除", 1, state.attachments.size)
        assertNull(state.attachments.single().data)
    }

    @Test
    fun `空文件拒绝且不改动既有草稿`() {
        draft.add("a.bin", "3 B", "KEEP".toByteArray())
        val before = state.attachments.size

        draft.add("b.bin", "0 B", ByteArray(0))

        assertEquals(before, state.attachments.size)
    }
}
