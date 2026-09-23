package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 标签互操作对拍探针（`ISSUE-P2-282` AC⑤ / 规则 8）。
 *
 * 产出含三个标签（含一个需归一化的「`b, x`」输入，验证写侧归一化）的 `.kdbx` 落盘，
 * 供 `keepassxc-cli` / `pykeepass` 端到端打开——官方实现读出的标签数与内容必须与本仓一致。
 */
class KdbxTagsInteropProbeTest {

    @Test
    fun `产出含标签的对拍产物供官方客户端打开`() {
        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("TagsProbeEntry", false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("probe", false)
            ),
            // 「b, x」含逗号——写侧归一化应落为「b. x」（官方 NormalizeTag：
            // 仅分隔符替换为点，空格保留）
            tags = listOf("beta", "alpha", "b, x")
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false).copy(
                kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 0x5A }, rounds = 6_000L)
            ),
            databaseName = "TagsProbeVault",
            databaseDescription = "ISSUE-P2-282 tags interop probe",
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )

        val password = PROBE_PASSWORD.toCharArray()
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        val fileBytes = bos.toByteArray()
        assertTrue("产物应包含头部与载荷", fileBytes.size > 200)

        // 自家往返：归一化 + 排序后恰为 [alpha, b. x, beta]
        val loaded = KdbxFile.load(fileBytes.inputStream(), password, keyFileData = null)
        assertEquals(
            listOf("alpha", "b. x", "beta"),
            loaded.rootGroup.allEntries().single().tags
        )

        val outDir = File(System.getProperty("user.dir"), PROBE_DIR_NAME).apply { mkdirs() }
        val outFile = File(outDir, PROBE_FILE_NAME)
        outFile.writeBytes(fileBytes)
        val sha256Hex = HashUtil.sha256(fileBytes).joinToString("") { "%02x".format(it) }
        File(outDir, PROBE_NOTE_NAME).writeText(
            """
            # 标签对拍探针（ISSUE-P2-282，由 KdbxTagsInteropProbeTest 生成）

            - 产物：`${outFile.absolutePath}`
            - 字节数：${fileBytes.size}
            - SHA-256：`$sha256Hex`
            - 口令：`$PROBE_PASSWORD`
            - 标签：写入输入 `["beta", "alpha", "b, x"]` ⇒ 期望官方客户端读出 `["alpha", "b. x", "beta"]`
              （归一化「逗号替换为点、空格保留」+ 自然排序 + 裸 `;` 存储形态）
            - 期望：`keepassxc-cli export` 的 `<Tags>` 为 `alpha;b. x;beta`；pykeepass 读出 3 个标签

            ## 外用验证

            ```bash
            echo -n '$PROBE_PASSWORD' | keepassxc-cli export -q -f xml '${outFile.absolutePath}'
            ```
            """.trimIndent()
        )
        assertTrue("产物必须留在磁盘上供外用对拍", outFile.isFile && outFile.length() > 200)
    }

    private companion object {
        const val PROBE_PASSWORD = "p2-282-tags-probe-2026"
        const val PROBE_DIR_NAME = "build/interop-probe"
        const val PROBE_FILE_NAME = "keepasskey-tags-probe.kdbx"
        const val PROBE_NOTE_NAME = "TAGS_PROBE.md"
    }
}
