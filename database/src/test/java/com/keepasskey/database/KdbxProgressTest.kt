package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.file.KdbxProgress
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ISSUE-P3-368：读写链路进度回调（AC① 的 JVM 可测面）。
 *
 * 覆盖验收要点：
 * - 打开链进度**单调不减且到 1.0**（已知总长的确定字节进度；含 KDF 分段不确定态）；
 * - 未知总长（SAF 流形态）退分段不确定态、结束仍到 1.0（AC② 分段不确定允许项）；
 * - 保存链进度发出（分段：准备 / KDF 不确定 / 序列化 / 0.9 物化完成）；
 * - 回调抛异常不影响保存/打开成功与清零路径（AC③：进度层绝不打断主流程）；
 * - 会话级 Flow（`DatabaseSession.ioProgress`）打开与保存两链均到 1.0（AC① Flow 形态）。
 */
class KdbxProgressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 快速测试库：AES-KDF（低轮数）+ 单条目，保存/打开毫秒级完成 */
    private fun buildTestDatabase(): KdbxDatabase {
        val header = KdbxHeader.createDefault(useArgon2 = false)
        val testHeader = header.copy(
            kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 9 }, rounds = 50L)
        )
        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("ProgressEntry", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("FieldSecret!42", isProtected = true)
            )
        )
        return KdbxDatabase(
            header = testHeader,
            databaseName = "ProgressVault",
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
    }

    private fun saveToBytes(
        db: KdbxDatabase,
        password: CharArray,
        onProgress: ((Float?) -> Unit)? = null
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password, onProgress = onProgress)
        return bos.toByteArray()
    }

    /** 确定进度（非 null 值）必须单调不减 */
    private fun assertMonotonic(values: List<Float>) {
        assertTrue("确定进度至少发出一次", values.isNotEmpty())
        for (i in 1 until values.size) {
            assertTrue(
                "进度必须单调不减: ${values[i - 1]} -> ${values[i]}",
                values[i] >= values[i - 1]
            )
        }
    }

    @Test
    fun `打开链进度单调不减且到1_已知总长`() {
        val password = "ProgressPass@2026".toCharArray()
        val bytes = saveToBytes(buildTestDatabase(), password)
        val events = mutableListOf<Float?>()

        val loaded = KdbxFile.load(
            ByteArrayInputStream(bytes),
            password,
            totalBytes = bytes.size.toLong(),
            onProgress = { events.add(it) }
        )

        assertEquals("ProgressVault", loaded.databaseName)
        val values = events.filterNotNull()
        assertMonotonic(values)
        assertEquals("链路完成必须到 1.0", KdbxProgress.DONE, values.last(), 0f)
        assertTrue("KDF 派生段应发分段不确定态（null）", events.contains(null))
    }

    @Test
    fun `打开链未知总长退分段不确定态且到1`() {
        val password = "ProgressPass@2026".toCharArray()
        val bytes = saveToBytes(buildTestDatabase(), password)
        val events = mutableListOf<Float?>()

        // 不传 totalBytes = SAF Uri 等未知长度流形态
        val loaded = KdbxFile.load(
            ByteArrayInputStream(bytes),
            password,
            onProgress = { events.add(it) }
        )

        assertEquals("ProgressVault", loaded.databaseName)
        val values = events.filterNotNull()
        assertMonotonic(values)
        assertEquals(KdbxProgress.DONE, values.last(), 0f)
        // KDF 段 + 载荷段各发一次不确定态（载荷段因总长未知无法确定推进）
        assertTrue("未知总长载荷段应整体为分段不确定态", events.count { it == null } >= 2)
    }

    @Test
    fun `保存链进度分段发出到物化完成`() {
        val password = "ProgressPass@2026".toCharArray()
        val events = mutableListOf<Float?>()

        val bytes = saveToBytes(buildTestDatabase(), password) { events.add(it) }

        assertTrue("保存产物应非空", bytes.isNotEmpty())
        val values = events.filterNotNull()
        assertMonotonic(values)
        assertTrue("KDF 派生段应发分段不确定态（null）", events.contains(null))
        assertEquals(
            "序列化侧终点为整库密文物化完成（落盘段由会话层补 1.0）",
            KdbxProgress.SAVE_SERIALIZED,
            values.last(),
            0f
        )
        assertTrue("起点应为 0", values.first() == 0f)
    }

    @Test
    fun `进度回调抛异常不影响保存与打开`() {
        val password = "ProgressPass@2026".toCharArray()
        val throwing: (Float?) -> Unit = { throw IllegalStateException("进度回调故障") }

        // 保存：回调抛异常不得中断序列化与 finally 密钥清零路径
        val bytes = saveToBytes(buildTestDatabase(), password, throwing)
        assertTrue("回调抛异常时保存仍须成功", bytes.isNotEmpty())

        // 打开：回调抛异常不得中断解密/解压/XML 与头部密钥清零路径（含字节进度流内上报点）
        val loaded = KdbxFile.load(
            ByteArrayInputStream(bytes),
            password,
            totalBytes = bytes.size.toLong(),
            onProgress = throwing
        )
        assertEquals("ProgressVault", loaded.databaseName)
        assertEquals(1, loaded.rootGroup.entries.size)
        assertEquals("FieldSecret!42", loaded.rootGroup.entries[0].password?.readString())
    }

    @Test
    fun `会话打开与保存进度流均到1`() = runBlocking {
        val testFile = File(tempFolder.root, "progress_session_vault.kdbx")
        val password = "SessionProgress!7".toCharArray()
        val session = DatabaseSession()

        val createResult = session.create(testFile, "ProgressVault", password, useArgon2 = false)
        assertTrue(createResult.isSuccess)

        session.lock()
        val openResult = session.open(testFile, password)
        assertTrue(openResult.isSuccess)
        assertEquals(
            "打开链（含本地文件已知总长）完成后 ioProgress 必须为 1.0",
            KdbxProgress.DONE,
            session.ioProgress.value ?: -1f,
            0f
        )

        session.saveEntry(
            KdbxEntry(
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("AfterOpen", isProtected = false)
                )
            )
        )
        val saveResult = session.save()
        assertTrue(saveResult.isSuccess)
        assertEquals(
            "保存链（序列化 + 落盘）完成后 ioProgress 必须为 1.0",
            KdbxProgress.DONE,
            session.ioProgress.value ?: -1f,
            0f
        )

        session.close()
    }
}
