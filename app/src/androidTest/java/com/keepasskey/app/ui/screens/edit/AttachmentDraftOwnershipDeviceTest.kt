package com.keepasskey.app.ui.screens.edit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.repository.AttachmentMergePolicy
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ISSUE-P2-600：设备文件系统上的草稿导入、会话交付、锁定擦除与重开验证。
 *
 * 只操作本测试创建的合成库与假附件。断言草稿数组归零后，会话拥有的独立副本仍可落盘，
 * 且真实重开后附件字节逐项一致。
 */
@RunWith(AndroidJUnit4::class)
class AttachmentDraftOwnershipDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 导入附件草稿锁定擦除后会话副本仍可重开() = runBlocking {
        val expectedBytes = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
        val draftBytes = expectedBytes.clone()
        var state = EntryEditUiState()
        val draft = EntryEditAttachmentDraft(
            strings = StringsProvider { _, _ -> "now" },
            state = { state },
            update = { transform -> state = transform(state) }
        )
        val file = File(context.cacheDir, "p2600-attachment-${System.nanoTime()}.kdbx")
        val password = "Attachment-Draft-Device-2026".toCharArray()
        val session = DatabaseSession()
        val reopened = DatabaseSession()
        val baseEntry = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Attachment draft device test"))
        )

        try {
            draft.add("synthetic.bin", "64 KiB", draftBytes)
            val uiEntry = UiVaultEntry(
                id = baseEntry.id.toString(),
                title = baseEntry.title,
                username = "synthetic-user",
                url = "",
                attachments = state.attachments
            )
            val sessionAttachments = AttachmentMergePolicy.merge(baseEntry, uiEntry)
            assertNotSame("会话必须拥有附件数据的独立数组", draftBytes, sessionAttachments.single().data)

            assertTrue(session.create(file, "P2-600 device attachment draft", password, useArgon2 = false).isSuccess)
            session.saveEntry(baseEntry.copy(attachments = sessionAttachments))
            assertTrue("会话内附件保存必须成功", session.save() is KdbxResult.Success)

            draft.wipeDrafts()
            assertTrue("编辑草稿数组必须在锁定清理时归零", draftBytes.all { it == 0.toByte() })
            val inMemoryAttachment = session.databaseFlow.value!!.rootGroup.allEntries().single().attachments.single()
            assertArrayEquals("清理草稿不得破坏会话独立副本", expectedBytes, inMemoryAttachment.data)

            session.lock()
            val reopenPassword = password.clone()
            try {
                assertTrue("设备正式文件必须能重开", reopened.open(file, reopenPassword).isSuccess)
                val reopenedAttachment = reopened.databaseFlow.value!!.rootGroup.allEntries().single().attachments.single()
                assertArrayEquals("重开后附件字节必须逐项一致", expectedBytes, reopenedAttachment.data)
            } finally {
                reopenPassword.fill('\u0000')
            }
        } finally {
            session.lock()
            reopened.lock()
            draft.wipeDrafts()
            password.fill('\u0000')
            expectedBytes.fill(0)
            file.delete()
        }
    }
}
