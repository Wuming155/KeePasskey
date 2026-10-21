package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryPreviewContentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `密码OTP通行密钥与受保护字段单独变动的生产预览报告一项修改`() = runBlocking {
        for (field in listOf(KdbxConstants.Fields.PASSWORD, KdbxConstants.Fields.OTP, "Passkey.PrivateKey")) {
            exercise { entry -> entry.withField(field, ProtectedString("Synthetic changed", true)) }
        }
        exercise { entry -> entry.copy(customFields = listOf(KdbxCustomField("secret", ProtectedString("Synthetic changed", true)))) }
    }

    @Test fun `等数量附件和等长度历史异内容的生产预览报告修改`() = runBlocking {
        exercise { entry -> entry.copy(attachments = listOf(KdbxAttachment("fixture.bin", data = byteArrayOf(1, 0)))) }
        exercise { entry -> entry.copy(history = listOf(history("Historical-B"))) }
    }

    @Test fun `内容未变的副本显示零修改`() = runBlocking {
        exercise(expectedModified = 0) { it }
    }

    @Test fun `正式库不可读时预览如实标记未知且不改正式文件`() = runBlocking {
        val official = File(temporary.root, "unreadable.kdbx")
        val session = DatabaseSession()
        val password = "Synthetic-Recovery-2026".toCharArray()
        val store = store()
        try {
            assertTrue(session.create(official, "Fixture", password, useArgon2 = false).isSuccess)
            session.saveEntry(baseEntry())
            val bytes = session.exportToBytes().getOrThrow()
            try { assertTrue(store.write(official.path, "Fixture", official.path, bytes)) }
            finally { bytes.fill(0) }
            official.writeBytes(byteArrayOf(2, 3, 5, 7))
            val before = official.readBytes()
            val coordinator = coordinator(store)
            coordinator.refresh()
            val preview = coordinator.preview(coordinator.pending.value.single(), password).getOrThrow()
            assertTrue(preview.officialUnreadable)
            assertEquals(null, preview.modifiedEntries)
            assertArrayEquals(before, official.readBytes())
            assertTrue(store.hasAny())
        } finally { session.close(); password.fill('0') }
    }

    @Test fun `错误凭据不能报告成功预览或清除恢复材料`() = runBlocking {
        val official = File(temporary.root, "wrong-password.kdbx")
        val session = DatabaseSession()
        val password = "Synthetic-Recovery-2026".toCharArray()
        val wrong = "Synthetic-Wrong".toCharArray()
        val store = store()
        try {
            assertTrue(session.create(official, "Fixture", password, useArgon2 = false).isSuccess)
            val bytes = session.exportToBytes().getOrThrow()
            try { assertTrue(store.write(official.path, "Fixture", official.path, bytes)) }
            finally { bytes.fill(0) }
            val before = official.readBytes()
            val coordinator = coordinator(store)
            coordinator.refresh()
            assertTrue(coordinator.preview(coordinator.pending.value.single(), wrong) is KdbxResult.Failure)
            assertArrayEquals(before, official.readBytes())
            assertTrue(store.hasAny())
        } finally { session.close(); password.fill('0'); wrong.fill('0') }
    }

    private suspend fun exercise(expectedModified: Int = 1, change: (KdbxEntry) -> KdbxEntry) {
        val official = File(temporary.root, "content-${System.nanoTime()}.kdbx")
        val session = DatabaseSession()
        val password = "Synthetic-Recovery-2026".toCharArray()
        val store = store()
        try {
            assertTrue(session.create(official, "Fixture", password, useArgon2 = false).isSuccess)
            val entry = baseEntry()
            session.saveEntry(entry)
            assertTrue(session.save().isSuccess)
            val before = official.readBytes()
            session.saveEntry(change(entry))
            val bytes = session.exportToBytes().getOrThrow()
            try { assertTrue(store.write(official.path, "Fixture", official.path, bytes)) }
            finally { bytes.fill(0) }
            val coordinator = coordinator(store)
            coordinator.refresh()
            val target = coordinator.pending.value.single { it.entry.pathIdentifier == official.path }
            val preview = coordinator.preview(target, password).getOrThrow()
            assertFalse(preview.officialUnreadable)
            assertEquals(1, preview.snapshotEntryCount)
            assertEquals(1, preview.officialEntryCount)
            assertEquals(0, preview.addedEntries)
            assertEquals(0, preview.removedEntries)
            assertEquals(expectedModified, preview.modifiedEntries)
            assertArrayEquals(before, official.readBytes())
            assertTrue(store.readPayload(target.entry)?.also { it.fill(0) } != null)
            store.delete(target.entry)
        } finally { session.close(); password.fill('0') }
    }

    private fun baseEntry() = KdbxEntry(
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("Synthetic fixture"),
            KdbxConstants.Fields.PASSWORD to ProtectedString("Synthetic original", true),
            KdbxConstants.Fields.OTP to ProtectedString("Synthetic original OTP", true),
            "Passkey.PrivateKey" to ProtectedString("Synthetic original key", true)
        ),
        customFields = listOf(KdbxCustomField("secret", ProtectedString("Synthetic original", true))),
        attachments = listOf(KdbxAttachment("fixture.bin", data = byteArrayOf(0, 31))),
        history = listOf(history("Historical-A"))
    )

    private fun history(value: String) = KdbxEntry(
        fields = mapOf(KdbxConstants.Fields.PASSWORD to ProtectedString(value, true))
    )

    private fun store() = VaultRecoveryStore(null, DebugLogBuffer()).apply {
        baseDirOverride = File(temporary.root, "recovery").apply { mkdirs() }
    }

    private fun coordinator(store: VaultRecoveryStore) = VaultRecoveryCoordinator(
        null, store, RecoveryAlertStore(null), KeyFileVaultCopyStore(null, null, DebugLogBuffer()), DebugLogBuffer()
    )
}
