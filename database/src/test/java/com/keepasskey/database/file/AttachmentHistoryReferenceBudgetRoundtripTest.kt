package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 「中等尺寸内联附件 + 若干历史快照」的库在 `:database:` 层**保存 → 重开解锁**往返
 * （ISSUE-P1-276 AC④；证据纪律见 `AGENTS.md` 规则 8）。
 *
 * ## 为什么必须有这一例
 *
 * ISSUE-P1-276 的后果不是降级而是**整库打不开**：附件物化预算的旧口径按
 * `Σᵢ nᵢ·sᵢ > 2·Σⱼ sⱼ + 1 MiB` 计费，而本形态恰好落在其受害区间 `(512 KiB, 1 MiB]`——
 * 一次 1 MiB 附件挂在主条目 + 3 条历史快照（同一池条目被引 4 次）即
 * `4 MiB > 2 × 1 MiB + 1 MiB = 3 MiB` ⇒ 解析期抛 `KdbxCorruptFileException`，
 * 只读通道与修复通道同走 `parse`，**本仓自产库被自己的解析器判为损坏**。
 * 断言落在「解锁成功」而非「算式不越界」上：AC⑥ 要求触发条件以**构造文件实测**验证，
 * 不允许只做算式推演。
 *
 * ## 形态的可验证性
 *
 * 1 MiB 恰为 [com.keepasskey.core.security.BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES]，
 * 而落盘判定是**严格大于** ⇒ 该附件仍内联、仍被引用记账，正好压住受害窗口的**上界端点**；
 * 保存侧 [KdbxBinaryDeduplicator] 按 `(flags, size, 内容哈希)` 池化，故主条目与 3 条历史的
 * 同一附件**共用一个池索引** ⇒ 重开时 `nᵢ = 4`（这正是被旧口径误判的放大形态）。
 *
 * ## 产物（`build/` 可丢弃、不入库）
 *
 * - 库文件：`build/attachment-budget-probe/keepasskey-medium-attachment-history.kdbx`
 * - 清单：同目录 `...expected.json`；人读说明：同目录 `ATTACHMENT_BUDGET_PROBE.md`
 * - 口令：本类常量 [PROBE_PASSWORD]（仅测试用，非敏感）
 *
 * ```bash
 * echo -n '<口令>' | keepassxc-cli db-info -q '<产物路径>'                       # 官方 CLI 可打开
 * echo -n '<口令>' | keepassxc-cli attachment-export -q '<产物路径>' '<条目>' photo.jpg out.jpg
 * python -c "from pykeepass import PyKeePass; ..."                                # 独立实现读数（见 PROBE.md）
 * ```
 */
class AttachmentHistoryReferenceBudgetRoundtripTest {

    private fun probeHeader(): KdbxHeader =
        KdbxHeader.createDefault(useArgon2 = false).copy(
            kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 0x3C }, rounds = PROBE_AES_ROUNDS)
        )

    /** 主条目 + 3 条历史快照，每个快照都挂着**同一份** 1 MiB 附件（受害窗口的上界端点）。 */
    private fun sampleDatabase(content: ByteArray): KdbxDatabase {
        val entryId = KdbxUuid.random()
        val attachment = { KdbxAttachment(name = ATTACHMENT_NAME, data = content.copyOf()) }
        fun snapshot(version: Int) = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("$ENTRY_TITLE v$version", isProtected = false)
            ),
            attachments = listOf(attachment())
        )
        return KdbxDatabase(
            header = probeHeader(),
            databaseName = "AttachmentBudgetProbe",
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    snapshot(4).copy(history = listOf(snapshot(1), snapshot(2), snapshot(3)))
                )
            )
        )
    }

    @Test
    fun `一兆附件带三条历史的库保存后可解锁且四个引用共享同一池条目`() {
        val content = ByteArray(ATTACHMENT_BYTES) { (it % 251).toByte() }
        val password = PROBE_PASSWORD.toCharArray()

        val fileBytes = ByteArrayOutputStream()
            .also { KdbxFile.save(it, sampleDatabase(content), password) }
            .toByteArray()

        // 整改前本行即抛「疑似引用放大攻击」，整库打不开（只读 / 修复通道同走 parse）
        val loaded = KdbxFile.load(ByteArrayInputStream(fileBytes), password)

        // 受害形态：4 个引用者（主条目 + 3 条历史）共享**同一**池条目
        assertEquals("1 MiB 附件应内联入池（落盘判定为严格大于 1 MiB）", 1, loaded.binaries.size)
        assertEquals(ATTACHMENT_BYTES.toLong(), loaded.binaries.single().size)

        val entry = loaded.rootGroup.entries.single()
        assertEquals(3, entry.history.size)
        val attachmentHolders = listOf(entry) + entry.history
        attachmentHolders.forEach { holder ->
            val att = holder.attachments.single()
            assertEquals("主条目与历史快照必须都引用同一池索引", 0, att.refIndex)
            assertArrayEquals("重开后附件字节必须逐字节一致", content, att.data)
        }

        // ── 落盘留存供官方实现对拍（AC⑥ / 规则 8） ───────────────────────────────────────────
        val outDir = File(System.getProperty("user.dir"), PROBE_DIR_NAME).apply { mkdirs() }
        val outFile = File(outDir, PROBE_FILE_NAME)
        outFile.writeBytes(fileBytes)
        val contentSha = content.sha256Hex()
        File(outDir, PROBE_MANIFEST_NAME).writeText(buildManifest(fileBytes, contentSha))
        File(outDir, PROBE_NOTE_NAME).writeText(buildNote(outFile, fileBytes.size, contentSha))
        assertTrue("产物必须留在磁盘上供外用对拍", outFile.isFile && outFile.length() > 200)
    }

    /** 二次保存仍可解锁（载荷重写后引用与池的对应关系不漂移）。 */
    @Test
    fun `二次保存往返后引用形态与字节均保持`() {
        val content = ByteArray(ATTACHMENT_BYTES) { (it % 97).toByte() }
        val password = PROBE_PASSWORD.toCharArray()

        val first = ByteArrayOutputStream()
            .also { KdbxFile.save(it, sampleDatabase(content), password) }
            .toByteArray()
        val loaded = KdbxFile.load(ByteArrayInputStream(first), password)
        val second = ByteArrayOutputStream().also { KdbxFile.save(it, loaded, password) }.toByteArray()
        val reloaded = KdbxFile.load(ByteArrayInputStream(second), password)

        assertEquals(1, reloaded.binaries.size)
        val entry = reloaded.rootGroup.entries.single()
        assertEquals(3, entry.history.size)
        (listOf(entry) + entry.history).forEach { holder ->
            assertArrayEquals(content, holder.attachments.single().data)
        }
    }

    private fun ByteArray.sha256Hex(): String =
        HashUtil.sha256(this).joinToString("") { "%02x".format(it) }

    private fun buildManifest(fileBytes: ByteArray, contentSha: String): String = buildString {
        appendLine("{")
        appendLine("  \"database\": \"$PROBE_FILE_NAME\",")
        appendLine("  \"password\": \"$PROBE_PASSWORD\",")
        appendLine("  \"sha256\": \"${fileBytes.sha256Hex()}\",")
        appendLine("  \"entryTitle\": \"$ENTRY_TITLE\",")
        appendLine("  \"attachmentName\": \"$ATTACHMENT_NAME\",")
        appendLine("  \"attachmentBytes\": $ATTACHMENT_BYTES,")
        appendLine("  \"attachmentSha256\": \"$contentSha\",")
        appendLine("  \"poolItems\": 1,")
        appendLine("  \"referencesToItem0\": 4,")
        appendLine("  \"historySnapshots\": 3")
        appendLine("}")
    }

    private fun buildNote(outFile: File, fileSize: Int, contentSha: String): String = buildString {
        appendLine("# ISSUE-P1-276 附件引用预算探针（自产产物）")
        appendLine()
        appendLine("> 由 `AttachmentHistoryReferenceBudgetRoundtripTest` 生成，**请勿手工编辑**。")
        appendLine()
        appendLine("- 库文件：`${outFile.name}`（$fileSize B）")
        appendLine("- 口令：`$PROBE_PASSWORD`")
        appendLine("- KDF：AES-KDF $PROBE_AES_ROUNDS 轮（刻意避开 Argon2 变体差异，便于第三方工具解锁）")
        appendLine("- 附件：`$ATTACHMENT_NAME`，$ATTACHMENT_BYTES B，SHA-256 `$contentSha`")
        appendLine("- 形态：主条目（title `$ENTRY_TITLE v4`）+ 3 条历史快照，4 个引用共享**同一池条目**")
        appendLine()
        appendLine("## 官方 / 第三方实现读数")
        appendLine()
        appendLine("```bash")
        appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli db-info -q '${outFile.absolutePath}'")
        appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli ls -R -q '${outFile.absolutePath}'")
        appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli attachment-export -q '${outFile.absolutePath}' '$ENTRY_TITLE' $ATTACHMENT_NAME out.bin")
        appendLine("```")
    }

    private companion object {
        const val PROBE_DIR_NAME = "build/attachment-budget-probe"
        const val PROBE_FILE_NAME = "keepasskey-medium-attachment-history.kdbx"
        const val PROBE_MANIFEST_NAME = "keepasskey-medium-attachment-history.expected.json"
        const val PROBE_NOTE_NAME = "ATTACHMENT_BUDGET_PROBE.md"
        const val PROBE_PASSWORD = "attachment-budget-probe-2026"
        const val PROBE_AES_ROUNDS = 6_000L

        /** 恰为内联 / 落盘阈值（`BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES`）：仍内联、仍计费。 */
        const val ATTACHMENT_BYTES = 1 * 1024 * 1024
        const val ENTRY_TITLE = "Medium Photo"
        const val ATTACHMENT_NAME = "photo.jpg"
    }
}
