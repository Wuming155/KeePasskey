package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * `ENG-20261028-19` / `ENG-20261028-18`：**写盘确认成功才发布**的保存提交边界。
 *
 * 锁定的缺陷（整改前）：
 * - 普通保存先发布按龄修剪后的树、擦除下线历史实例，再序列化 / 写盘 ⇒ 写盘失败或取消时
 *   内存已少掉历史、旧实例已清零，磁盘仍是旧版本；
 * - `commitPreparedSave` 先采纳整个候选树并擦除部分旧实例，再执行 `writer(bytes)` ⇒ 同样留下
 *   「内存已合并、文件未合并」的半步状态；
 * - 持久化基线登记在写盘成功之后做**克隆**（大库为第二份整库密文）；登记失败时旧基线原样保留，
 *   于是一次已确认的写盘会让下游拿上一个版本当共同底版。
 *
 * 判据全部落在可观测终态上：活动树实例身份、旧字段可读性、正式文件字节、基线修订与基线字节、
 * 预备密文是否清零。断言「写盘失败」用 writer 抛异常注入（与 `SessionSerializationBufferWipeTest`
 * 同一注入点），失败注入后同一会话继续用于成功对照，避免另建夹具。
 */
class SessionPersistCommitBoundaryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val keyFile = ByteArray(64) { (it + 1).toByte() }
    private val password = "{{SECRET_7k2m1xq9}}".toCharArray()

    private fun initialVault(): ByteArray {
        val entry = KdbxEntry(
            fields = linkedMapOf(
                KdbxConstants.Fields.PASSWORD to ProtectedString("secret#2026", true)
            )
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
        return ByteArrayOutputStream().also { KdbxFile.save(it, db, password, keyFile) }.toByteArray()
    }

    /** 打开会话并让正式文件先持有初始库字节（写盘通道由测试按需注入失败）。 */
    private suspend fun openSession(
        file: File,
        core: SessionCore? = null,
        writer: suspend (ByteArray) -> Unit
    ): DatabaseSession {
        val initial = initialVault()
        file.writeBytes(initial)
        val session = if (core != null) DatabaseSession(core = core) else DatabaseSession()
        assertTrue(
            "前置：会话打开失败",
            session.openStream(
                pathIdentifier = file.absolutePath,
                inputStreamProvider = { ByteArrayInputStream(initial) },
                saveWriter = writer,
                passwordChars = password,
                keyFileData = keyFile
            ).isSuccess
        )
        return session
    }

    /**
     * 给唯一条目挂一条**超期**历史快照，并把保留期设为 1 天 ⇒ 下一次保存必然触发按龄修剪。
     * 直接改内存树（不依赖 KDBX 往返是否保留 `times`），确保修剪分支真的被执行。
     */
    private suspend fun withAgedHistory(session: DatabaseSession) {
        val aged = Instant.now().minus(Duration.ofDays(400))
        session.updateDatabaseMeta { db ->
            val entry = db.rootGroup.entries.single()
            db.copy(
                maintenanceHistoryDays = 1,
                rootGroup = db.rootGroup.copy(
                    entries = listOf(
                        entry.copy(
                            history = listOf(
                                KdbxEntry(
                                    fields = linkedMapOf(
                                        KdbxConstants.Fields.PASSWORD to ProtectedString("history#2026", true)
                                    ),
                                    times = KdbxTimes(creationTime = aged, lastModificationTime = aged)
                                )
                            )
                        )
                    )
                )
            )
        }
    }

    private suspend fun revisionOf(session: DatabaseSession): Long =
        session.persistedBaselineSnapshot()?.also { it.wipe() }?.revision ?: -1L

    private fun titleOf(entry: KdbxEntry): String =
        entry.fields[KdbxConstants.Fields.TITLE]?.readString().orEmpty()

    @Test
    fun `历史修剪写盘失败时不发布修剪树且旧历史仍可读`() = runBlocking<Unit> {
        val file = File(tempFolder.root, "prune-fail.kdbx")
        var fail = true
        val session = openSession(file) { bytes ->
            if (fail) throw IOException("disk full")
            file.writeBytes(bytes)
        }
        try {
            withAgedHistory(session)
            val before = session.databaseFlow.value!!
            val fileBefore = file.readBytes()
            val revisionBefore = revisionOf(session)
            val stateBefore = session.state.value
            val historyField = before.rootGroup.entries.single().history.single()
                .fields.getValue(KdbxConstants.Fields.PASSWORD)
            val liveField = before.rootGroup.entries.single()
                .fields.getValue(KdbxConstants.Fields.PASSWORD)

            val result = session.save()
            assertTrue("写盘失败必须如实失败: $result", result.isFailure)

            assertSame("写盘失败不得发布修剪候选", before, session.databaseFlow.value)
            assertEquals("写盘失败不得改变会话状态", stateBefore, session.state.value)
            assertEquals("旧历史快照必须仍可读（不得先擦除下线实例）", "history#2026", historyField.readString())
            assertEquals("活动条目字段必须仍可读", "secret#2026", liveField.readString())
            assertArrayEquals("写盘失败不得改动正式文件", fileBefore, file.readBytes())
            assertEquals("写盘失败不得前移基线修订", revisionBefore, revisionOf(session))
        } finally {
            session.close()
        }
    }

    @Test
    fun `历史修剪写盘成功后发布修剪树且文件与基线一致`() = runBlocking<Unit> {
        val file = File(tempFolder.root, "prune-ok.kdbx")
        var written: ByteArray? = null
        val session = openSession(file) { bytes ->
            written = bytes.copyOf()
            file.writeBytes(bytes)
        }
        try {
            withAgedHistory(session)
            val before = session.databaseFlow.value!!
            val revisionBefore = revisionOf(session)

            val result = session.save()
            assertTrue("保存必须成功: $result", result.isSuccess)

            val after = session.databaseFlow.value!!
            assertNotSame("修剪成功后必须发布修剪候选", before, after)
            assertTrue("修剪结果必须已发布到活动树", after.rootGroup.entries.single().history.isEmpty())
            assertEquals(
                "确认写盘后状态必须为 OPENED",
                DatabaseSession.SessionState.OPENED,
                session.state.value
            )
            assertEquals("确认写盘后基线修订必须恰好前移一档", revisionBefore + 1, revisionOf(session))
            assertArrayEquals("正式文件内容必须等于本次写出的字节", written, file.readBytes())
            val baseline = session.persistedBaselineSnapshot() ?: error("确认写盘后必须登记基线")
            try {
                assertArrayEquals("基线必须等于实际写出的字节", written, baseline.bytes)
            } finally {
                baseline.wipe()
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `写盘确认后基线直接接管写出缓冲而不克隆整库密文`() = runBlocking<Unit> {
        val file = File(tempFolder.root, "baseline-owner.kdbx")
        var seen: ByteArray? = null
        val session = openSession(file) { bytes ->
            // 只持引用：用于断言基线接管的就是这一份缓冲（`ENG-20261028-18` 的峰值收敛）
            seen = bytes
            file.writeBytes(bytes)
        }
        try {
            assertTrue("保存必须成功", session.save().isSuccess)
            val written = seen
            assertNotNull("写盘必须被触发", written)
            assertSame(
                "基线必须接管本次写出的缓冲本体（克隆即多一份整库密文）",
                written,
                session.persistedBaselineInternalBytesForTesting()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `预备提交写盘失败时保持采纳前状态`() = runBlocking<Unit> {
        val file = File(tempFolder.root, "prepared-fail.kdbx")
        var fail = false
        val session = openSession(file) { bytes ->
            if (fail) throw IOException("disk full")
            file.writeBytes(bytes)
        }
        var prepared: PreparedSerializedSave? = null
        try {
            val tree = session.databaseFlow.value!!
            val candidate = tree.copy(
                rootGroup = tree.rootGroup.copy(
                    entries = tree.rootGroup.entries + KdbxEntry(
                        fields = linkedMapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("merged", false),
                            KdbxConstants.Fields.PASSWORD to ProtectedString("merged#pwd", true)
                        )
                    )
                )
            )
            val prepResult = session.prepareSerializedSave(candidate)
            assertTrue("前置：预备段必须成功", prepResult is KdbxResult.Success)
            val preparedSave = (prepResult as KdbxResult.Success).data
            prepared = preparedSave
            val fileBefore = file.readBytes()
            val revisionBefore = revisionOf(session)
            val stateBefore = session.state.value

            fail = true
            val thrown = runCatching {
                session.commitPreparedSave(
                    expectedAtCycleStart = tree,
                    candidate = candidate,
                    prepared = preparedSave
                )
            }.exceptionOrNull()
            assertNotNull("写盘异常必须如实抛出", thrown)
            assertTrue("写盘异常必须保持原始类型: $thrown", thrown is IOException)

            assertSame("写盘失败不得发布合并候选", tree, session.databaseFlow.value)
            assertFalse(
                "合并条目不得进入活动树",
                session.databaseFlow.value!!.rootGroup.entries.any { titleOf(it) == "merged" }
            )
            assertEquals(
                "原条目字段必须仍可读",
                "secret#2026",
                tree.rootGroup.entries.single().fields.getValue(KdbxConstants.Fields.PASSWORD).readString()
            )
            assertEquals("写盘失败不得改变会话状态", stateBefore, session.state.value)
            assertArrayEquals("写盘失败不得改动正式文件", fileBefore, file.readBytes())
            assertEquals("写盘失败不得前移基线修订", revisionBefore, revisionOf(session))
            assertTrue("预备密文必须已清零", preparedSave.isWiped)
        } finally {
            prepared?.wipe()
            session.close()
        }
    }

    @Test
    fun `预备提交成功时返回已发布实例并前移基线`() = runBlocking<Unit> {
        val file = File(tempFolder.root, "prepared-ok.kdbx")
        var written: ByteArray? = null
        val session = openSession(file) { bytes ->
            written = bytes.copyOf()
            file.writeBytes(bytes)
        }
        var prepared: PreparedSerializedSave? = null
        try {
            val tree = session.databaseFlow.value!!
            val candidate = tree.copy(
                rootGroup = tree.rootGroup.copy(
                    entries = tree.rootGroup.entries + KdbxEntry(
                        fields = linkedMapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("merged", false)
                        )
                    )
                )
            )
            val prepResult = session.prepareSerializedSave(candidate)
            assertTrue("前置：预备段必须成功", prepResult is KdbxResult.Success)
            val preparedSave = (prepResult as KdbxResult.Success).data
            prepared = preparedSave
            val revisionBefore = revisionOf(session)

            val commitResult = session.commitPreparedSave(
                expectedAtCycleStart = tree,
                candidate = candidate,
                prepared = preparedSave
            )
            assertTrue("提交必须成功: $commitResult", commitResult.isSuccess)
            val committed = (commitResult as KdbxResult.Success).data

            assertSame("提交成功返回的实例即已发布的活动树", committed, session.databaseFlow.value)
            assertTrue(
                "合并条目必须已进入活动树",
                session.databaseFlow.value!!.rootGroup.entries.any { titleOf(it) == "merged" }
            )
            assertEquals(
                "确认写盘后状态必须为 OPENED",
                DatabaseSession.SessionState.OPENED,
                session.state.value
            )
            assertEquals("确认写盘后基线修订必须恰好前移一档", revisionBefore + 1, revisionOf(session))
            assertArrayEquals("正式文件内容必须等于预备密文", written, file.readBytes())
            assertTrue("提交完成后预备密文必须已清零", preparedSave.isWiped)
        } finally {
            prepared?.wipe()
            session.close()
        }
    }

    @Test
    fun `预备提交写盘取消时保持采纳前状态`() = runBlocking<Unit> {
        val file = File(tempFolder.root, "prepared-cancel.kdbx")
        var cancel = false
        val session = openSession(file) { bytes ->
            if (cancel) throw CancellationException("cancelled during write")
            file.writeBytes(bytes)
        }
        var prepared: PreparedSerializedSave? = null
        try {
            val tree = session.databaseFlow.value!!
            val candidate = tree.copy(
                rootGroup = tree.rootGroup.copy(
                    entries = tree.rootGroup.entries + KdbxEntry(
                        fields = linkedMapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("merged", false)
                        )
                    )
                )
            )
            val prepResult = session.prepareSerializedSave(candidate)
            assertTrue("前置：预备段必须成功", prepResult is KdbxResult.Success)
            val preparedSave = (prepResult as KdbxResult.Success).data
            prepared = preparedSave
            val revisionBefore = revisionOf(session)
            val fileBefore = file.readBytes()

            cancel = true
            val thrown = runCatching {
                session.commitPreparedSave(
                    expectedAtCycleStart = tree,
                    candidate = candidate,
                    prepared = preparedSave
                )
            }.exceptionOrNull()
            assertTrue("取消必须传播: $thrown", thrown is CancellationException)

            assertSame("取消不得发布合并候选", tree, session.databaseFlow.value)
            assertEquals("取消不得前移基线修订", revisionBefore, revisionOf(session))
            assertArrayEquals("取消不得改动正式文件", fileBefore, file.readBytes())
            assertTrue("预备密文必须已清零", preparedSave.isWiped)
        } finally {
            prepared?.wipe()
            session.close()
        }
    }

    @Test
    fun `基线登记失败后旧基线必须作废且修订前移`() {
        var failClone = false
        val core = SessionCore(
            baselineCloner = { bytes ->
                if (failClone) throw OutOfMemoryError("synthetic OOM during baseline retain")
                bytes.clone()
            }
        )
        val initialBytes = ByteArray(48) { 0x11 }
        core.retainPersistedBaseline(initialBytes, "vault.kdbx")
        val oldBuffer = core.persistedBaselineInternalBytesForTesting() ?: error("前置：旧基线必须存在")
        val revisionBefore = core.persistedBaselineRevision()

        // 异常安全验证（先克隆后替换）：直接 retain 抛出时克隆在触碰旧值之前失败，旧基线原值与原修订保持未受破坏
        failClone = true
        val directThrown = runCatching {
            core.retainPersistedBaseline(ByteArray(48) { 0x22 }, "vault.kdbx")
        }.exceptionOrNull()
        assertTrue("直接登记必须抛出 OOM: $directThrown", directThrown is OutOfMemoryError)
        assertEquals("克隆失败时旧基线修订不得改变", revisionBefore, core.persistedBaselineRevision())
        assertArrayEquals("克隆失败时旧基线原值保持未清零", initialBytes, core.persistedBaselineClone())

        // 真实 bestEffort 收口穿透：catch 触发作废收口，旧值清零、快照置空、修订单调前移
        core.retainPersistedBaselineBestEffort(ByteArray(48) { 0x22 }, "vault.kdbx")

        assertNull("登记失败不得留下旧基线（不得留下上一个版本冒充最新已提交版本）", core.persistedBaselineClone())
        assertTrue("旧基线缓冲必须就地清零", oldBuffer.all { it == 0.toByte() })
        assertTrue("修订必须前移（令在途校验失效）", core.persistedBaselineRevision() > revisionBefore)
    }

    @Test
    fun `预备提交成功但基线留存克隆失败时端到端执行作废收口且保持已提交写盘语义`() = runBlocking<Unit> {
        val file = File(tempFolder.root, "prepared-baseline-oom.kdbx")
        var written: ByteArray? = null
        var failClone = false
        val core = SessionCore(
            baselineCloner = { bytes ->
                if (failClone) throw OutOfMemoryError("synthetic OOM during baseline retain")
                bytes.clone()
            }
        )
        val session = openSession(file, core = core) { bytes ->
            written = bytes.copyOf()
            file.writeBytes(bytes)
        }
        var prepared: PreparedSerializedSave? = null
        try {
            // 1. 验证前置：打开成功，已留存旧基线
            val oldSnapshot = session.persistedBaselineSnapshot() ?: error("前置：旧基线必须存在")
            val oldRevision = oldSnapshot.revision
            oldSnapshot.wipe()
            val oldInternalBuffer = session.persistedBaselineInternalBytesForTesting() ?: error("前置：内部缓冲必须存在")
            val treeBefore = session.databaseFlow.value!!
            val fileBefore = file.readBytes()

            // 2. 准备合并候选（新版本）并完成序列化预备段
            val candidate = treeBefore.copy(
                rootGroup = treeBefore.rootGroup.copy(
                    entries = treeBefore.rootGroup.entries + KdbxEntry(
                        fields = linkedMapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("merged-fail-retain", false),
                            KdbxConstants.Fields.PASSWORD to ProtectedString("merged#pwd", true)
                        )
                    )
                )
            )
            val prepResult = session.prepareSerializedSave(candidate)
            assertTrue("前置：预备段必须成功", prepResult is KdbxResult.Success)
            val preparedSave = (prepResult as KdbxResult.Success).data
            prepared = preparedSave

            // 3. 启用克隆故障注入：写盘成功后登记新版本时触发 OOM
            failClone = true
            val commitResult = session.commitPreparedSave(
                expectedAtCycleStart = treeBefore,
                candidate = candidate,
                prepared = preparedSave
            )

            // 4. 断言提交结果：真实 writer 已写盘、树已采纳发布，结果必须为 Success，不能伪失败
            assertTrue("写盘已成功提交不能伪失败: $commitResult", commitResult.isSuccess)
            val committed = (commitResult as KdbxResult.Success).data
            assertSame("提交成功返回的实例即已发布的活动树", committed, session.databaseFlow.value)
            assertTrue(
                "合并条目必须已正确提交到活动树",
                session.databaseFlow.value!!.rootGroup.entries.any { titleOf(it) == "merged-fail-retain" }
            )
            assertArrayEquals("正式文件内容必须已写入新版本", written, file.readBytes())
            assertFalse("正式文件不得仍是旧版本", fileBefore.contentEquals(file.readBytes()))
            assertEquals("会话状态仍必须为 OPENED", DatabaseSession.SessionState.OPENED, session.state.value)
            assertFalse("会话不得被错误置为写不确定态", session.credentialWriteUncertain.value)
            assertTrue("提交完成后预备密文必须已清零", preparedSave.isWiped)

            // 5. 断言基线状态：真实 bestEffort catch 触发作废收口
            assertNull("基线留存失败后快照必须为 null（绝不留旧基线冒充最新已提交版本）", session.persistedBaselineSnapshot())
            assertTrue("旧基线内部缓冲必须被就地清零", oldInternalBuffer.all { it == 0.toByte() })
            assertTrue("基线修订必须单调前移以令在途校验失效", core.persistedBaselineRevision() > oldRevision)

            // 6. 正常克隆路径与旧值无损对照保持：恢复克隆策略后再次提交
            failClone = false
            val candidateNext = committed.copy(
                rootGroup = committed.rootGroup.copy(
                    entries = committed.rootGroup.entries + KdbxEntry(
                        fields = linkedMapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("merged-next-ok", false)
                        )
                    )
                )
            )
            val prepNext = session.prepareSerializedSave(candidateNext)
            assertTrue(prepNext is KdbxResult.Success)
            val preparedNext = (prepNext as KdbxResult.Success).data
            try {
                val revBeforeNext = core.persistedBaselineRevision()
                val nextCommitResult = session.commitPreparedSave(
                    expectedAtCycleStart = committed,
                    candidate = candidateNext,
                    prepared = preparedNext
                )
                assertTrue("恢复正常克隆后提交必须成功", nextCommitResult.isSuccess)
                val snapshotNext = session.persistedBaselineSnapshot() ?: error("正常克隆路径必须成功留存基线")
                try {
                    assertEquals("正常克隆后修订前移", revBeforeNext + 1, snapshotNext.revision)
                    assertArrayEquals("基线密文等于写入密文", written, snapshotNext.bytes)
                    // 独占所有权/旧值无损断言：外部 wipe 不影响内部缓冲
                    snapshotNext.wipe()
                    val internalRef = session.persistedBaselineInternalBytesForTesting()
                    assertNotNull("会话独占持有的基线缓冲依然存在", internalRef)
                    assertTrue("调用方 wipe 快照不影响会话内部独立克隆缓冲", internalRef!!.any { it != 0.toByte() })
                } finally {
                    snapshotNext.wipe()
                }
            } finally {
                preparedNext.wipe()
            }
        } finally {
            prepared?.wipe()
            session.close()
        }
    }
}
