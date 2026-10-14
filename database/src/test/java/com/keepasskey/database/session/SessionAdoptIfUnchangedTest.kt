package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.security.ProtectedString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream

/**
 * ISSUE-P2-278：「校验-采用」原子落库（[DatabaseSession.adoptDatabaseIfUnchanged]）的
 * 会话层单元守卫——同步周期接管 / 合并落库的唯一判据。
 *
 * 锁定三态：
 * ① 会话树仍是周期起点实例 ⇒ 采用成功并置 DIRTY；
 * ② 窗口内发生本地编辑（copy-on-write 替换实例）⇒ 拒绝采用、会话树与新编辑均不受影响；
 * ③ 拒绝后不得以任何方式改动会话状态（ StateFlow 实例与内容保持原样）。
 */
class SessionAdoptIfUnchangedTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newSession(): DatabaseSession {
        val session = DatabaseSession()
        val created = runBlocking {
            session.create(
                file = File(tempFolder.root, "adopt_${System.nanoTime()}.kdbx"),
                name = "AdoptGuard",
                passwordChars = "AdoptGuard#2026".toCharArray(),
                useArgon2 = false
            )
        }
        assertTrue("夹具建库失败: $created", created is KdbxResult.Success)
        return session
    }

    @Test
    fun `会话树未变时采用成功并置 DIRTY`() = runBlocking {
        val session = newSession()
        val atCycleStart = session.databaseFlow.value!!
        val replacement = atCycleStart.copy(databaseName = "RenamedVault")

        val adopted = session.adoptDatabaseIfUnchanged(atCycleStart, replacement)

        assertTrue("会话树未偏离时必须采用成功", adopted)
        assertSame("采用后会话树必须就是替换树", replacement, session.databaseFlow.value)
        assertEquals("采用后必须置 DIRTY", DatabaseSession.SessionState.DIRTY, session.state.value)
    }

    @Test
    fun `窗口内本地编辑后拒绝采用且编辑不丢`() = runBlocking {
        val session = newSession()
        val atCycleStart = session.databaseFlow.value!!
        // 模拟「同步窗口内」用户编辑：copy-on-write 替换会话树实例
        val edit = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("窗口内编辑", false))
        )
        session.saveEntry(edit)
        val editedTree = session.databaseFlow.value!!
        assertTrue("前提：编辑后会话树实例必须已替换", editedTree !== atCycleStart)

        val adopted = session.adoptDatabaseIfUnchanged(
            atCycleStart,
            atCycleStart.copy(databaseName = "Takeover")
        )

        assertFalse("会话树已偏离时必须拒绝采用", adopted)
        assertSame("拒绝采用后会话树必须保持编辑后的实例", editedTree, session.databaseFlow.value)
        assertTrue(
            "窗口内的编辑必须仍在会话树中",
            session.databaseFlow.value!!.rootGroup.allEntries().any { it.id == edit.id }
        )
    }

    @Test
    fun `无活动库时拒绝采用`() = runBlocking {
        val session = newSession()
        val atCycleStart = session.databaseFlow.value!!
        session.lock()

        val adopted = session.adoptDatabaseIfUnchanged(
            atCycleStart,
            atCycleStart.copy(databaseName = "Takeover")
        )

        assertFalse("锁库后（无活动库）必须拒绝采用", adopted)
    }

    @Test
    fun `活动库切换后拒绝旧设置提交且不执行变换`() = runBlocking {
        val session = newSession()
        val replacement = newSession()
        val staleDatabase = session.databaseFlow.value!!
        val replacementBytes = (replacement.exportToBytes() as KdbxResult.Success).data
        val saveCalls = java.util.concurrent.atomic.AtomicInteger()
        val password = "AdoptGuard#2026".toCharArray()
        try {
            assertTrue(
                "前置：打开替换库失败",
                session.openStream(
                    pathIdentifier = "replacement-${System.nanoTime()}",
                    inputStreamProvider = { ByteArrayInputStream(replacementBytes) },
                    saveWriter = { saveCalls.incrementAndGet() },
                    passwordChars = password
                ) is KdbxResult.Success
            )
            val activeAfterSwitch = session.databaseFlow.value!!
            var transformCalled = false

            val result = session.writeIntents.commitDatabaseMeta(staleDatabase) {
                transformCalled = true
                it.copy(databaseDescription = "must-not-apply")
            }

            assertEquals("旧会话请求应收到明确切换错误", KdbxError.SAVE_SESSION_CHANGED, (result as KdbxResult.Failure).code)
            assertFalse("拒绝旧会话请求前不得调用变换", transformCalled)
            assertSame("活动库切换后的树必须保持不变", activeAfterSwitch, session.databaseFlow.value)
            assertEquals("拒绝旧会话请求不得写正式文件", 0, saveCalls.get())
        } finally {
            replacementBytes.fill(0)
            password.fill('\u0000')
            session.close()
            replacement.close()
        }
    }

    @Test
    fun `写入回执已提交后收到取消仍先发布已落盘候选`() = runBlocking {
        val session = newSession()
        val password = "AdoptGuard#2026".toCharArray()
        val bytes = (session.exportToBytes() as KdbxResult.Success).data
        try {
            assertTrue(
                "前置：装载带提交回执的会话失败",
                session.openStream(
                    pathIdentifier = "committed-before-cancel-${System.nanoTime()}",
                    inputStreamProvider = { ByteArrayInputStream(bytes) },
                    saveWriter = {},
                    passwordChars = password,
                    trackedSaveWriter = { _, receipt ->
                        receipt.committed()
                        throw CancellationException("synthetic cancellation after durable commit")
                    }
                ) is KdbxResult.Success
            )
            val before = session.databaseFlow.value!!
            var cancellation: CancellationException? = null

            try {
                session.writeIntents.commitDatabaseMeta(before) { it.copy(databaseDescription = "committed despite cancellation") }
            } catch (error: CancellationException) {
                cancellation = error
            }

            assertNotNull("已取消的调用仍必须传播取消", cancellation)
            assertEquals(
                "提交回执已确认时内存候选必须追上正式文件",
                "committed despite cancellation",
                session.databaseFlow.value?.databaseDescription
            )
            assertEquals("提交后取消不得把已保存会话留为 DIRTY", DatabaseSession.SessionState.OPENED, session.state.value)
            assertEquals("提交后取消的进度必须落到完成态", 1.0f, session.ioProgress.value)
        } finally {
            bytes.fill(0)
            password.fill('\u0000')
            session.close()
        }
    }

    /**
     * `ISSUE-P0-531`：采用点的**擦除面**回归（擦除顺序整改不得改变擦除语义）。
     *
     * 采用点现在是「**先发布 replacement、再擦旧树**」（整改前相反；闪退根因见
     * `docs/ACTIVE_ISSUES.md` 的 `ISSUE-P0-531`）。无论顺序如何，以下两条身份集合判据
     * 必须不变——它们是 `ISSUE-P3-235` / `ISSUE-P3-258` / `ISSUE-P3-471` 的红线：
     * ① 与 replacement **共享同一实例**的字段不得被擦（否则静默清空活动库内容）；
     * ② 被 replacement 替换下线的实例必须清零（不得留给 GC）。
     */
    @Test
    fun `采用后共享实例保持可读而被下线实例清零`() = runBlocking {
        val session = newSession()
        val shared = ProtectedString("keep-me", isProtected = true)
        val retired = ProtectedString("retire-me", isProtected = true)
        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to shared,
                KdbxConstants.Fields.NOTES to retired
            )
        )
        session.saveEntry(entry)
        val atCycleStart = session.databaseFlow.value!!
        assertFalse("前提：两个实例初始都未清零", shared.cleared || retired.cleared)

        // 替换树复用 shared、丢弃 retired（合并器「单侧独有对象原实例复用」的同型形态）
        val replacementRoot = KdbxGroup(
            id = atCycleStart.rootGroup.id,
            name = atCycleStart.rootGroup.name,
            entries = listOf(entry.copy(fields = mapOf(KdbxConstants.Fields.TITLE to shared)))
        )
        val replacement = atCycleStart.copy(rootGroup = replacementRoot)

        val adopted = session.adoptDatabaseIfUnchanged(atCycleStart, replacement)

        assertTrue("会话树未偏离时必须采用成功", adopted)
        assertSame("采用后会话树必须就是替换树", replacement, session.databaseFlow.value)
        assertEquals("与替换树共享的实例不得被擦", "keep-me", shared.readString())
        assertTrue("被替换下线的实例必须清零（不留 GC）", retired.cleared)
    }
}
