package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.xml.KdbxXmlTimeHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 产物对拍探针（ISSUE-P0-05 / D1 互操作验收 AC④）。
 *
 * ## 为什么需要它
 *
 * 本仓既有的互操作用例（`RealKdbxInteroperabilityTest`、`KdbxXmlFullRoundtripTest`）都是
 * **自家 writer → 自家 reader**：D1（时间单位写成 .NET Ticks）之所以长期逃逸，正是因为
 * 自读自写恒为绿。本用例把真实产物**留在磁盘上**，供官方实现做端到端对拍。
 *
 * ## 产物
 *
 * - 路径：`<模块目录>/build/interop-probe/keepasskey-probe.kdbx`（`build/` 可丢弃、不入库）；
 * - 说明：`<同目录>/PROBE.md`（含 SHA-256、口令与外用验证命令，便于事后复现与留痕）；
 * - 口令：本类常量 [PROBE_PASSWORD]（仅测试用，非敏感）。
 *
 * ## 外用验证命令（本机工具链已实测可用：pykeepass 4.2.0 / keepassxc-cli 2.7.12）
 *
 * ```
 * echo -n '<PROBE_PASSWORD>' | keepassxc-cli db-info -q <产物路径>
 * python -c "from pykeepass import PyKeePass; kp=PyKeePass(r'<产物路径>', password='<PROBE_PASSWORD>'); \
 *   e=kp.find_entries(title='Probe Entry', first=True); print(e.ctime, e.mtime)"
 * ```
 *
 * 期望（D1 修复后）：两条命令均成功，且时间为**正常日期**（与 [probeTime] 同秒）。
 * D1 修复前：pykeepass 抛 `OverflowError`、KeePass 2.x 因 `new DateTime(lSec * 10^7)` 回绕而
 * 打开失败或显示荒谬日期、KeePassXC 可打开但时间为非法值。
 *
 * ## KDF 选择
 *
 * 探针刻意使用 **AES-KDF（6000 轮）** 而非默认 Argon2id：让外用工具的失败/成功只反映
 * **时间编码**这一被验对象，而不掺杂第三方工具对 Argon2 变体的支持差异（Argon2 路径的
 * 往返由本模块其他用例覆盖）。轮数取小值以保证外用工具解锁耗时可控。
 */
class OwnProductInteropProbeTest {

    private fun probeTime(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private fun probeHeader(): KdbxHeader =
        KdbxHeader.createDefault(useArgon2 = false).copy(
            kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 0x5A }, rounds = PROBE_AES_ROUNDS)
        )

    private fun probeDatabase(time: Instant): KdbxDatabase {
        val entry = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Probe Entry", isProtected = false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("probe-user", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("Probe-P@ssw0rd-2026", isProtected = true),
                KdbxConstants.Fields.URL to ProtectedString("https://example.com", isProtected = false),
                KdbxConstants.Fields.NOTES to ProtectedString("", isProtected = false)
            ),
            times = KdbxTimes(
                creationTime = time,
                lastModificationTime = time,
                lastAccessTime = time,
                expiryTime = time,
                usageCount = 7L
            ),
            tags = listOf("probe")
        )
        return KdbxDatabase(
            header = probeHeader(),
            databaseName = "InteropProbeVault",
            databaseDescription = "D1 interop probe artifact",
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
    }

    /**
     * 产出对拍产物 + 在进程内锁定 D1 的量级不变量。
     */
    @Test
    fun `产出可被官方客户端打开的对拍产物`() {
        val time = probeTime()
        val password = PROBE_PASSWORD.toCharArray()

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, probeDatabase(time), password)
        val fileBytes = bos.toByteArray()
        assertTrue("产物应包含头部与载荷", fileBytes.size > 200)

        // ── D1 进程内守卫：时间编码必须是「秒」量级（Ticks 会是它的 10^7 倍）─────────────
        val encodedSeconds = KdbxXmlTimeHelper.instantToDotNetSeconds(time)
        assertTrue(
            "D1 回归：时间编码量级异常（$encodedSeconds）——官方格式为自 0001-01-01 起的秒，" +
                "本仓 2026 年取值应 < 2^40（约 1.1e12），而 Ticks 编码约 6.4e17",
            encodedSeconds in 1L until (1L shl 40)
        )

        // ── 自家往返（必要但**不充分**的证据，真正的互操作证据由外用工具给出）─────────
        val loaded = KdbxFile.load(
            java.io.ByteArrayInputStream(fileBytes),
            password,
            keyFileData = null
        )
        val loadedEntry = loaded.rootGroup.entries.single()
        assertEquals("条目创建时间应按秒精度原样读回", time, loadedEntry.times.creationTime)
        assertEquals("条目修改时间应按秒精度原样读回", time, loadedEntry.times.lastModificationTime)
        assertEquals(7L, loadedEntry.times.usageCount)

        // ── 落盘留存供外用对拍 ────────────────────────────────────────────────────
        val outDir = File(System.getProperty("user.dir"), PROBE_DIR_NAME).apply { mkdirs() }
        val outFile = File(outDir, PROBE_FILE_NAME)
        outFile.writeBytes(fileBytes)

        val sha256Hex = HashUtil.sha256(fileBytes).joinToString("") { "%02x".format(it) }
        File(outDir, PROBE_NOTE_NAME).writeText(
            buildString {
                appendLine("# 对拍探针产物说明（由 OwnProductInteropProbeTest 生成，可随时重跑）")
                appendLine()
                appendLine("- 产物：`${outFile.absolutePath}`")
                appendLine("- 字节数：${fileBytes.size}")
                appendLine("- SHA-256：`$sha256Hex`")
                appendLine("- 口令：`$PROBE_PASSWORD`")
                appendLine("- 条目：Probe Entry / probe-user / https://example.com")
                appendLine("- 时间（自 0001-01-01 起秒数）：$encodedSeconds（应 < 2^40 = ${1L shl 40}）")
                appendLine("- 期望读回时刻（UTC）：$time")
                appendLine()
                appendLine("## 外用验证（官方实现端到端对拍）")
                appendLine()
                appendLine("```bash")
                appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli db-info -q '${outFile.absolutePath}'")
                appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli ls -q -R '${outFile.absolutePath}'")
                appendLine(
                    "python -c \"from pykeepass import PyKeePass; " +
                        "kp=PyKeePass(r'${outFile.absolutePath}', password='$PROBE_PASSWORD'); " +
                        "e=kp.find_entries(title='Probe Entry', first=True); print(e.ctime, e.mtime)\""
                )
                appendLine("```")
                appendLine()
                appendLine("D1 修复前：pykeepass 抛 OverflowError；KeePass 2.x 打开失败或显示荒谬日期。")
            }
        )
        assertTrue("产物必须留在磁盘上供外用对拍", outFile.isFile && outFile.length() > 200)
    }

    private companion object {
        const val PROBE_PASSWORD = "interop-probe-password-2026"
        const val PROBE_AES_ROUNDS = 6_000L
        const val PROBE_DIR_NAME = "build/interop-probe"
        const val PROBE_FILE_NAME = "keepasskey-probe.kdbx"
        const val PROBE_NOTE_NAME = "PROBE.md"
    }
}
