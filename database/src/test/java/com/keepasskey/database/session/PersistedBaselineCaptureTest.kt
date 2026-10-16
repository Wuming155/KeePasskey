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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * `ISSUE-P1-631`：会话**最后确认持久化版本**快照（漂移合并真实基线）的登记 / 前移 / 清除契约。
 *
 * 覆盖：
 * - 打开成功 ⇒ 基线登记为**源文件字节**（与解析内容同源，不存在重读竞态），路径绑定库身份；
 * - 保存成功 ⇒ 基线前移为**实际写出的字节**；写盘失败 / 抛异常 ⇒ 基线不前移、修订不变；
 * - 换密成功 ⇒ 基线前移为**新凭据密文**（可被新口令解析）；
 * - 锁定 ⇒ 基线清除且修订前移（在途校验失效）；
 * - `adoptDatabaseIfUnchanged` 的基线修订条件：修订前移后拒绝（正反对照）。
 */
class PersistedBaselineCaptureTest {

    private val keyFile = ByteArray(64) { (it + 1).toByte() }

    private fun initialBytes(password: CharArray): ByteArray {
        val entry = KdbxEntry(
            fields = linkedMapOf(
                KdbxConstants.Fields.PASSWORD to ProtectedString("secret#2026", isProtected = true)
            )
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
        return ByteArrayOutputStream().also { KdbxFile.save(it, db, password, keyFile) }.toByteArray()
    }

    private suspend fun openedSession(
        password: CharArray,
        writer: suspend (ByteArray) -> Unit
    ): DatabaseSession {
        val session = DatabaseSession()
        assertTrue(
            "前置：会话打开失败",
            session.openStream(
                pathIdentifier = "baseline.kdbx",
                inputStreamProvider = { ByteArrayInputStream(initialBytes(password)) },
                saveWriter = writer,
                passwordChars = password,
                keyFileData = keyFile
            ).isSuccess
        )
        return session
    }

    private fun snapshotOf(session: DatabaseSession): PersistedDatabaseBaseline =
        runBlocking { session.persistedBaselineSnapshot() } ?: error("基线必须已登记")

    @Test
    fun `打开成功登记源文件字节且绑定库身份`() = runBlocking {
        val pwd = "Baseline#2026".toCharArray()
        val source = initialBytes(pwd)
        val session = DatabaseSession()
        assertTrue(
            session.openStream(
                pathIdentifier = "baseline.kdbx",
                inputStreamProvider = { ByteArrayInputStream(source) },
                saveWriter = {},
                passwordChars = pwd,
                keyFileData = keyFile
            ).isSuccess
        )

        val snapshot = snapshotOf(session)
        assertEquals("基线必须绑定当前库路径标识", "baseline.kdbx", snapshot.pathIdentifier)
        assertTrue("修订必须已前移", snapshot.revision > 0)
        assertArrayEquals(
            "打开后的基线必须与源文件字节逐位一致（解析旁路捕获，不重读文件）",
            source,
            snapshot.bytes
        )
        // 基线字节必须可再解析（漂移合并将以其为三方合并底版）
        val reparsed = KdbxFile.load(ByteArrayInputStream(snapshot.bytes), pwd, keyFile)
        assertTrue("基线必须可再解析", reparsed.rootGroup.entries.isNotEmpty())
        snapshot.wipe()
        session.close()
    }

    @Test
    fun `保存成功后基线前移为实际写出的字节`() = runBlocking {
        val pwd = "Baseline#2026".toCharArray()
        val written = mutableListOf<ByteArray>()
        val session = openedSession(pwd) { bytes -> written += bytes.copyOf() }
        val before = snapshotOf(session)
        val revisionBefore = before.revision
        before.wipe()

        assertTrue("前置：保存必须成功", session.save().isSuccess)

        val after = snapshotOf(session)
        assertEquals("保存成功必须令修订恰好前移一档", revisionBefore + 1, after.revision)
        assertArrayEquals(
            "保存成功后的基线必须等于实际写出的字节",
            written.last(),
            after.bytes
        )
        after.wipe()
        session.close()
    }

    @Test
    fun `保存写盘失败基线不前移且保持旧值`() = runBlocking {
        val pwd = "Baseline#2026".toCharArray()
        val session = openedSession(pwd) { throw IOException("disk full") }
        val before = snapshotOf(session)
        val revisionBefore = before.revision
        val bytesBefore = before.bytes.copyOf()
        before.wipe()

        assertTrue("写盘异常必须如实失败", session.save().isFailure)

        val after = snapshotOf(session)
        assertEquals("写盘失败不得前移基线修订", revisionBefore, after.revision)
        assertArrayEquals("写盘失败必须保持旧基线字节", bytesBefore, after.bytes)
        after.wipe()
        bytesBefore.fill(0)
        session.close()
    }

    @Test
    fun `换密成功后基线前移为新凭据密文`() = runBlocking {
        val oldPwd = "Old#Baseline#2026".toCharArray()
        val newPwd = "New#Baseline#2026".toCharArray()
        val written = mutableListOf<ByteArray>()
        val session = openedSession(oldPwd) { bytes -> written += bytes.copyOf() }

        assertTrue("前置：换密必须成功", session.changeCredentials(newPwd).isSuccess)

        val after = snapshotOf(session)
        assertArrayEquals("换密后的基线必须等于换密实际写出的字节", written.last(), after.bytes)
        val reparsed = KdbxFile.load(ByteArrayInputStream(after.bytes), newPwd, keyFile)
        assertTrue("换密后的基线必须可被新口令解析", reparsed.rootGroup.entries.isNotEmpty())
        after.wipe()
        session.close()
    }

    @Test
    fun `锁定清除基线`() = runBlocking {
        val pwd = "Baseline#2026".toCharArray()
        val session = openedSession(pwd) {}
        assertNotNull("前置：打开后基线必须存在", session.persistedBaselineSnapshot())
        session.persistedBaselineSnapshot()?.wipe()

        session.lock()

        assertNull("锁定后不得残留持久化基线（整库密文快照随会话销毁）", session.persistedBaselineSnapshot())
        session.close()
    }

    @Test
    fun `校验-采用在基线修订前移后拒绝且同修订放行`() = runBlocking {
        val pwd = "Baseline#2026".toCharArray()
        val session = openedSession(pwd) {}
        val expected = session.databaseFlow.value ?: error("活动库不得为空")
        val snapshot = snapshotOf(session)
        val revision = snapshot.revision
        snapshot.wipe()

        // 反例：窗口内发生一次确认写盘 ⇒ 修订前移 ⇒ 以旧修订提交必须拒绝
        assertTrue("前置：窗口内保存必须成功", session.save().isSuccess)
        val rejected = session.adoptDatabaseIfUnchanged(
            expectedAtCycleStart = expected,
            replacement = expected,
            expectedPersistedBaselineRevision = revision
        )
        assertFalse("基线修订前移后，携带旧修订的采用必须 fail-closed 拒绝", rejected)

        // 正例：以当前修订提交（同修订）⇒ 采用成功，锚定「拒绝」不是恒真
        val current = session.persistedBaselineSnapshot()!!.also { it.wipe() }.revision
        val adopted = session.adoptDatabaseIfUnchanged(
            expectedAtCycleStart = expected,
            replacement = expected,
            expectedPersistedBaselineRevision = current
        )
        assertTrue("同一修订下采用必须放行", adopted)
        session.close()
    }

    @Test
    fun `捕获流超限即整体放弃且不阻断读取`() = runBlocking {
        val payload = ByteArray(64) { it.toByte() }
        val capturing = PersistedBaselineCapturingInputStream(ByteArrayInputStream(payload), maxCaptureBytes = 8)
        val drained = ByteArrayOutputStream()
        capturing.use { input ->
            val buffer = ByteArray(16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                drained.write(buffer, 0, read)
            }
        }
        assertArrayEquals("超限只影响捕获，读取必须完整透传", payload, drained.toByteArray())
        assertNull("超限必须整体放弃捕获（不留半份基线）", capturing.capturedBytes())
        capturing.wipe()

        // 未超限对照：捕获完整且可清零
        val ok = PersistedBaselineCapturingInputStream(ByteArrayInputStream(payload), maxCaptureBytes = payload.size)
        ok.use { it.readBytes() }
        val captured = ok.capturedBytes() ?: error("限额内必须产出捕获")
        assertArrayEquals("限额内捕获必须完整", payload, captured)
        ok.wipe()
        assertNull("wipe 后内部缓冲必须已清零（不再产出捕获）", ok.capturedBytes())
    }

    @Test
    fun `打开失败不登记基线`() = runBlocking {
        val pwd = "Baseline#2026".toCharArray()
        val session = DatabaseSession()
        val wrongPwd = "Wrong#Password".toCharArray()
        val result = session.openStream(
            pathIdentifier = "baseline.kdbx",
            inputStreamProvider = { ByteArrayInputStream(initialBytes(pwd)) },
            saveWriter = {},
            passwordChars = wrongPwd,
            keyFileData = keyFile
        )
        assertTrue("错误口令必须如实失败", result.isFailure)
        assertNull("打开失败不得登记持久化基线", session.persistedBaselineSnapshot())
        session.close()
    }
}
