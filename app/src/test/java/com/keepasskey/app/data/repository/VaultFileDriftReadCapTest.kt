package com.keepasskey.app.data.repository

import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ISSUE-P3-617` 的漂移源文件读取上限回归。
 *
 * 缺陷形态：`VaultFileDriftResolve.readCurrentBytes` 对本地文件 / SAF 流调用无上限整流
 * `readBytes()`——未知或错误申报长度可绕过读取前检查，超大输入在解析限制生效前分配完整
 * `ByteArray`（OOM/ANR）。现改为：本地文件按精确 `length()` 快拒 + 分块累计封顶，
 * SAF 流由累计上限兜底，超限一律按 fail-closed 报合并 / 重载失败。
 *
 * 本用例以注入的小上限行为级锁定「超限即失败、不崩溃、不污染会话」，并以生产上限对照
 * 「合法小库恒通过」（上限取值本身见 `MAX_DRIFT_READ_BYTES` KDoc 推导）；
 * `KdbxFile` 本体无需改动——其 `load` 全链路流式（外层 Header 长度驱动分配守卫 +
 * 128 MiB 解压输出守卫），无整流点（见批次文档核验记录）。
 */
class VaultFileDriftReadCapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val masterPassword = "DriftCap#2026".toCharArray()

    private fun seedSession(workDir: File): DatabaseSession = runBlocking {
        val vaultFile = File(workDir, "drift.kdbx")
        val session = DatabaseSession()
        val created = session.create(
            file = vaultFile,
            name = "Drift",
            passwordChars = masterPassword,
            useArgon2 = false
        )
        assertTrue("前提：建库必须成功: $created", created is KdbxResult.Success)
        val root = KdbxGroup(
            id = KdbxUuid.fromHexString("00000000000000000000000000000001"),
            name = "Root",
            entries = listOf(
                KdbxEntry(
                    id = KdbxUuid.fromHexString("00000000000000000000000000000002"),
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString("Site", false),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("pwd", true)
                    )
                )
            )
        )
        session.updateDatabaseMeta { it.copy(rootGroup = root) }
        assertTrue("前提：本地落盘成功", session.save() is KdbxResult.Success)
        session
    }

    @Test
    fun `源文件超出注入上限时合并失败且会话不受损`() {
        val session = seedSession(tempFolder.newFolder())
        val before = session.databaseFlow.value ?: error("前提：活动库不得为空")

        // 注入 16 字节上限：真实库文件（KB 量级）必超限——旧实现会在此整流读入
        val resolver = VaultFileDriftResolve(
            databaseSession = session,
            strings = STRINGS,
            driftCoordinator = null,
            maxDriftReadBytes = 16L
        )

        val result = runBlocking { resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) }

        assertTrue("超限必须 fail-closed 报失败（不得崩溃 / OOM）：$result", result is KdbxResult.Failure)
        assertEquals(
            "失败不得触碰活动会话树",
            before.rootGroup.allEntries().size,
            session.databaseFlow.value?.rootGroup?.allEntries()?.size
        )
    }

    @Test
    fun `合法小库在生产上限下合并通过`() {
        val session = seedSession(tempFolder.newFolder())

        val resolver = VaultFileDriftResolve(
            databaseSession = session,
            strings = STRINGS,
            driftCoordinator = null
        )

        val result = runBlocking { resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE) }

        assertTrue("生产上限不得误拒合法小库：$result", result is KdbxResult.Success)
    }

    @Test
    fun `生产上限覆盖任何可解析库的密文体`() {
        // 推导锁死：解析侧解压输出守卫 128 MiB（database 内部常量，按值引用）；
        // 密文体 ≈ 明文体（不压缩）或更小（GZip）+ 不足 1% 的块帧开销——
        // 160 MiB 在此之上留足余量，超限文件不可能被解析，放行即浪费内存。
        // 另与同步下载上限（128 MiB，约束远端响应体）不冲突：本上限约束本地 / SAF 源文件物化。
        assertEquals(160L * 1024 * 1024, VaultFileDriftResolve.MAX_DRIFT_READ_BYTES)
    }

    private companion object {
        val STRINGS = StringsProvider { _, _ -> "" }
    }
}
