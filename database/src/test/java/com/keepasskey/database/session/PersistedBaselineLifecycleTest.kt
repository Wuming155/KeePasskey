package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * `ISSUE-P1-631` 生命周期专项：持久化基线的登记 / 前移 / 清除 / 归属 / 擦除契约。
 *
 * 与 [PersistedBaselineCaptureTest] 的分工：该件覆盖「打开登记 / 保存前移 / 保存失败不前移 /
 * 换密前移 / 锁定清除 / 修订正反对照 / 捕获超限 / 打开失败不登记」；
 * 本件补齐：**关闭清除、换库归属与旧基线作废、Meta 提交仅在 writer 确认后前移（确认 /
 * 未确认 / 不确定三分支）、换密后旧口令不可解析、候选失败保留原基线、缓冲就地清零与重复清除**。
 *
 * 擦除断言经 `persistedBaselineInternalBytesForTesting`（内部缓冲**引用**）——克隆给到的是
 * 副本，无法观测内部缓冲的覆写；本件只对引用做只读观察。
 */
class PersistedBaselineLifecycleTest {

    private val keyFile = ByteArray(64) { (it + 1).toByte() }

    private fun vaultBytes(password: CharArray, description: String = "lifecycle"): ByteArray {
        val entry = KdbxEntry(
            fields = linkedMapOf(
                KdbxConstants.Fields.PASSWORD to ProtectedString("secret#2026", isProtected = true)
            )
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseDescription = description,
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
        return ByteArrayOutputStream().also { KdbxFile.save(it, db, password, keyFile) }.toByteArray()
    }

    private suspend fun open(
        session: DatabaseSession,
        path: String,
        bytes: ByteArray,
        password: CharArray,
        writer: suspend (ByteArray) -> Unit = {},
        trackedWriter: (suspend (ByteArray, SessionWriteReceipt) -> Unit)? = null
    ): Boolean = session.openStream(
        pathIdentifier = path,
        inputStreamProvider = { ByteArrayInputStream(bytes) },
        saveWriter = writer,
        passwordChars = password,
        keyFileData = keyFile,
        trackedSaveWriter = trackedWriter
    ).isSuccess

    private fun revisionOf(session: DatabaseSession): Long =
        runBlocking { session.persistedBaselineSnapshot()?.also { it.wipe() }?.revision ?: -1L }

    @Test
    fun `关闭清除基线且此后以旧修订采用必须被拒绝`() = runBlocking {
        val pwd = "Lifecycle#2026".toCharArray()
        val bytes = vaultBytes(pwd)
        val session = DatabaseSession()
        assertTrue("前提：打开成功", open(session, "lifecycle.kdbx", bytes, pwd))
        val revBefore = revisionOf(session)

        session.close()

        assertNull("关闭必须清除持久化基线（整库密文快照随会话终止销毁）", session.persistedBaselineSnapshot())
        // 重开同一库：修订已因「清除 + 重登记」前移，携带旧修订的采用必须拒绝
        assertTrue("前提：重开成功", open(session, "lifecycle.kdbx", bytes, pwd))
        val fresh = session.databaseFlow.value ?: error("活动库不得为空")
        val stale = session.adoptDatabaseIfUnchanged(
            expectedAtCycleStart = fresh,
            replacement = fresh,
            expectedPersistedBaselineRevision = revBefore
        )
        assertFalse("关闭 + 重开（清除 + 重登记）后，旧修订必须被拒绝", stale)
        // 正对照：以当前修订提交必须放行（证明拒绝不是恒真）
        val current = revisionOf(session)
        assertTrue("清除 + 重登记必须令修订前移", current > revBefore)
        assertTrue(
            "以当前修订采用必须放行",
            session.adoptDatabaseIfUnchanged(fresh, fresh, current)
        )
        session.close()
    }

    @Test
    fun `换库后基线绑定新库且旧库字节不得复用`() = runBlocking {
        val pwd = "Lifecycle#2026".toCharArray()
        val bytesA = vaultBytes(pwd, description = "vault-A")
        val bytesB = vaultBytes(pwd, description = "vault-B")
        assertFalse("前提：两个库字节必须不同", bytesA.contentEquals(bytesB))
        val session = DatabaseSession()
        assertTrue(open(session, "vault-a.kdbx", bytesA, pwd))
        val revA = revisionOf(session)

        assertTrue(open(session, "vault-b.kdbx", bytesB, pwd))

        val snapshot = session.persistedBaselineSnapshot() ?: error("换库后必须登记新基线")
        try {
            assertEquals("换库后基线必须绑定新库路径", "vault-b.kdbx", snapshot.pathIdentifier)
            assertArrayEquals("换库后基线必须是新库字节（旧库字节不得复用）", bytesB, snapshot.bytes)
            assertNotEquals("换库必须令修订前移", revA, snapshot.revision)
        } finally {
            snapshot.wipe()
        }
        val treeB = session.databaseFlow.value ?: error("活动库不得为空")
        assertFalse(
            "换库后以旧库时代的修订提交必须拒绝",
            session.adoptDatabaseIfUnchanged(treeB, treeB, revA)
        )
        session.close()
    }

    @Test
    fun `Meta提交仅在writer确认提交后前移基线`() = runBlocking {
        val pwd = "Lifecycle#2026".toCharArray()
        val bytes = vaultBytes(pwd)

        // 分支一：tracked writer 明确确认提交 ⇒ 提交成功且基线前移为候选字节
        run {
            val written = mutableListOf<ByteArray>()
            val session = DatabaseSession()
            assertTrue(
                open(session, "meta.kdbx", bytes, pwd, trackedWriter = { data, receipt ->
                    receipt.writing()
                    written += data.copyOf()
                    receipt.committed()
                }) 
            )
            val revBefore = revisionOf(session)
            val expected = session.databaseFlow.value ?: error("活动库不得为空")
            val result = session.writeIntents.commitDatabaseMeta(expected) {
                it.copy(databaseDescription = "meta-committed")
            }
            assertTrue("确认提交的 Meta 事务必须成功: $result", result.isSuccess)
            val after = session.persistedBaselineSnapshot() ?: error("确认提交后必须登记基线")
            try {
                assertEquals("Meta 提交确认后修订必须恰好前移一档", revBefore + 1, after.revision)
                assertEquals("基线必须等于本次写出的候选字节", "meta-committed", KdbxFile.load(ByteArrayInputStream(after.bytes), pwd, keyFile).databaseDescription)
                assertArrayEquals("基线必须逐位等于实际写出字节", written.last(), after.bytes)
            } finally {
                after.wipe()
            }
            session.close()
        }

        // 分支二：writer 证明未提交后抛异常 ⇒ 如实失败、基线不前移、不进入写不确定态
        run {
            val session = DatabaseSession()
            assertTrue(
                open(session, "meta.kdbx", bytes, pwd, trackedWriter = { _, receipt ->
                    receipt.writing()
                    receipt.notCommitted()
                    throw IOException("synthetic-not-committed")
                })
            )
            val before = session.persistedBaselineSnapshot() ?: error("前置基线必须存在")
            val revBefore = before.revision
            val bytesBefore = before.bytes.copyOf()
            before.wipe()
            val expected = session.databaseFlow.value ?: error("活动库不得为空")
            val result = session.writeIntents.commitDatabaseMeta(expected) {
                it.copy(databaseDescription = "must-not-publish")
            }
            assertTrue("未确认提交的 Meta 事务必须如实失败", result.isFailure)
            assertFalse("已证明未提交时不得进入写不确定态", session.credentialWriteUncertain.value)
            val after = session.persistedBaselineSnapshot() ?: error("旧基线必须保持")
            try {
                assertEquals("未确认提交不得前移基线修订", revBefore, after.revision)
                assertArrayEquals("未确认提交必须保持旧基线字节", bytesBefore, after.bytes)
            } finally {
                after.wipe()
                bytesBefore.fill(0)
            }
            assertEquals(
                "未确认提交不得发布候选 Meta",
                "lifecycle",
                session.databaseFlow.value?.databaseDescription
            )
            session.close()
        }

        // 分支三：writer 报告结果不确定 ⇒ 失败 + 写不确定封锁，且基线不前移
        run {
            val session = DatabaseSession()
            assertTrue(
                open(session, "meta.kdbx", bytes, pwd, trackedWriter = { _, receipt ->
                    receipt.writing()
                    receipt.uncertain()
                    throw IOException("synthetic-uncertain")
                })
            )
            val revBefore = revisionOf(session)
            val expected = session.databaseFlow.value ?: error("活动库不得为空")
            val result = session.writeIntents.commitDatabaseMeta(expected) {
                it.copy(databaseDescription = "must-not-publish")
            }
            assertTrue("结果不确定必须如实失败", result.isFailure)
            assertTrue("结果不确定必须封锁会话", session.credentialWriteUncertain.value)
            assertEquals("结果不确定不得前移基线修订", revBefore, revisionOf(session))
            session.close()
        }
    }

    @Test
    fun `换密后旧口令不再能解析基线`() = runBlocking {
        val oldPwd = "Old#Lifecycle#2026".toCharArray()
        val newPwd = "New#Lifecycle#2026".toCharArray()
        val session = DatabaseSession()
        assertTrue(open(session, "rotate.kdbx", vaultBytes(oldPwd), oldPwd))
        assertTrue("前提：换密成功", session.changeCredentials(newPwd).isSuccess)

        val after = session.persistedBaselineSnapshot() ?: error("换密后基线必须仍存在")
        try {
            assertTrue(
                "换密后的基线必须可被新口令解析",
                runCatching { KdbxFile.load(ByteArrayInputStream(after.bytes), newPwd, keyFile) }.isSuccess
            )
            assertTrue(
                "换密后的基线必须**不可**被旧口令解析（否则基线与会话凭据不一致）",
                runCatching { KdbxFile.load(ByteArrayInputStream(after.bytes), oldPwd, keyFile) }.isFailure
            )
        } finally {
            after.wipe()
        }
        session.close()
    }

    @Test
    fun `候选打开失败保留原会话基线且不登记候选基线`() = runBlocking {
        val pwd = "Lifecycle#2026".toCharArray()
        val session = DatabaseSession()
        try {
            assertTrue(open(session, "first.kdbx", vaultBytes(pwd), pwd))
            val originalTree = session.databaseFlow.value
            val before = session.persistedBaselineSnapshot() ?: error("原会话必须已有基线")
            try {
                val failed = session.openStream(
                    pathIdentifier = "second.kdbx",
                    inputStreamProvider = { ByteArrayInputStream(ByteArray(128) { 0x5A }) },
                    saveWriter = {},
                    passwordChars = pwd,
                    keyFileData = keyFile
                )
                assertTrue("非 KDBX 输入必须如实失败", failed.isFailure)
                assertTrue("候选失败不得替换原树", originalTree === session.databaseFlow.value)
                assertEquals("first.kdbx", session.currentPathIdentifier)
                val after = session.persistedBaselineSnapshot() ?: error("原会话基线必须保留")
                try {
                    assertEquals("失败不得前移基线修订", before.revision, after.revision)
                    assertArrayEquals("失败不得改写基线密文", before.bytes, after.bytes)
                } finally {
                    after.wipe()
                }
            } finally {
                before.wipe()
            }
        } finally {
            session.close()
            pwd.fill('\u0000')
        }
    }

    @Test
    fun `基线缓冲就地清零且重复清除安全`() {
        // 会话级：登记 → 替换 → 清除 → 重复清除
        val core = SessionCore()
        val first = ByteArray(48) { (it * 3 + 1).toByte() }
        val firstExpect = first.copyOf()
        core.retainPersistedBaseline(first, "wipe.kdbx")
        first.fill(0) // 调用方清零不得影响内部克隆
        assertArrayEquals("登记必须克隆（调用方清零不影响基线）", firstExpect, core.persistedBaselineClone())
        val firstRef = core.persistedBaselineInternalBytesForTesting() ?: error("内部缓冲必须存在")
        assertTrue("登记后的内部缓冲不得为全零", firstRef.any { it != 0.toByte() })

        val second = ByteArray(64) { (it + 7).toByte() }
        core.retainPersistedBaseline(second, "wipe.kdbx")
        assertTrue("替换登记必须就地清零旧缓冲（不得留给 GC）", firstRef.all { it == 0.toByte() })
        val secondRef = core.persistedBaselineInternalBytesForTesting() ?: error("内部缓冲必须存在")

        core.clearPersistedBaseline()
        assertTrue("清除必须就地清零缓冲", secondRef.all { it == 0.toByte() })
        assertNull("清除后不得残留基线", core.persistedBaselineClone())
        val revisionAfterClear = core.persistedBaselineRevision()

        // 重复清除：内容语义幂等（仍为 null），修订单调前移（仅令在途校验更保守地失效）
        core.clearPersistedBaseline()
        assertNull("重复清除必须安全（仍为无基线）", core.persistedBaselineClone())
        assertTrue("重复清除令修订继续单调前移", core.persistedBaselineRevision() > revisionAfterClear)

        // 登记 null（＝本次写盘无留存，如捕获超限 / 打开未捕获）同样清除旧值
        core.retainPersistedBaseline(ByteArray(16) { 1 }, "wipe.kdbx")
        val refThird = core.persistedBaselineInternalBytesForTesting() ?: error("内部缓冲必须存在")
        core.retainPersistedBaseline(null, "wipe.kdbx")
        assertNull("登记 null 必须清除旧基线（不得伪造新基线）", core.persistedBaselineClone())
        assertTrue("登记 null 也必须清零旧缓冲", refThird.all { it == 0.toByte() })
    }

    @Test
    fun `会话级清除同样就地清零内部缓冲`() = runBlocking {
        val pwd = "Lifecycle#2026".toCharArray()
        val session = DatabaseSession()
        assertTrue(open(session, "buffer.kdbx", vaultBytes(pwd), pwd))
        val ref = session.persistedBaselineInternalBytesForTesting() ?: error("内部缓冲必须存在")
        assertTrue("打开登记后内部缓冲不得为全零", ref.any { it != 0.toByte() })

        session.lock()

        assertTrue("锁定必须就地清零内部缓冲", ref.all { it == 0.toByte() })
        assertNull("锁定后不得残留基线", session.persistedBaselineSnapshot())
        session.close()
    }
}
