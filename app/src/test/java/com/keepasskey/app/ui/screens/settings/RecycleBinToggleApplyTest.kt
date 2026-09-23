package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.autofill.AutofillFieldBlocklistStore
import com.keepasskey.app.autofill.AutofillSaveBlocklistStore
import com.keepasskey.app.autofill.testHmacFieldSignatureSource
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ISSUE-P2-270 整改验收（契约测试）：设置页「回收站」开关必须真实生效。
 *
 * 此前 `setRecycleBinEnabled` 仅回写设置页内存回显（假开关）：既不更新库 Meta 也不落盘，
 * 删除分流全部读库 Meta 真值 `db.recycleBinEnabled`（RecycleBinCoordinator 单条 / 分组 / 批量
 * 三处），与 UI 开关无任何数据通路——关闭后界面显示「已关闭」，删除却仍软删移入回收站，
 * 方向与界面声明相反；且回填通道刻意跳过该字段，冷启动即回缺省 true（显示漂移）。
 * 本测试锁定整改后的契约：
 * ① 关闭后库 Meta（`KdbxDatabase.recycleBinEnabled`）置 false 并落盘，重开文件读回 false；
 *   设置页回显随库真值统一下发（单一真相源，不再自持内存状态）；
 * ② 重新开启写回 true 并落盘读回（开关双向真实）；
 * ③ 无活动库时如实 no-op——不产生假变更、不置 DIRTY、回显保持文件头真值；
 * ④ 另产出「RecycleBinEnabled=false」互操作对拍产物（`build/interop-probe/`），
 *   供官方实现外用核验（见 [probeInteropArtifact] 的 KDoc）。
 *
 * 有意使用真实 [CoroutineScope] + 真实时间轮询（非 runTest 虚拟时间）：
 * 被测链路内含 `Dispatchers.Default` 上的真实 KDF 重派生与落盘，
 * 与 `Argon2ParametersApplyTest` 同一手法。
 */
class RecycleBinToggleApplyTest {

    /** 控制器真实作用域（与生产 viewModelScope 同语义；用毕取消） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun awaitCondition(describe: String, timeoutMs: Long = 60_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                error("等待「$describe」超时（${timeoutMs}ms）")
            }
            Thread.sleep(10)
        }
    }

    private fun buildController(session: DatabaseSession): SettingsPreferencesController =
        SettingsPreferencesController(
            settingsRepository = FakeSettingsRepository(),
            vaultRepository = FakeVaultRepository(),
            autofillBlocklistStore = AutofillBlocklistStore(null),
            autofillSaveBlocklistStore = AutofillSaveBlocklistStore(null),
            autofillFieldBlocklistStore = AutofillFieldBlocklistStore(null, testHmacFieldSignatureSource()),
            debugLogBuffer = DebugLogBuffer(),
            databaseSession = session,
            scope = scope
        )

    private fun createSession(file: File, password: CharArray): DatabaseSession {
        val session = DatabaseSession()
        runBlocking {
            assertTrue(session.create(file, "recycle-bin-toggle", password) is KdbxResult.Success)
        }
        return session
    }

    @Test
    fun `关闭回收站后库 Meta 置 false 落盘重开读回且回显随库真值下发`() {
        val dir = Files.createTempDirectory("recycle_bin_off").toFile()
        val file = File(dir, "recycle-off.kdbx")
        val password = "recycle-bin-toggle-password".toCharArray()

        val session = createSession(file, password)
        val controller = buildController(session)

        // 回填通道就绪：回显 = 库 Meta 真值（建库缺省 true）
        awaitCondition("回显经库 Meta 回填为 true") {
            controller.databaseConfigState.value.recycleBinEnabled
        }

        controller.setRecycleBinEnabled(false)

        // ① 会话库 Meta 置 false（updateDatabaseMeta 真实下发，非内存回显）
        awaitCondition("会话库 Meta 置 false") {
            session.databaseFlow.value?.recycleBinEnabled == false
        }
        // ② 回显随库真值统一下发（init 回填通道，不再自持状态）
        awaitCondition("回显随库真值下发为 false") {
            !controller.databaseConfigState.value.recycleBinEnabled
        }
        // ③ 落盘完成（save 置回 OPENED）
        awaitCondition("落盘完成") {
            file.exists() && file.length() > 0 &&
                session.state.value == DatabaseSession.SessionState.OPENED
        }

        val reopen = DatabaseSession()
        runBlocking {
            assertTrue(reopen.open(file, password) is KdbxResult.Success)
        }
        assertEquals("重开文件 Meta 应为 false（开关真实写库）", false, reopen.databaseFlow.value!!.recycleBinEnabled)
        runBlocking { reopen.close() }
        runBlocking { session.close() }
    }

    @Test
    fun `重新开启回收站后写回 true 并落盘读回`() {
        val dir = Files.createTempDirectory("recycle_bin_on").toFile()
        val file = File(dir, "recycle-on.kdbx")
        val password = "recycle-bin-toggle-password".toCharArray()

        val session = createSession(file, password)
        val controller = buildController(session)

        controller.setRecycleBinEnabled(false)
        awaitCondition("关闭落盘") {
            file.exists() && session.state.value == DatabaseSession.SessionState.OPENED &&
                session.databaseFlow.value?.recycleBinEnabled == false
        }

        controller.setRecycleBinEnabled(true)
        awaitCondition("会话库 Meta 置回 true") {
            session.databaseFlow.value?.recycleBinEnabled == true
        }
        awaitCondition("再次落盘完成") {
            session.state.value == DatabaseSession.SessionState.OPENED
        }

        val reopen = DatabaseSession()
        runBlocking {
            assertTrue(reopen.open(file, password) is KdbxResult.Success)
        }
        assertEquals("重开文件 Meta 应为 true（重新开启真实写库）", true, reopen.databaseFlow.value!!.recycleBinEnabled)
        runBlocking { reopen.close() }
        runBlocking { session.close() }
    }

    @Test
    fun `无活动库时开关如实 no-op 不产生假变更`() {
        val session = DatabaseSession()
        val controller = buildController(session)
        // 未打开库 → updateDatabaseMeta 与 save() 均如实 no-op/失败
        controller.setRecycleBinEnabled(false)
        awaitCondition("调度完成") { true }
        Thread.sleep(200)
        assertTrue("库应保持未挂载（不产生假变更）", session.databaseFlow.value == null)
        assertTrue("状态不得被置为 DIRTY", session.state.value != DatabaseSession.SessionState.DIRTY)
        // 回显保持一次性占位真值，未谎报变更
        assertTrue(controller.databaseConfigState.value.recycleBinEnabled)
    }

    /**
     * 产出「RecycleBinEnabled=false」对拍产物（ISSUE-P2-270 AC⑤）。
     *
     * 走真实开关链路：建库 → 控制器关闭回收站 → updateDatabaseMeta + save 落盘，
     * 产物留在磁盘供官方实现外用对拍（手法与 `OwnProductInteropProbeTest` 一致——
     * 自家 reader 读回属必要但不充分证据）。
     *
     * ## 外用验证命令（本机工具链已实测可用：keepassxc-cli 2.7.12）
     *
     * ```
     * echo -n '<PROBE_PASSWORD>' | keepassxc-cli db-info -q <产物路径>
     * ```
     *
     * 期望：官方客户端正常打开（官方对 Meta 内 `<RecycleBinEnabled>False</RecycleBinEnabled>`
     * 的语义为「删除即物理删除」，元素大小写与官方 True/False 约定一致，缺省 true 已由
     * `KdbxMetaXmlSemanticsTest` 锁定）。
     */
    @Test
    fun `产出 RecycleBinEnabled 为 false 的互操作对拍产物`() {
        val dir = Files.createTempDirectory("recycle_bin_probe").toFile()
        val file = File(dir, "recycle-off-probe.kdbx")
        val password = PROBE_PASSWORD.toCharArray()

        val session = createSession(file, password)
        val controller = buildController(session)
        controller.setRecycleBinEnabled(false)
        awaitCondition("关闭落盘完成") {
            file.exists() && file.length() > 0 &&
                session.databaseFlow.value?.recycleBinEnabled == false &&
                session.state.value == DatabaseSession.SessionState.OPENED
        }

        // ── 自家往返（必要但不充分）─────────────────────────────────────────
        val reopen = DatabaseSession()
        runBlocking {
            assertTrue(reopen.open(file, password) is KdbxResult.Success)
        }
        assertEquals(false, reopen.databaseFlow.value!!.recycleBinEnabled)
        runBlocking { reopen.close() }

        // ── 落盘留存供外用对拍 ────────────────────────────────────────────
        val fileBytes = file.readBytes()
        val outDir = File(System.getProperty("user.dir"), PROBE_DIR_NAME).apply { mkdirs() }
        val outFile = File(outDir, PROBE_FILE_NAME)
        outFile.writeBytes(fileBytes)
        val sha256Hex = HashUtil.sha256(fileBytes).joinToString("") { "%02x".format(it) }
        File(outDir, PROBE_NOTE_NAME).writeText(
            buildString {
                appendLine("# 对拍探针产物说明（由 RecycleBinToggleApplyTest 生成，可随时重跑）")
                appendLine()
                appendLine("- 产物：`${outFile.absolutePath}`")
                appendLine("- 字节数：${fileBytes.size}")
                appendLine("- SHA-256：`$sha256Hex`")
                appendLine("- 口令：`$PROBE_PASSWORD`")
                appendLine("- Meta 状态：`RecycleBinEnabled = false`（经真实开关链路写入：updateDatabaseMeta + save）")
                appendLine()
                appendLine("## 外用验证（官方实现端到端对拍）")
                appendLine()
                appendLine("```bash")
                appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli db-info -q '${outFile.absolutePath}'")
                appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli ls -q -R '${outFile.absolutePath}'")
                appendLine("```")
                appendLine()
                appendLine("期望：官方客户端正常打开（禁用回收站状态下官方语义为「删除即物理删除」）。")
            }
        )
        assertTrue("产物必须留在磁盘上供外用对拍", outFile.isFile && outFile.length() > 200)
        runBlocking { session.close() }
    }

    private companion object {
        const val PROBE_PASSWORD = "recycle-bin-probe-password-2026"
        const val PROBE_DIR_NAME = "build/interop-probe"
        const val PROBE_FILE_NAME = "keepasskey-recycle-bin-off-probe.kdbx"
        const val PROBE_NOTE_NAME = "RECYCLE_BIN_PROBE.md"
    }
}
