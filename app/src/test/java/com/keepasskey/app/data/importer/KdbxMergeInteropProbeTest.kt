package com.keepasskey.app.data.importer

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ISSUE-P3-384 AC④：`.kdbx` 并入结果的**文件级**探针（供 pykeepass / keepassxc-cli 外用对拍）。
 *
 * 本用例不依赖设备：JVM 内建库 → KdbxMerger 并入 → KdbxFile.save 落盘 → 产物留在
 * `build/interop-probe/`。人读说明与复现命令写入 `KDBX_MERGE_PROBE.md`。
 *
 * 外用验证（本机工具链已实测可用：pykeepass 4.2.0 / keepassxc-cli 2.7.12）：
 * ```
 * python tools/kdbx-merge-interop/verify_merge.py \
 *   app/build/interop-probe/keepasskey-kdbx-merge-probe.kdbx \
 *   merge-probe-password \
 *   --expect-entry merge-local \
 *   --expect-entry merge-remote-unique
 * ```
 */
class KdbxMergeInteropProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `合并产物落盘且含本地与对端独有条目`() {
        val password = "merge-probe-password".toCharArray()
        val localDb = buildVault(
            name = "local",
            password = password,
            entries = listOf("merge-local")
        )
        val remoteDb = buildVault(
            name = "remote",
            password = password,
            entries = listOf("merge-remote-unique")
        )
        try {
            val base = KdbxDatabaseLite(
                rootGroup = localDb.rootGroup.copy(entries = emptyList(), subgroups = emptyList())
            )
            val localLite = KdbxDatabaseLite(
                rootGroup = localDb.rootGroup,
                deletedObjects = localDb.deletedObjects,
                customIcons = localDb.customIcons
            )
            val remoteLite = KdbxDatabaseLite(
                rootGroup = remoteDb.rootGroup,
                deletedObjects = remoteDb.deletedObjects,
                customIcons = remoteDb.customIcons
            )
            val merged = KdbxMerger.mergeDatabases(base, localLite, remoteLite)
            val outDb = localDb.copy(
                rootGroup = merged.mergedRoot,
                deletedObjects = merged.mergedDeletedObjects,
                customIcons = merged.mergedCustomIcons
            )

            val probeDir = File("build/interop-probe").apply { mkdirs() }
            val probeFile = File(probeDir, PROBE_FILE_NAME)
            probeFile.outputStream().use { out ->
                KdbxFile.save(out, outDb, password.clone(), null)
            }

            val titles = outDb.rootGroup.allEntries().map { it.title }
            assertTrue("合并产物须含本地条目", titles.contains("merge-local"))
            assertTrue("合并产物须含对端独有条目", titles.contains("merge-remote-unique"))
            assertTrue("探针文件必须落盘", probeFile.exists() && probeFile.length() > 0)

            val sha = MessageDigest.getInstance("SHA-256")
                .digest(probeFile.readBytes())
                .joinToString("") { "%02x".format(it) }
            probeDir.resolve(PROBE_NOTE_NAME).writeText(
                """
                # ISSUE-P3-384 KDBX 并入互操作探针

                - 文件：`app/build/interop-probe/$PROBE_FILE_NAME`
                - 口令：`$password`（**虚构测试值**，勿用于生产）
                - SHA-256：`$sha`
                - 期望条目：`merge-local`、`merge-remote-unique`
                - 外用命令：

                ```
                python tools/kdbx-merge-interop/verify_merge.py \\
                  app/build/interop-probe/$PROBE_FILE_NAME \\
                  $password \\
                  --expect-entry merge-local \\
                  --expect-entry merge-remote-unique
                ```

                - keepassxc-cli：
                ```
                keepassxc-cli ls -R -q --stdout app/build/interop-probe/$PROBE_FILE_NAME
                ```
                （会提示输入口令）
                """.trimIndent() + "\n"
            )
        } finally {
            password.fill('0')
        }
    }

    private fun buildVault(
        name: String,
        password: CharArray,
        entries: List<String>
    ): KdbxDatabase {
        val rootId = KdbxUuid.random()
        val root = KdbxGroup(
            id = rootId,
            name = "Root",
            entries = entries.map { title ->
                KdbxEntry(
                    id = KdbxUuid.random(),
                    parentGroupId = rootId,
                    fields = mapOf(
                        KdbxConstants.Fields.TITLE to ProtectedString(title, false),
                        KdbxConstants.Fields.PASSWORD to ProtectedString("$name-pw", true)
                    ),
                    times = KdbxTimes(
                        creationTime = Instant.parse("2026-03-01T00:00:00Z"),
                        lastModificationTime = Instant.parse("2026-03-02T00:00:00Z")
                    )
                )
            }
        )
        val file = temp.newFile("$name.kdbx")
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            databaseName = name,
            rootGroup = root
        )
        file.outputStream().use { out -> KdbxFile.save(out, db, password.clone(), null) }
        return KdbxFile.load(file.inputStream(), password.clone(), null, null)
    }

    companion object {
        const val PROBE_FILE_NAME = "keepasskey-kdbx-merge-probe.kdbx"
        const val PROBE_NOTE_NAME = "KDBX_MERGE_PROBE.md"
    }
}
