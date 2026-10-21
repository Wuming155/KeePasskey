package com.keepasskey.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P2-661`：**进程死亡**后，未提交的会话脏树必须可由加密崩溃草稿恢复。
 *
 * 两阶段（由宿主编排，`tools/device/run_p2_661_crash_draft.py`）：
 * 1. [prepareCrashWithDirtyTree]：内存脏树 + 已保全的加密草稿 → 写标记与正式文件快照 → 等待被
 *    `adb shell am force-stop` 杀死（本进程无法自杀后继续断言）；
 * 2. [verifyDraftRecoveredInNewProcess]：**新进程**核对——正式文件逐字节未变且不含未提交条目、
 *    草稿可发现且 purpose 唯一、错误凭据预览被拒、正确凭据预览可见本地修改、明确恢复后可重读、
 *    且只删除对应草稿。
 *
 * 缺 runner 参数即 fail-closed（不允许把两阶段用例混进常规全类运行）。
 */
@RunWith(AndroidJUnit4::class)
class VaultCrashDraftProcessDeathDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val password = "P2-661-Synthetic".toCharArray()
    private val directory: File get() = File(context.filesDir, "p2-661-crash")
    private val vault: File get() = File(directory, "vault.kdbx")

    private val repository: VaultRepository
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext, AutofillDeviceGraph::class.java
        ).vaultRepository()
    private val session: DatabaseSession
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext, AutofillDeviceGraph::class.java
        ).databaseSession()

    @Test
    fun prepareCrashWithDirtyTree() {
        assertEquals("只允许宿主以 p2661Phase=prepare 启动死亡前置", "prepare", arguments.getString(ARG_PHASE))
        runBlocking {
            directory.mkdirs()
            File(directory, "GATE_REACHED").delete()
            val isolated = DatabaseSession()
            try {
                assertTrue(isolated.create(vault, "P2-661", password, useArgon2 = false).isSuccess)
                isolated.saveEntry(entry("Disk#1"))
                assertTrue("初始版本必须已落盘", isolated.save().isSuccess)
            } finally {
                isolated.close()
            }
            assertTrue(repository.importExternalDatabase("P2-661", vault.absolutePath).isSuccess)
            repository.selectDatabase(vault.absolutePath)
            assertTrue(repository.unlockActiveDatabase(password.copyOf(), null, readOnly = false).isSuccess)

            // ① 未提交的本地修改（只存在于内存树）
            session.saveEntry(entry("LocalOnly#1"))
            assertEquals("本地修改必须进入脏树", DatabaseSession.SessionState.DIRTY, session.state.value)

            // ② 以会话复合凭据保全加密草稿（与生产保存路径同一实现）
            val store = VaultRecoveryStore(context, DebugLogBuffer())
            val drafts = VaultCrashDrafts(session, store)
            val preserved = drafts.capture()
            assertTrue("加密草稿必须已保全: $preserved", preserved is KdbxResult.Success)
            val draftEntry = (preserved as KdbxResult.Success).data
            assertEquals("草稿目的必须是崩溃草稿", VaultRecoveryStore.Purpose.CRASH_DRAFT, draftEntry.purpose)

            // ③ 定格现场：正式文件快照 + 新 PID 标记，然后等宿主 force-stop
            File(directory, "before.bin").writeBytes(vault.readBytes())
            File(directory, "GATE_REACHED").writeText(android.os.Process.myPid().toString())
            android.util.Log.i("P2661", "GATE_REACHED")
            val deadline = android.os.SystemClock.uptimeMillis() + WAIT_KILL_MS
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(500)
            }
            error("宿主未在 ${WAIT_KILL_MS}ms 内终止进程（前置段不应自行通过）")
        }
    }

    @Test
    fun verifyDraftRecoveredInNewProcess() {
        assertEquals("只允许宿主以 p2661Phase=verify 启动新进程核对", "verify", arguments.getString(ARG_PHASE))
        runBlocking {
            val oldPid = File(directory, "GATE_REACHED").readText().trim().toInt()
            assertNotEquals("必须是新的应用进程", oldPid, android.os.Process.myPid())

            // ① 正式文件逐字节未变，且不含未提交条目
            val before = File(directory, "before.bin").readBytes()
            assertArrayEquals("进程死亡不得改动正式文件", before, vault.readBytes())
            assertFalse(
                "正式库不得含未提交条目",
                titles(vault.readBytes()).contains("LocalOnly#1")
            )

            // ② 冷启动可发现草稿，且目的唯一
            val store = VaultRecoveryStore(context, DebugLogBuffer())
            val drafts = store.list().filter {
                it.purpose == VaultRecoveryStore.Purpose.CRASH_DRAFT && it.pathIdentifier == vault.absolutePath
            }
            assertEquals("必须且仅有一份本库崩溃草稿", 1, drafts.size)
            val draft = drafts.single()

            // ③ 恢复前必须重新验证复合凭据：错误密码不得放行
            val coordinator = VaultRecoveryCoordinator(
                context,
                store,
                RecoveryAlertStore(context),
                KeyFileVaultCopyStore(context, null, DebugLogBuffer()),
                DebugLogBuffer()
            )
            coordinator.refresh()
            val pending = coordinator.pending.value.single { it.entry.payloadFileName == draft.payloadFileName }
            val wrong = "P2-661-Wrong".toCharArray()
            try {
                assertTrue(
                    "错误凭据不得预览成功",
                    coordinator.preview(pending, wrong) is KdbxResult.Failure
                )
            } finally {
                wrong.fill('\u0000')
            }

            // ④ 正确凭据预览可见未提交的本地修改
            val preview = coordinator.preview(pending, password.copyOf())
            assertTrue("正确凭据必须能解锁草稿: $preview", preview.isSuccess)
            assertEquals("草稿必须仍含本地修改", 2, (preview as KdbxResult.Success).data.snapshotEntryCount)

            // ⑤ 明确恢复后才写回正式库，并可重新读到该修改
            val restored = coordinator.restore(pending, forceOverwrite = true)
            assertTrue("明确恢复必须成功: $restored", restored is KdbxResult.Success)
            assertTrue(
                "恢复后正式库必须能读到未提交的本地修改",
                titles(vault.readBytes()).contains("LocalOnly#1")
            )

            // ⑥ 仅删除对应草稿，正式库之外不残留
            assertEquals(
                "恢复成功后本库崩溃草稿必须清理",
                0,
                store.list().count {
                    it.purpose == VaultRecoveryStore.Purpose.CRASH_DRAFT && it.pathIdentifier == vault.absolutePath
                }
            )
            assertNotNull("正式库必须仍存在", vault.takeIf { it.isFile })
        }
    }

    private fun entry(title: String) = KdbxEntry(
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title),
            KdbxConstants.Fields.PASSWORD to ProtectedString("fake-$title", isProtected = true)
        )
    )

    private fun titles(bytes: ByteArray): Set<String> {
        val credential = password.clone()
        val db = try {
            com.keepasskey.database.file.KdbxFile.load(bytes.inputStream(), credential)
        } finally {
            credential.fill('\u0000')
        }
        return try {
            db.rootGroup.allEntries().mapNotNull {
                it.fields[KdbxConstants.Fields.TITLE]?.readString()
            }.toSet()
        } finally {
            db.clearSensitiveData()
        }
    }

    private companion object {
        const val ARG_PHASE = "p2661Phase"
        const val WAIT_KILL_MS = 180_000L
    }
}
