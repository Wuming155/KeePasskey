package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.autofill.AutofillFieldBlocklistStore
import com.keepasskey.app.autofill.AutofillSaveBlocklistStore
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfParameters
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
 * ISSUE-P2-271 整改验收（契约测试）：加密算法 / KDF 算法选择器必须真实生效。
 *
 * 此前 `setEncryptionAlgorithm` / `setKdfAlgorithm` 仅回写设置页内存回显（假开关）：
 * 既不更新会话头也不落盘，文件头一个字节未动——界面声称已切换到 ChaCha20 / Argon2d 等，
 * 实际库仍以旧算法加密，用户据界面形成「已更换更强算法」的错误认知（安全语义失真，
 * 同 ISSUE-P2-22 / P2-270 的假闭环族）。
 * 本测试锁定整改后的契约：
 * ① 选定加密算法后会话头 `cipherUuid` 真实变化、落盘后经同一主密码重开成功且头一致；
 * ② 换 KDF 变体（Argon2id → Argon2d）携带现行 I/M/P，落盘重开读回同型；
 * ③ 换入 AES-KDF 补齐官方缺省 rounds（不留参数缺失的半成品头），重开成功；
 * ④ 回显随库真值统一下发（init 头映射通道，不再自持内存状态）；
 * ⑤ 无活动会话 / 未知标签时如实 no-op，不产生假变更；
 * ⑥ 产出「Twofish + Argon2d」互操作对拍产物供官方实现外用核验（见 [probeInteropArtifact]）。
 *
 * 有意使用真实 [CoroutineScope] + 真实时间轮询（非 runTest 虚拟时间）：
 * 被测链路内含 `Dispatchers.Default` 上的真实 KDF 重派生与落盘，
 * 与 `RecycleBinToggleApplyTest` 同一手法。
 */
class AlgorithmSelectionApplyTest {

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
            autofillFieldBlocklistStore = AutofillFieldBlocklistStore(null),
            debugLogBuffer = DebugLogBuffer(),
            databaseSession = session,
            scope = scope
        )

    /** 建库（缺省 AES-256-CBC + Argon2id）并等待回填通道就绪（回显 = 文件头真值）。 */
    private fun createSession(file: File, name: String, password: CharArray): Pair<DatabaseSession, SettingsPreferencesController> {
        val session = DatabaseSession()
        runBlocking {
            assertTrue(session.create(file, name, password) is KdbxResult.Success)
        }
        val controller = buildController(session)
        awaitCondition("回填通道就绪") {
            controller.databaseConfigState.value.kdfAlgorithm == KdfLabels.ARGON2ID &&
                controller.databaseConfigState.value.encryptionAlgorithm == CipherLabels.AES_256_CBC
        }
        return session to controller
    }

    private fun reopenAndAssertHeader(file: File, password: CharArray, verify: (DatabaseSession) -> Unit) {
        val reopen = DatabaseSession()
        runBlocking {
            assertTrue("重开应成功（以同一主密码解锁）", reopen.open(file, password) is KdbxResult.Success)
        }
        verify(reopen)
        runBlocking { reopen.close() }
    }

    @Test
    fun `切换加密算法后文件头真实变化并以同一主密码解锁成功`() {
        val dir = Files.createTempDirectory("cipher_switch").toFile()
        val file = File(dir, "cipher-switch.kdbx")
        val password = "cipher-switch-password".toCharArray()

        val (session, controller) = createSession(file, "cipher-switch", password)

        controller.setEncryptionAlgorithm(CipherLabels.CHACHA20)

        awaitCondition("会话头 cipherUuid 切到 ChaCha20") {
            session.databaseFlow.value?.header?.cipherUuid == KdbxConstants.Cipher.CHACHA20
        }
        awaitCondition("落盘完成") {
            file.exists() && file.length() > 0 &&
                session.state.value == DatabaseSession.SessionState.OPENED
        }
        // 回显随库真值统一下发（不再自持内存状态）
        awaitCondition("回显随头真值下发") {
            controller.databaseConfigState.value.encryptionAlgorithm == CipherLabels.CHACHA20
        }

        reopenAndAssertHeader(file, password) { reopen ->
            assertEquals(
                "重开文件外层头应为 ChaCha20（算法真实写库）",
                KdbxConstants.Cipher.CHACHA20,
                reopen.databaseFlow.value!!.header.cipherUuid
            )
        }
        runBlocking { session.close() }
    }

    @Test
    fun `Argon2id 换 Argon2d 携带现行参数且落盘重开读回同型`() {
        val dir = Files.createTempDirectory("kdf_argon2d").toFile()
        val file = File(dir, "kdf-argon2d.kdbx")
        val password = "kdf-argon2d-password".toCharArray()

        val (session, controller) = createSession(file, "kdf-argon2d", password)

        controller.setKdfAlgorithm(KdfLabels.ARGON2D)

        awaitCondition("会话头 KDF 切到 Argon2d") {
            (session.databaseFlow.value?.header?.kdfParameters as? KdfParameters.Argon2)
                ?.type == KdfParameters.Argon2.Argon2Type.ARGON2D
        }
        awaitCondition("落盘完成") {
            session.state.value == DatabaseSession.SessionState.OPENED
        }
        awaitCondition("回显随头真值下发") {
            controller.databaseConfigState.value.kdfAlgorithm == KdfLabels.ARGON2D
        }

        reopenAndAssertHeader(file, password) { reopen ->
            val kdf = reopen.databaseFlow.value!!.header.kdfParameters
            assertTrue("重开文件 KDF 应为 Argon2，实际 $kdf", kdf is KdfParameters.Argon2)
            assertEquals(
                KdfParameters.Argon2.Argon2Type.ARGON2D,
                (kdf as KdfParameters.Argon2).type
            )
            // 换型携带现行 I/M/P（建库缺省 2 轮 / 64MB / 并行 2），不留参数缺失的半成品头
            assertEquals(2L, kdf.iterations)
            assertEquals(64L * 1024 * 1024, kdf.memoryInBytes)
            assertEquals(2, kdf.parallelism)
        }
        runBlocking { session.close() }
    }

    @Test
    fun `换入 AES-KDF 补齐官方缺省 rounds 且落盘重开解锁成功`() {
        val dir = Files.createTempDirectory("kdf_aeskdf").toFile()
        val file = File(dir, "kdf-aeskdf.kdbx")
        val password = "kdf-aeskdf-password".toCharArray()

        val (session, controller) = createSession(file, "kdf-aeskdf", password)

        controller.setKdfAlgorithm(KdfLabels.AES_KDF)

        awaitCondition("会话头 KDF 切到 AES-KDF") {
            session.databaseFlow.value?.header?.kdfParameters is KdfParameters.Aes
        }
        awaitCondition("落盘完成") {
            session.state.value == DatabaseSession.SessionState.OPENED
        }
        awaitCondition("回显随头真值下发") {
            controller.databaseConfigState.value.kdfAlgorithm == KdfLabels.AES_KDF
        }

        reopenAndAssertHeader(file, password) { reopen ->
            val kdf = reopen.databaseFlow.value!!.header.kdfParameters
            assertTrue("重开文件 KDF 应为 AES-KDF，实际 $kdf", kdf is KdfParameters.Aes)
            assertEquals(
                "AES-KDF rounds 应补齐官方缺省（不留半成品头）",
                KdbxConstants.Kdf.DEFAULT_AES_KDF_ROUNDS,
                (kdf as KdfParameters.Aes).rounds
            )
        }
        runBlocking { session.close() }
    }

    @Test
    fun `同算法重复选择与未知标签如实 no-op 不产生假变更`() {
        val dir = Files.createTempDirectory("algo_noop").toFile()
        val file = File(dir, "algo-noop.kdbx")
        val password = "algo-noop-password".toCharArray()

        val (session, controller) = createSession(file, "algo-noop", password)
        val headerBefore = session.databaseFlow.value!!.header

        // 与现行算法相同：避免无谓重派生，直接 no-op
        controller.setEncryptionAlgorithm(CipherLabels.AES_256_CBC)
        // 已是 Argon2id：同型 no-op
        controller.setKdfAlgorithm(KdfLabels.ARGON2ID)
        // 未知标签：反查不到算法 ID，如实 no-op
        controller.setEncryptionAlgorithm("不存在的算法")
        controller.setKdfAlgorithm("不存在的KDF")

        Thread.sleep(500)
        assertEquals("库头不得变化（no-op）", headerBefore, session.databaseFlow.value!!.header)
        assertTrue("状态不得停留 DIRTY", session.state.value != DatabaseSession.SessionState.DIRTY)
        runBlocking { session.close() }
    }

    @Test
    fun `无活动会话时选择器如实 no-op 不产生假变更`() {
        val session = DatabaseSession()
        val controller = buildController(session)
        // 未打开库 → updateDatabaseMeta 与 save() 均如实 no-op/失败
        controller.setEncryptionAlgorithm(CipherLabels.CHACHA20)
        controller.setKdfAlgorithm(KdfLabels.ARGON2D)
        Thread.sleep(200)
        assertTrue("库应保持未挂载（不产生假变更）", session.databaseFlow.value == null)
        assertTrue("状态不得被置为 DIRTY", session.state.value != DatabaseSession.SessionState.DIRTY)
        // 回显保持空态占位，未谎报变更
        assertEquals("", controller.databaseConfigState.value.encryptionAlgorithm)
        assertEquals("", controller.databaseConfigState.value.kdfAlgorithm)
    }

    /**
     * 产出「Twofish + Argon2d」对拍产物（ISSUE-P2-271 AC⑥）。
     *
     * 一件产物同时覆盖两个选择轴：加密算法 AES→Twofish、KDF Argon2id→Argon2d，
     * 均走真实选择链路（updateDatabaseMeta + save）落盘，产物留在磁盘供官方实现外用对拍
     * （手法与 `RecycleBinToggleApplyTest` 一致——自家 reader 读回属必要但不充分证据）。
     *
     * ## 外用验证命令（本机工具链已实测可用：keepassxc-cli 2.7.12）
     *
     * ```
     * echo -n '<PROBE_PASSWORD>' | keepassxc-cli db-info -q <产物路径>
     * ```
     *
     * 期望：官方客户端正常打开，`db-info` 报告加密算法 Twofish、KDF Argon2d——
     * 证明按本仓选择器落盘的新头组合在官方实现侧真实可解锁。
     */
    @Test
    fun `产出 Twofish 与 Argon2d 组合的互操作对拍产物`() {
        val dir = Files.createTempDirectory("algo_probe").toFile()
        val file = File(dir, "algo-probe.kdbx")
        val password = PROBE_PASSWORD.toCharArray()

        val (session, controller) = createSession(file, "algo-probe", password)
        controller.setEncryptionAlgorithm(CipherLabels.TWOFISH_CBC)
        awaitCondition("加密算法切到 Twofish 落盘") {
            session.databaseFlow.value?.header?.cipherUuid == KdbxConstants.Cipher.TWOFISH &&
                session.state.value == DatabaseSession.SessionState.OPENED
        }
        controller.setKdfAlgorithm(KdfLabels.ARGON2D)
        awaitCondition("KDF 切到 Argon2d 落盘") {
            (session.databaseFlow.value?.header?.kdfParameters as? KdfParameters.Argon2)
                ?.type == KdfParameters.Argon2.Argon2Type.ARGON2D &&
                session.state.value == DatabaseSession.SessionState.OPENED
        }

        // ── 自家往返（必要但不充分）─────────────────────────────────────────
        reopenAndAssertHeader(file, password) { reopen ->
            assertEquals(KdbxConstants.Cipher.TWOFISH, reopen.databaseFlow.value!!.header.cipherUuid)
            assertEquals(
                KdfParameters.Argon2.Argon2Type.ARGON2D,
                (reopen.databaseFlow.value!!.header.kdfParameters as KdfParameters.Argon2).type
            )
        }

        // ── 落盘留存供外用对拍 ────────────────────────────────────────────
        val fileBytes = file.readBytes()
        val outDir = File(System.getProperty("user.dir"), PROBE_DIR_NAME).apply { mkdirs() }
        val outFile = File(outDir, PROBE_FILE_NAME)
        outFile.writeBytes(fileBytes)
        val sha256Hex = HashUtil.sha256(fileBytes).joinToString("") { "%02x".format(it) }
        File(outDir, PROBE_NOTE_NAME).writeText(
            buildString {
                appendLine("# 对拍探针产物说明（由 AlgorithmSelectionApplyTest 生成，可随时重跑）")
                appendLine()
                appendLine("- 产物：`${outFile.absolutePath}`")
                appendLine("- 字节数：${fileBytes.size}")
                appendLine("- SHA-256：`$sha256Hex`")
                appendLine("- 口令：`$PROBE_PASSWORD`")
                appendLine("- 外层头：cipher = Twofish-CBC、KDF = Argon2d（经真实选择链路写入：updateDatabaseMeta + save，ISSUE-P2-271）")
                appendLine()
                appendLine("## 外用验证（官方实现端到端对拍）")
                appendLine()
                appendLine("```bash")
                appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli db-info -q '${outFile.absolutePath}'")
                appendLine("```")
                appendLine()
                appendLine("期望：官方客户端正常打开，报告加密算法 Twofish、KDF Argon2d。")
            }
        )
        assertTrue("产物必须留在磁盘上供外用对拍", outFile.isFile && outFile.length() > 200)
        runBlocking { session.close() }
    }

    private companion object {
        const val PROBE_PASSWORD = "algorithm-probe-password-2026"
        const val PROBE_DIR_NAME = "build/interop-probe"
        const val PROBE_FILE_NAME = "keepasskey-twofish-argon2d-probe.kdbx"
        const val PROBE_NOTE_NAME = "ALGORITHM_SWITCH_PROBE.md"
    }
}
