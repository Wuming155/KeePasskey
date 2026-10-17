package com.keepasskey.app.data.repository

import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaselineHolder
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

private const val CANCEL_PWD_FIELD = KdbxConstants.Fields.PASSWORD

private fun cancelUuid(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val CANCEL_ROOT = cancelUuid("00000000000000000000000000000001")
private val CANCEL_EDITED = cancelUuid("00000000000000000000000000000002")
private val CANCEL_LOCAL_ONLY = cancelUuid("00000000000000000000000000000003")

private val CANCEL_STRINGS = StringsProvider { _, _ -> "" }

/** 确定性等待的超时兜底（正常路径远小于该值；仅防用例级悬挂） */
private const val CANCEL_TIMEOUT_MS = 60_000L

/**
 * `ISSUE-P1-631`：**取消语义 / `NonCancellable` 边界**专项验证（方案第 6 项）。
 *
 * 被验证的实现边界（生命周期收口后）：`VaultFileDriftResolve.adoptAndPersist` 把提交包在
 * `NonCancellable` 段内，段内经 `DatabaseSession.commitPreparedSave` 在**同一会话锁**内完成
 * 「校验 → 采用 → 写预备密文 → 基线」；序列化（保存侧 KDF/Argon2）已移至可取消的
 * `prepareSerializedSave` 预备段。本件用确定性同步点（闸门 / 写回阻塞）分别落在**临界区之前**
 * 与**临界区之内**，逐项确认：
 * 1. 临界区之前取消 ⇒ **不写盘、不采用、基线不前移**（取消正常生效，未被子段掩盖）；
 * 2. 临界区之内取消 ⇒ 提交**照常跑完**（不留「内存已合并 / 磁盘未写」半步状态），
 *    且取消被**推迟**到写回返回（如实记录为有意取舍，不是无界阻塞）；
 * 3. 正常路径 ⇒ `Success` 且基线前移（证明 1 / 2 的断言不是恒真）。
 */
class VaultFileDriftCancellationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 旧任务独立作用域：闸门未放行时用例失败也不会吊住 runBlocking（@After 统一取消） */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val masterPassword = "DriftCancel#2026".toCharArray()

    private fun entry(id: KdbxUuid, title: String, password: String) = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            CANCEL_PWD_FIELD to ProtectedString(password, true)
        )
    )

    private fun rootOf(vararg entries: KdbxEntry) =
        KdbxGroup(id = CANCEL_ROOT, name = "Root", entries = entries.toList())

    /** 建库 + 写入 V1，返回文件（用于随后以自定义 saveWriter 重开） */
    private suspend fun seedVault(): File {
        val file = File(tempFolder.newFolder(), "cancel.kdbx")
        val seed = DatabaseSession()
        assertTrue(
            "前提：建库必须成功",
            seed.create(file, "Cancel", masterPassword, useArgon2 = false) is KdbxResult.Success
        )
        seed.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(CANCEL_EDITED, "Edited", "A"))) }
        assertTrue("前提：V1 落盘成功", seed.save() is KdbxResult.Success)
        seed.close()
        return file
    }

    /**
     * 以**自定义 saveWriter** 重开（写回不落盘，字节交给测试捕获）——这样「是否发生提交」
     * 与「写回是否阻塞」都可由用例确定性控制，而不必依赖真实文件系统的时序。
     */
    private suspend fun openWithWriter(
        file: File,
        writer: suspend (ByteArray) -> Unit
    ): DatabaseSession {
        val session = DatabaseSession()
        val result = session.openStream(
            pathIdentifier = file.absolutePath,
            inputStreamProvider = { FileInputStream(file) },
            saveWriter = writer,
            passwordChars = masterPassword,
            associatedFile = file
        )
        assertTrue("前提：重开必须成功: $result", result is KdbxResult.Success)
        return session
    }

    /** 制造漂移：磁盘（正式文件）改写为磁盘版本（尺寸不同以保证判据可判） */
    private suspend fun driftDisk(file: File, session: DatabaseSession) {
        val active = session.databaseFlow.value ?: error("活动库不得为空")
        val bytes = ByteArrayOutputStream()
            .also {
                KdbxFile.save(
                    it,
                    active.copy(rootGroup = rootOf(entry(CANCEL_EDITED, "Edited", "Disk#1"))),
                    masterPassword
                )
            }
            .toByteArray()
        file.writeBytes(bytes)
    }

    /**
     * 本地**只新增**一条独有内容（不改编共享条目）——本件要验的是取消语义与提交边界，
     * 必须让合并**无冲突直达提交**；若本地也改共享条目的同字段，合并会先阻断待裁决，
     * 写回根本不会被调用，用例就测不到临界区了。
     */
    private suspend fun localEdit(session: DatabaseSession) {
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = rootOf(
                    entry(CANCEL_EDITED, "Edited", "A"),
                    entry(CANCEL_LOCAL_ONLY, "LocalOnly", "LocalOnly#1")
                )
            )
        }
    }

    private suspend fun revisionOf(session: DatabaseSession): Long =
        session.persistedBaselineSnapshot()?.also { it.wipe() }?.revision ?: -1L

    private fun passwordsOf(bytes: ByteArray): Set<String> =
        KdbxFile.load(ByteArrayInputStream(bytes), masterPassword)
            .rootGroup.allEntries()
            .map { it.fields.getValue(CANCEL_PWD_FIELD).readString() }
            .toSet()

    @Test
    fun `临界区之前取消 不得写盘不得采用且基线不前移`() = runBlocking {
        val file = seedVault()
        val gate = CompletableDeferred<Unit>()
        val written = arrayOfNulls<ByteArray>(1)
        // 写回缓冲由保存链在 writer 返回后清零（整库密文不得滞留）⇒ 测试必须自行复制
        val session = openWithWriter(file) { bytes -> written[0] = bytes.copyOf() }
        driftDisk(file, session)
        localEdit(session)

        val revisionBefore = revisionOf(session)
        val treeBefore = session.databaseFlow.value
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val resolver = VaultFileDriftResolve(
            session, CANCEL_STRINGS, coordinator,
            mergeCommitGate = { gate.await() }
        )

        val job = scope.async { resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) }
        // 让旧任务停在闸门（临界区之外），随后取消
        delay(200)
        job.cancel()

        val thrown = runCatching { withTimeout(CANCEL_TIMEOUT_MS) { job.await() } }.exceptionOrNull()
        assertNotNull("临界区之前取消必须终止本次调用", thrown)
        assertTrue("必须是取消语义而非静默成功", thrown is kotlinx.coroutines.CancellationException)
        assertNull("取消在临界区之前 ⇒ 写回绝不能被调用", written[0])
        assertEquals("取消后基线修订不得前移", revisionBefore, revisionOf(session))
        assertTrue("取消后会话树不得被替换", treeBefore === session.databaseFlow.value)
        session.close()
    }

    @Test
    fun `临界区之内取消 提交仍跑完且取消被推迟到写回返回`() = runBlocking {
        val file = seedVault()
        val writerEntered = CompletableDeferred<Unit>()
        val writerRelease = CompletableDeferred<Unit>()
        val written = arrayOfNulls<ByteArray>(1)
        val session = openWithWriter(file) { bytes ->
            // 同上：必须复制，否则返回后被保存链清零（本用例后面的重解析会读到全零）
            written[0] = bytes.copyOf()
            writerEntered.complete(Unit)
            writerRelease.await()
        }
        driftDisk(file, session)
        localEdit(session)

        val revisionBefore = revisionOf(session)
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val resolver = VaultFileDriftResolve(session, CANCEL_STRINGS, coordinator)

        val job = scope.async { resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) }
        withTimeout(CANCEL_TIMEOUT_MS) { writerEntered.await() }

        // 写回进行中取消：NonCancellable 段内取消被推迟（不得在半步状态中断）
        job.cancel()
        delay(200)
        assertFalse(
            "写回阻塞期间取消不得立即完成——NonCancellable 段尚未退出（有意取舍，非无界阻塞）",
            job.isCompleted
        )
        writerRelease.complete(Unit)

        val thrown = runCatching { withTimeout(CANCEL_TIMEOUT_MS) { job.await() } }.exceptionOrNull()
        assertTrue(
            "临界区跑完后取消才传播 ⇒ 调用以取消收场（如实）",
            thrown == null || thrown is kotlinx.coroutines.CancellationException
        )
        val bytes = written[0]
        assertNotNull("临界区内的提交必须跑完（不得留下「内存已合并、磁盘未写」）", bytes)
        assertTrue(
            "写入的必须是合并产物（磁盘侧改动 + 本地新增都在）: ${bytes!!.let { passwordsOf(it) }}",
            passwordsOf(bytes).containsAll(setOf("Disk#1", "LocalOnly#1"))
        )
        assertTrue("提交完成 ⇒ 基线必须已前移", revisionOf(session) > revisionBefore)
        assertEquals(
            "提交完成后会话树必须是合并产物（含本地独有条目）",
            setOf("Disk#1", "LocalOnly#1"),
            session.databaseFlow.value!!.rootGroup.allEntries()
                .map { it.fields.getValue(CANCEL_PWD_FIELD).readString() }.toSet()
        )
        assertNull("提交成功必须清掉待裁决提示", coordinator.mergeConflict.value)
        session.close()
    }

    /**
     * 生命周期收口新增：**预备段（序列化）完成后、提交临界区开始前，会话发生确认变更**
     * ⇒ 提交必须 fail-closed（`SAVE_SESSION_CHANGED`），预备密文整份作废——
     * 不得把用旧候选算出的密文写到已变化的会话上。这是「序列化移出临界区」后
     * 原子性由实例身份校验兜底的直接证据。
     */
    @Test
    fun `预备与提交之间会话变更 提交必须fail_closed且不写盘`() = runBlocking {
        val file = seedVault()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = arrayOfNulls<ByteArray>(1)
        val session = openWithWriter(file) { bytes -> written[0] = bytes.copyOf() }
        driftDisk(file, session)
        localEdit(session)

        val revisionBefore = revisionOf(session)
        val treeBefore = session.databaseFlow.value
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val resolver = VaultFileDriftResolve(
            session, CANCEL_STRINGS, coordinator,
            // 门只对裁决重算（policy != null）生效；夹具无冲突，KEEP_LOCAL 与 MERGE_AND_SAVE
            // 走同一合并/提交管线，仅裁决面差异。commitEnteredGate 触发时预备段（序列化）已完成。
            commitEnteredGate = { entered.complete(Unit); release.await() }
        )

        val job = scope.async { resolver.resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL) }
        withTimeout(CANCEL_TIMEOUT_MS) { entered.await() }
        // 此刻：预备密文已生成（保存侧 KDF/序列化完成）、会话与磁盘仍为旧状态。
        // 在预备与提交之间制造**会话确认变更**（树实例被替换为编辑后实例）。
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = rootOf(
                    entry(CANCEL_EDITED, "Edited", "A"),
                    entry(CANCEL_LOCAL_ONLY, "LocalOnly", "LocalOnly#1"),
                    entry(cancelUuid("00000000000000000000000000000004"), "Injected", "Injected#1")
                )
            )
        }
        val treeAfterChange = session.databaseFlow.value
        release.complete(Unit)

        val result = runCatching { withTimeout(CANCEL_TIMEOUT_MS) { job.await() } }
            .getOrNull() ?: error("提交必须以类型化失败收场而非取消/异常")
        assertTrue("预备后提交前会话变更 ⇒ 必须 fail-closed 失败: $result", result is KdbxResult.Failure)
        assertNull("不得写盘（预备密文整份作废）", written[0])
        assertEquals("失败后基线修订不得前移", revisionBefore, revisionOf(session))
        assertTrue(
            "会话树必须保持注入后的实例（合并候选不得被采用）",
            treeAfterChange === session.databaseFlow.value
        )
        assertTrue(
            "会话树必须包含注入条目（证明采用的是会话新树而非候选）",
            session.databaseFlow.value!!.rootGroup.allEntries()
                .any { it.fields.getValue(CANCEL_PWD_FIELD).readString() == "Injected#1" }
        )
        assertNull("KEEP_* 失败不得重建三选提示", coordinator.pending.value)
        session.close()
    }

    @Test
    fun `合并入口取消 不写盘不采用且基线不前移`() = runBlocking {
        val file = seedVault()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = arrayOfNulls<ByteArray>(1)
        val session = openWithWriter(file) { bytes -> written[0] = bytes.copyOf() }
        driftDisk(file, session)
        localEdit(session)

        val revisionBefore = revisionOf(session)
        val treeBefore = session.databaseFlow.value
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val resolver = VaultFileDriftResolve(
            session, CANCEL_STRINGS, coordinator,
            mergeStartGate = { entered.complete(Unit); release.await() }
        )

        val job = scope.async { resolver.resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL) }
        withTimeout(CANCEL_TIMEOUT_MS) { entered.await() }
        job.cancel()

        val thrown = runCatching { withTimeout(CANCEL_TIMEOUT_MS) { job.await() } }.exceptionOrNull()
        assertNotNull("合并入口取消必须终止本次调用", thrown)
        assertTrue("必须是取消语义而非静默成功", thrown is kotlinx.coroutines.CancellationException)
        assertNull("取消 ⇒ 写回绝不能被调用", written[0])
        assertEquals("取消后基线修订不得前移", revisionBefore, revisionOf(session))
        assertTrue("取消后会话树不得被替换", treeBefore === session.databaseFlow.value)
        session.close()
    }

    /**
     * 收口专项：**候选树已采用（adopt）后写回失败**的原子性。
     *
     * 排除的静默一致性风险：「内存显示新值、磁盘仍旧值、dirty 却被清除」。
     * 期望：写回失败 ⇒ 会话状态仍为 `DIRTY`、基线修订不前移、正式文件逐字节未变、
     * 内存树保留合并产物（不丢用户修改）、再次提交可成功（可重试）。
     */
    @Test
    fun `写回失败不得清dirty不得前移基线且可重试`() = runBlocking {
        val file = seedVault()
        var attempts = 0
        val written = arrayOfNulls<ByteArray>(1)
        val session = openWithWriter(file) { bytes ->
            attempts++
            if (attempts == 1) throw java.io.IOException("注入写回失败（收口专项）")
            written[0] = bytes.copyOf()
        }
        driftDisk(file, session)
        localEdit(session)
        val fileBytesBefore = file.readBytes()
        val revisionBefore = revisionOf(session)
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val resolver = VaultFileDriftResolve(session, CANCEL_STRINGS, coordinator)

        val first = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL)
        assertTrue("第一次写回失败必须如实失败: $first", first is KdbxResult.Failure)
        assertEquals(
            "写回失败后会话状态必须保持 DIRTY（不得出现「内存新值 + dirty 被清」）",
            DatabaseSession.SessionState.DIRTY,
            session.state.value
        )
        assertEquals("写回失败后基线修订不得前移", revisionBefore, revisionOf(session))
        assertTrue("写回失败不得改动正式文件", fileBytesBefore.contentEquals(file.readBytes()))
        val inMemory = session.databaseFlow.value!!.rootGroup.allEntries()
            .map { it.fields.getValue(CANCEL_PWD_FIELD).readString() }
            .toSet()
        assertTrue(
            "写回失败后内存树必须保留合并产物（不丢用户修改，实测=$inMemory）",
            inMemory.containsAll(setOf("Disk#1", "LocalOnly#1"))
        )
        assertTrue("写回失败不得把整库密文缓冲留在堆上（writer 未收到即失败）", written[0] == null)

        // 可重试：同一会话再次提交必须成功且状态回到 OPENED
        val second = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL)
        assertTrue("重试必须成功: $second", second is KdbxResult.Success)
        assertNotNull("重试必须发生写回", written[0])
        assertTrue(
            "重试写回的必须是合并产物",
            passwordsOf(written[0]!!).containsAll(setOf("Disk#1", "LocalOnly#1"))
        )
        assertTrue("重试成功后基线必须前移", revisionOf(session) > revisionBefore)
        assertEquals(
            "重试成功后状态必须回到 OPENED",
            DatabaseSession.SessionState.OPENED,
            session.state.value
        )
        session.close()
    }

    @Test
    fun `正常路径成功且基线前移 证明前述断言非恒真`() = runBlocking {
        val file = seedVault()
        val written = arrayOfNulls<ByteArray>(1)
        // 写回缓冲由保存链在 writer 返回后清零（整库密文不得滞留）⇒ 测试必须自行复制
        val session = openWithWriter(file) { bytes -> written[0] = bytes.copyOf() }
        driftDisk(file, session)
        localEdit(session)

        val revisionBefore = revisionOf(session)
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val result = VaultFileDriftResolve(session, CANCEL_STRINGS, coordinator)
            .resolve(ExternalModificationChoice.MERGE_AND_SAVE)

        assertTrue("正常路径必须成功: $result", result is KdbxResult.Success)
        assertNotNull("正常路径必须发生写回", written[0])
        assertTrue("正常路径基线必须前移", revisionOf(session) > revisionBefore)
        session.close()
    }
}
