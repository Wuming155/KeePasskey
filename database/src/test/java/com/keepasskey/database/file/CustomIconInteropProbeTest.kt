package com.keepasskey.database.file

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfParameters
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 自定义图标互操作对拍探针（`ISSUE-P2-283` AC③ / 规则 8）。
 *
 * 产出含**大体积自定义图标**（Base64 足以触发 76 列折行形态）的 `.kdbx` 落盘，
 * 供 `keepassxc-cli` 端到端打开。读侧「折行 Data」形态由
 * `KdbxCustomIconBase64LenientTest` 以官方 `.NET XmlWriter.WriteBase64` 同形样本锁定。
 */
class CustomIconInteropProbeTest {

    @Test
    fun `产出含自定义图标的对拍产物供官方客户端打开`() {
        val iconBytes = ByteArray(200) { (it * 7 + 3).toByte() }
        val icon = CustomIcon(
            uuid = KdbxUuid.random(),
            data = iconBytes,
            name = "P2-283 Probe Icon"
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false).copy(
                kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 0x5A }, rounds = 6_000L)
            ),
            databaseName = "CustomIconProbeVault",
            databaseDescription = "ISSUE-P2-283 custom icon interop probe",
            rootGroup = KdbxGroup(name = "Root"),
            customIcons = listOf(icon)
        )

        val password = PROBE_PASSWORD.toCharArray()
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)
        val fileBytes = bos.toByteArray()
        assertTrue("产物应包含头部与载荷", fileBytes.size > 200)

        // 自家往返：图标字节必须原样
        val loaded = KdbxFile.load(fileBytes.inputStream(), password, keyFileData = null)
        val loadedIcon = loaded.customIcons.single()
        assertTrue("图标字节必须与写入一致", loadedIcon.data.contentEquals(iconBytes))

        val outDir = File(System.getProperty("user.dir"), PROBE_DIR_NAME).apply { mkdirs() }
        val outFile = File(outDir, PROBE_FILE_NAME)
        outFile.writeBytes(fileBytes)
        val sha256Hex = HashUtil.sha256(fileBytes).joinToString("") { "%02x".format(it) }
        File(outDir, PROBE_NOTE_NAME).writeText(
            """
            # 自定义图标对拍探针（ISSUE-P2-283，由 CustomIconInteropProbeTest 生成）

            - 产物：`${outFile.absolutePath}`
            - 字节数：${fileBytes.size}
            - SHA-256：`$sha256Hex`
            - 口令：`$PROBE_PASSWORD`
            - 自定义图标：name=P2-283 Probe Icon，data=${iconBytes.size} 字节（Base64 约 ${(iconBytes.size + 2) / 3 * 4} 字符，会触发 76 列折行形态）
            - 期望：keepassxc-cli `db-info` / `export` 成功，export 的 `<Data>` 解出 ${iconBytes.size} 字节且与源字节一致

            ## 外用验证

            ```bash
            echo -n '$PROBE_PASSWORD' | keepassxc-cli db-info -q '${outFile.absolutePath}'
            echo -n '$PROBE_PASSWORD' | keepassxc-cli export -q -f xml '${outFile.absolutePath}'
            ```
            """.trimIndent()
        )
        assertTrue("产物必须留在磁盘上供外用对拍", outFile.isFile && outFile.length() > 200)
    }

    private companion object {
        const val PROBE_PASSWORD = "p2-283-icon-probe-2026"
        const val PROBE_DIR_NAME = "build/interop-probe"
        const val PROBE_FILE_NAME = "keepasskey-custom-icon-probe.kdbx"
        const val PROBE_NOTE_NAME = "CUSTOM_ICON_PROBE.md"
    }
}
