package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
}
