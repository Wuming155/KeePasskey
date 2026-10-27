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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

private const val BARRIER_PWD_FIELD = KdbxConstants.Fields.PASSWORD

private fun barrierUuid(hex: String): KdbxUuid =
    KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

private val BARRIER_ROOT = barrierUuid("00000000000000000000000000000001")
private val BARRIER_EDITED = barrierUuid("00000000000000000000000000000002")
private val BARRIER_LOCAL_ONLY = barrierUuid("00000000000000000000000000000003")
private val BARRIER_DISK_ONLY = barrierUuid("00000000000000000000000000000004")

private val BARRIER_STRINGS = StringsProvider { _, _ -> "" }

/** 闸门等待 / 收尾的超时兜底（确定性路径正常远小于该值；仅防悬挂） */
private const val BARRIER_TIMEOUT_MS = 60_000L

/**
 * `ISSUE-P1-631`：**并发换库 / 提交窗口 barrier 验收**（方案 §4 第 3 行的并发部分）。
 *
 * 竞态一律用**确定性同步点**制造（Resolver 的 `mergeCommitGate` + `CompletableDeferred`
 * 信号，非概率性 sleep）：
 * - 旧任务在「读取 / 解析 / 合并完成、尚未采用」的时点被闸门暂停；
 * - 期间执行真实换库 / 真实本地编辑 / 真实确认写盘；
 * - 放行后旧任务必须以 **Failure** 收场，且不得提交到新库、不得覆盖新版本文件与基线。
 *
 * 三条独立闸门（任一命中即 fail-closed）：
 * ① **读取版本钉扎**：通道身份或文件指纹在窗口内变化 ⇒ 拒绝（换库 / 二次外部改写）；
 * ② **会话树实例身份**：窗口内树被替换（本地又有编辑）⇒ 采用拒绝；
 * ③ **持久化基线修订**：窗口内发生确认写盘 ⇒ 采用拒绝（与 ① 可能同时命中）。
 */
class VaultFileDriftConcurrencyBarrierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 旧任务独立作用域：闸门未放行时用例失败也不会吊住 runBlocking（@After 统一取消） */
    private val barrierScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @After
    fun tearDown() {
        barrierScope.cancel()
    }

    private val masterPassword = "DriftBarrier#2026".toCharArray()

    private fun entry(id: KdbxUuid, title: String, password: String) = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            BARRIER_PWD_FIELD to ProtectedString(password, true)
        )
    )

    private fun rootOf(vararg entries: KdbxEntry) =
        KdbxGroup(id = BARRIER_ROOT, name = "Root", entries = entries.toList())

    private suspend fun createVault(name: String, title: String): Pair<DatabaseSession, File> {
        val vaultFile = File(tempFolder.newFolder(), name)
        val session = DatabaseSession()
        assertTrue(
            "前提：建库必须成功",
            session.create(vaultFile, title, masterPassword, useArgon2 = false) is KdbxResult.Success
        )
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(BARRIER_EDITED, "Edited", "A"))) }
        assertTrue("前提：V1 落盘成功", session.save() is KdbxResult.Success)
        return session to vaultFile
    }

    private suspend fun writeDisk(vaultFile: File, session: DatabaseSession, root: KdbxGroup) {
        val active = session.databaseFlow.value ?: error("活动库不得为空")
        val bytes = ByteArrayOutputStream()
            .also { KdbxFile.save(it, active.copy(rootGroup = root), masterPassword) }
            .toByteArray()
        vaultFile.writeBytes(bytes)
    }

    /** 闸门：到达即置信号 1，随后等待放行信号 2 */
    private class Gate {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun park() {
            reached.complete(Unit)
            release.await()
        }
    }

    @Test
    fun `合并窗口内换库 旧任务不得提交到新库也不得覆盖其基线`() = runBlocking {
        val (session, vaultA) = createVault("barrier-a.kdbx", "BarrierA")
        // 本地未落盘新增 E；磁盘被外部改写为 V2
        writeDisk(vaultA, session, rootOf(entry(BARRIER_EDITED, "Edited", "A"), entry(BARRIER_DISK_ONLY, "Disk", "Disk#1")))
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(entry(BARRIER_EDITED, "Edited", "A"), entry(BARRIER_LOCAL_ONLY, "Local", "Local#1")))
        }
        val diskBytesA = vaultA.readBytes()

        // 预备库 B（独立文件与独立内容；换库用）
        val seedB = DatabaseSession()
        val vaultB = File(tempFolder.newFolder(), "barrier-b.kdbx")
        assertTrue(seedB.create(vaultB, "BarrierB", masterPassword, useArgon2 = false) is KdbxResult.Success)
        seedB.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(BARRIER_EDITED, "Edited", "B-Only"))) }
        assertTrue("前提：库 B 落盘成功", seedB.save() is KdbxResult.Success)
        seedB.close()
        val bytesBBefore = vaultB.readBytes()

        val gate = Gate()
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val resolver = VaultFileDriftResolve(session, BARRIER_STRINGS, coordinator, mergeCommitGate = { gate.park() })

        // 独立作用域启动旧任务：不得用 `withContext { async { … } }`（withContext 会等待子协程，
        // 在「子协程停在闸门」时反而自锁）；闸门等待另加超时兜底，避免用例级悬挂。
        val task = barrierScope.async { resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) }
        withTimeout(BARRIER_TIMEOUT_MS) { gate.reached.await() }

        // 真实换库：同一会话对象打开库 B（走真实 openStream 前置释放路径）
        assertTrue(
            "换库必须成功",
            session.openStream(
                pathIdentifier = vaultB.absolutePath,
                inputStreamProvider = { FileInputStream(vaultB) },
                saveWriter = {},
                passwordChars = masterPassword,
                associatedFile = vaultB
            ) is KdbxResult.Success
        )
        val baselineB = session.persistedBaselineSnapshot() ?: error("换库后必须登记 B 的基线")
        val revisionB = baselineB.revision
        val bytesB = baselineB.bytes.copyOf()
        baselineB.wipe()
        val treeB = session.databaseFlow.value ?: error("活动库不得为空")

        gate.release.complete(Unit)
        val result = withTimeout(BARRIER_TIMEOUT_MS) { task.await() }

        assertTrue("旧任务必须以 Failure 明确收场（不得显示成功）: $result", result is KdbxResult.Failure)
        assertArrayEquals("库 B 的正式文件不得被旧任务改写", bytesBBefore, vaultB.readBytes())
        assertArrayEquals("库 A 的正式文件同样不得被旧任务改写（磁盘仍是 V2）", diskBytesA, vaultA.readBytes())
        val baselineBAfter = session.persistedBaselineSnapshot() ?: error("B 的基线不得被清除")
        try {
            assertEquals("B 的基线修订不得被旧任务前移", revisionB, baselineBAfter.revision)
            assertArrayEquals("B 的基线字节不得被旧任务覆盖", bytesB, baselineBAfter.bytes)
        } finally {
            baselineBAfter.wipe()
            bytesB.fill(0)
        }
        assertSame("B 的会话树实例不得被旧任务替换", treeB, session.databaseFlow.value)
        session.close()
    }

    @Test
    fun `合并窗口内本地又有编辑 采用必须被实例身份拦截`() = runBlocking {
        val (session, vaultA) = createVault("barrier-identity.kdbx", "BarrierIdentity")
        writeDisk(vaultA, session, rootOf(entry(BARRIER_EDITED, "Edited", "A"), entry(BARRIER_DISK_ONLY, "Disk", "Disk#1")))
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(entry(BARRIER_EDITED, "Edited", "A"), entry(BARRIER_LOCAL_ONLY, "Local", "Local#1")))
        }
        val diskBytesBefore = vaultA.readBytes()

        val gate = Gate()
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val resolver = VaultFileDriftResolve(session, BARRIER_STRINGS, coordinator, mergeCommitGate = { gate.park() })
        // 独立作用域启动旧任务：不得用 `withContext { async { … } }`（withContext 会等待子协程，
        // 在「子协程停在闸门」时反而自锁）；闸门等待另加超时兜底，避免用例级悬挂。
        val task = barrierScope.async { resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) }
        withTimeout(BARRIER_TIMEOUT_MS) { gate.reached.await() }

        // 窗口内本地又发生一次编辑（仅换树实例，不动文件、不动基线修订）
        val baselineBeforeEdit = session.persistedBaselineSnapshot() ?: error("前置基线必须存在")
        val revisionBeforeEdit = baselineBeforeEdit.revision
        baselineBeforeEdit.wipe()
        session.updateDatabaseMeta {
            it.copy(
                rootGroup = rootOf(
                    entry(BARRIER_EDITED, "Edited", "A"),
                    entry(BARRIER_LOCAL_ONLY, "Local", "Local#1"),
                    entry(barrierUuid("00000000000000000000000000000009"), "Second Edit", "Edit#2")
                )
            )
        }
        val revisionAfterEdit = session.persistedBaselineSnapshot()!!.also { it.wipe() }.revision
        assertEquals("前提：该编辑不得前移基线修订", revisionBeforeEdit, revisionAfterEdit)

        gate.release.complete(Unit)
        val result = withTimeout(BARRIER_TIMEOUT_MS) { task.await() }

        assertTrue("窗口内树被替换 ⇒ 采用必须 fail-closed: $result", result is KdbxResult.Failure)
        assertArrayEquals("正式文件不得被旧任务覆盖", diskBytesBefore, vaultA.readBytes())
        assertEquals(
            "窗口内的第二次编辑必须完整保留在会话树中",
            "Edit#2",
            session.databaseFlow.value!!.rootGroup.allEntries()
                .first { it.id == barrierUuid("00000000000000000000000000000009") }
                .fields.getValue(BARRIER_PWD_FIELD).readString()
        )
        session.close()
    }

    @Test
    fun `合并窗口内一次确认写盘 提交必须被拒且修订条件对旧修订拒绝`() = runBlocking {
        val (session, vaultA) = createVault("barrier-revision.kdbx", "BarrierRevision")
        writeDisk(vaultA, session, rootOf(entry(BARRIER_EDITED, "Edited", "A"), entry(BARRIER_DISK_ONLY, "Disk", "Disk#1")))
        session.updateDatabaseMeta {
            it.copy(rootGroup = rootOf(entry(BARRIER_EDITED, "Edited", "A"), entry(BARRIER_LOCAL_ONLY, "Local", "Local#1")))
        }

        val gate = Gate()
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val resolver = VaultFileDriftResolve(session, BARRIER_STRINGS, coordinator, mergeCommitGate = { gate.park() })
        // 独立作用域启动旧任务：不得用 `withContext { async { … } }`（withContext 会等待子协程，
        // 在「子协程停在闸门」时反而自锁）；闸门等待另加超时兜底，避免用例级悬挂。
        val task = barrierScope.async { resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) }
        withTimeout(BARRIER_TIMEOUT_MS) { gate.reached.await() }

        // 窗口内发生一次**真实确认写盘**（写的就是当前库自己的文件）
        val revisionBeforeSave = session.persistedBaselineSnapshot()!!.also { it.wipe() }.revision
        assertTrue("窗口内保存必须成功", session.save() is KdbxResult.Success)
        val revisionAfterSave = session.persistedBaselineSnapshot()!!.also { it.wipe() }.revision
        assertEquals("确认写盘必须恰好前移修订一档", revisionBeforeSave + 1, revisionAfterSave)
        val fileAfterSave = vaultA.readBytes()

        gate.release.complete(Unit)
        val result = withTimeout(BARRIER_TIMEOUT_MS) { task.await() }

        assertTrue("窗口内确认写盘 ⇒ 旧合并任务必须以 Failure 收场: $result", result is KdbxResult.Failure)
        assertArrayEquals(
            "旧任务不得覆盖窗口内已确认写盘的新版本（磁盘必须保持保存后的字节）",
            fileAfterSave,
            vaultA.readBytes()
        )
        // 修订条件本身：以窗口前的旧修订提交必须拒绝，以当前修订提交必须放行（正反对照）
        val tree = session.databaseFlow.value ?: error("活动库不得为空")
        assertFalse(
            "携带窗口前旧修订的采用必须被拒绝",
            session.adoptDatabaseIfUnchanged(tree, tree, revisionBeforeSave)
        )
        assertTrue(
            "以当前修订采用必须放行（锚定拒绝不是恒真）",
            session.adoptDatabaseIfUnchanged(tree, tree, revisionAfterSave)
        )
        session.close()
    }

    @Test
    fun `切换库后的旧裁决选择不得作用于新库`() = runBlocking {
        // 库 A：制造同字段双改（本地 B / 磁盘 C）⇒ 要求裁决
        val (session, vaultA) = createVault("barrier-stale-a.kdbx", "BarrierStaleA")
        writeDisk(vaultA, session, rootOf(entry(BARRIER_EDITED, "Edited", "C")))
        session.updateDatabaseMeta { it.copy(rootGroup = rootOf(entry(BARRIER_EDITED, "Edited", "B"))) }
        val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
        val resolver = VaultFileDriftResolve(session, BARRIER_STRINGS, coordinator)
        assertTrue(
            "前提：同字段双改必须要求裁决",
            resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) is KdbxResult.Failure
        )
        assertNotNull("前提：待裁决提示必须置起", coordinator.mergeConflict.value)
        val bytesABefore = vaultA.readBytes()

        // 换库到 B（真实打开路径；会话终止会清空提示）
        val (seedB, vaultB) = createVault("barrier-stale-b.kdbx", "BarrierStaleB")
        seedB.close()
        assertTrue(
            "换库必须成功",
            session.openStream(
                pathIdentifier = vaultB.absolutePath,
                inputStreamProvider = { FileInputStream(vaultB) },
                saveWriter = {},
                passwordChars = masterPassword,
                associatedFile = vaultB
            ) is KdbxResult.Success
        )
        assertArrayEquals("换库后旧库文件不得被改动", bytesABefore, vaultA.readBytes())
        val bytesBBefore = vaultB.readBytes()
        val baselineB = session.persistedBaselineSnapshot() ?: error("换库后必须有 B 的基线")
        val revisionB = baselineB.revision
        baselineB.wipe()
        val treeB = session.databaseFlow.value

        // 过期的裁决选择（属于库 A）落到库 B 上 ⇒ 必须被拒
        val stale = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_LOCAL)

        assertTrue("过期裁决必须被拒绝（不得对新库套用旧决策）: $stale", stale is KdbxResult.Failure)
        assertArrayEquals("库 B 的正式文件不得被过期裁决改写", bytesBBefore, vaultB.readBytes())
        assertSame("库 B 的会话树实例不得被替换", treeB, session.databaseFlow.value)
        assertEquals(
            "库 B 的基线修订不得被动",
            revisionB,
            session.persistedBaselineSnapshot()!!.also { it.wipe() }.revision
        )
        assertArrayEquals("库 A 的正式文件同样保持原样", bytesABefore, vaultA.readBytes())
        session.close()
    }
}
