package com.keepasskey.app.data.repository

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.data.repository.NewVaultDocumentClaims
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.security.VaultFileDriftGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.KdbxErrorTexts
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ISSUE-P1-601：在 Android DocumentsProvider 上验证统一库写边界的 SAF 写回与取消后重试。 */
@RunWith(AndroidJUnit4::class)
class VaultWriteBoundarySafDeviceTest {

    @Test
    fun saf元数据写回和取消后重试不损坏正式文档() = runBlocking {
        val client = SafCreationProbeClient()
        val context = client.targetContext
        val uri = client.create("p1-601-${System.nanoTime()}.kdbx")
        val password = "P1-601-Saf-Write-2026".toCharArray()
        val seedSession = DatabaseSession()
        val session = DatabaseSession()
        val canceledSession = DatabaseSession()
        val retrySession = DatabaseSession()
        try {
            assertTrue("SAF URI 必须登记归属", NewVaultDocumentClaims.record(context, uri))
            assertTrue(
                "真实 SAF 文档创建失败",
                SafVaultCreation.create(
                    context,
                    StringsProvider { id, args -> context.getString(id, *args) },
                    seedSession,
                    "P1-601 SAF boundary",
                    password,
                    null,
                    CreateVaultPreset.DEFAULT,
                    uri
                ) { _, _ -> KdbxResult.Success(Unit) }.isSuccess
            )

            assertTrue("SAF 会话打开失败", openSaf(context, uri, session, password, cancelBeforeWrite = false).isSuccess)
            val committed = boundary(session, context).commitMeta { it.copy(databaseDescription = "saf-committed") }
            assertTrue("统一写边界必须真实提交 SAF Meta", committed is KdbxResult.Success)
            val committedBytes = requireNotNull(client.state(uri).getByteArray("bytes"))
            assertEquals("写后 SAF 文档必须可由生产 KDBX 读取", "saf-committed", loadDescription(committedBytes, password))

            // SAF writer 可能已进入 rwt 通道但无法证明文档未被截断；会话必须保留旧内存值并封锁后续保存。
            client.setFault(uri, "OPEN_FAIL")
            val uncertain = boundary(session, context).commitMeta { it.copy(databaseDescription = "must-not-publish") }
            assertTrue("无法确认 SAF 写入时须返回失败", uncertain is KdbxResult.Failure)
            val uncertainFailure = uncertain as KdbxResult.Failure
            assertEquals(KdbxError.SAVE_WRITE_UNCERTAIN, uncertainFailure.code)
            assertEquals(
                "失败反馈必须映射到本地化的写入不确定文案",
                context.getString(R.string.err_save_write_uncertain),
                context.getString(KdbxErrorTexts.resIdFor(uncertainFailure.code))
            )
            assertEquals("写入失败不得把候选 Meta 发布到活动会话", "saf-committed", session.databaseFlow.value?.databaseDescription)
            assertEquals("失败后不得伪装为 DIRTY", DatabaseSession.SessionState.OPENED, session.state.value)
            assertTrue("写入结果不确定时必须封锁会话", session.credentialWriteUncertain.value)
            assertArrayEquals("测试提供方在打开失败时保留原正式字节", committedBytes, client.state(uri).getByteArray("bytes"))

            session.close()
            client.setFault(uri, "NONE")

            assertTrue("取消注入会话打开失败", openSaf(context, uri, canceledSession, password, cancelBeforeWrite = true).isSuccess)
            val beforeCancel = requireNotNull(client.state(uri).getByteArray("bytes"))
            var cancellation: CancellationException? = null
            try {
                boundary(canceledSession, context).commitMeta { it.copy(databaseDescription = "must-not-commit") }
            } catch (error: CancellationException) {
                cancellation = error
            }
            assertTrue("SAF 写入前取消必须传播", cancellation != null)
            assertArrayEquals("取消不得改动 SAF 正式文档字节", beforeCancel, client.state(uri).getByteArray("bytes"))
            assertEquals("已证明写入前取消时活动 Meta 不变", "saf-committed", canceledSession.databaseFlow.value?.databaseDescription)
            assertEquals("已证明写入前取消不应污染会话状态", DatabaseSession.SessionState.OPENED, canceledSession.state.value)
            assertEquals("已证明写入前取消无需封锁会话", false, canceledSession.credentialWriteUncertain.value)

            canceledSession.close()
            assertTrue("取消后原 SAF 文档仍可重开", openSaf(context, uri, retrySession, password, cancelBeforeWrite = false).isSuccess)
            val retried = boundary(retrySession, context).commitMeta { it.copy(databaseDescription = "saf-retried") }
            assertTrue("取消后应可经统一边界重试保存", retried is KdbxResult.Success)
            val retriedBytes = requireNotNull(client.state(uri).getByteArray("bytes"))
            assertEquals("重试后的 SAF 产物必须含本轮已提交值", "saf-retried", loadDescription(retriedBytes, password))
        } finally {
            seedSession.close()
            session.close()
            canceledSession.close()
            retrySession.close()
            password.fill('\u0000')
            val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            if (permission != null) {
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            }
            client.cleanupAndEnd()
        }
    }

    private suspend fun openSaf(
        context: android.content.Context,
        uri: Uri,
        session: DatabaseSession,
        password: CharArray,
        cancelBeforeWrite: Boolean
    ): KdbxResult<Unit> = session.openStream(
        pathIdentifier = uri.toString(),
        inputStreamProvider = { context.contentResolver.openInputStream(uri) ?: error("SAF input unavailable") },
        saveWriter = SafVaultCreation.saveWriter(context, uri, "p1-601.kdbx"),
        passwordChars = password,
        trackedSaveWriter = if (cancelBeforeWrite) {
            { _, receipt ->
                receipt.notCommitted()
                throw CancellationException("synthetic writer-before-commit cancellation")
            }
        } else {
            SafVaultCreation.trackedSaveWriter(context, uri, "p1-601.kdbx")
        }
    )

    private fun boundary(session: DatabaseSession, context: android.content.Context): VaultWriteBoundary {
        val log = DebugLogBuffer()
        return VaultWriteBoundary(
            databaseSession = session,
            driftGuard = VaultFileDriftGuard(coordinator = null, context = context),
            strings = StringsProvider { id, args -> context.getString(id, *args) },
            autoLockGuard = AutoLockSessionGuard(session, RealSettingsRepository(context), log),
            logPersistFailure = { log.warn("P1-601", "SAF synthetic save failed (${it.error.javaClass.simpleName})") }
        )
    }

    private fun loadDescription(bytes: ByteArray, password: CharArray): String {
        val copy = password.clone()
        val database = try {
            KdbxFile.load(bytes.inputStream(), copy)
        } finally {
            copy.fill('\u0000')
        }
        return try {
            database.databaseDescription
        } finally {
            database.clearSensitiveData()
        }
    }
}
