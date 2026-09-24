package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 附件**同名不同内容**的寻址守卫（**ISSUE-P3-295** AC①）。
 *
 * 缺陷背景：`VaultEntrySecretReader.getAttachmentData` 原本按 `attachments.firstOrNull { it.name == fileName }`
 * 取字节，而调用方传的是**文件名** ⇒ 外部库（KeePass XML / Bitwarden / 桌面版）含两个同名附件时，
 * 「导出 A 得 B 的字节、且提示仍报 A」。本仓编辑页按名字视为替换、不自产同名，故该形态**只能来自外部库**。
 *
 * 整改：寻址改为**附件下标 `refIndex`**（`attachments` 列表中的位置），UI 的 `id` 也同步去名字依赖
 * （原为 `"${entryId}_${name}"`，同名即撞 id）。本用例锁定：同名两条各自取到**自己**的字节，
 * 且投影出的两条 `UiAttachment` 不共享 id、`refIndex` 与下标一致。
 */
class AttachmentSameNameAddressingTest {

    private fun readerFor(entry: KdbxEntry): Pair<VaultEntrySecretReader, String> {
        val session = DatabaseSession()
        session.setDatabaseForTesting(
            KdbxDatabase(
                header = KdbxHeader.createDefault(useArgon2 = false),
                rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
            )
        )
        val reader = VaultEntrySecretReader(session, VaultEntryMapper(StringsProvider { _, _ -> "" }))
        return reader to entry.id.toHexString()
    }

    /** 两个**同名**附件，内容不同（合法 Base32 之外的任意字节序列亦可——本用例只验证寻址） */
    private fun entryWithSameNameAttachments(): KdbxEntry = KdbxEntry(
        attachments = listOf(
            KdbxAttachment(name = "shared.bin", refIndex = 0, data = byteArrayOf(1, 2, 3)),
            KdbxAttachment(name = "shared.bin", refIndex = 1, data = byteArrayOf(9, 8, 7, 6))
        )
    )

    @Test
    fun `同名附件按下标分别取到各自的字节`() = runTest {
        val entry = entryWithSameNameAttachments()
        val (reader, entryId) = readerFor(entry)

        val first = reader.getAttachmentData(entryId, 0)
        val second = reader.getAttachmentData(entryId, 1)

        assertArrayEquals("下标 0 应取到第一条同名附件的字节", byteArrayOf(1, 2, 3), first)
        assertArrayEquals("下标 1 应取到第二条同名附件的字节", byteArrayOf(9, 8, 7, 6), second)
        assertNotEquals(
            "两条同名附件的内容必须不同——否则本用例失去判别力",
            first!!.toList(),
            second!!.toList()
        )
    }

    @Test
    fun `下标越界返回 null 而非回落其它同名项`() = runTest {
        val (reader, entryId) = readerFor(entryWithSameNameAttachments())

        assertNull("第二条之后的下标必须返回 null（不得回落到同名项）", reader.getAttachmentData(entryId, 2))
        assertNull("负下标必须返回 null", reader.getAttachmentData(entryId, -1))
    }

    @Test
    fun `投影出的同名附件不共享 id 且 refIndex 与下标一致`() {
        val mapper = VaultEntryMapper(StringsProvider { _, _ -> "" })
        val projected = mapper.mapKdbxEntryToUi(entryWithSameNameAttachments()).attachments

        assertEquals(2, projected.size)
        assertEquals("第一条的 refIndex 应为 0", 0, projected[0].refIndex)
        assertEquals("第二条的 refIndex 应为 1", 1, projected[1].refIndex)
        assertNotEquals(
            "同名附件的投影 id 必须互不相同（原实现由名字派生 ⇒ 撞 id）",
            projected[0].id,
            projected[1].id
        )
    }
}
