package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.database.session.DatabaseSession
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-469` 写侧的**文件级**探针（供 pykeepass / keepassxc-cli 外用对拍）。
 *
 * ## 锁定的缺陷
 *
 * `VaultTemplateFactory` 建的是硬编码「模板」分组，但 Meta `EntryTemplatesGroup` 从不写 ——
 * 该字段是官方 KeePass / KeePassDX / KeePassXC 识别模板组的**唯一依据**，故本仓写的库三家均
 * 不识别其模板组（反向亦然）。本用例走**真实安装链路**（`VaultExportCoordinator.installEntryTemplates`）
 * 落盘，产物留在 `build/interop-probe/` 供官方实现外用核验（自家 reader 读回属必要但不充分证据）。
 *
 * ## 外用验证命令（本机工具链已实测可用：pykeepass 4.2.0 / keepassxc-cli 2.7.12）
 *
 * ```
 * python tools/template-group-interop/verify_template_group.py \
 *   app/build/interop-probe/keepasskey-template-group-probe.kdbx \
 *   template-group-probe-password-2026
 * ```
 */
class TemplateGroupInteropProbeTest {

    private fun coordinator(session: DatabaseSession) = VaultExportCoordinator(
        strings = StringsProvider { _, _ -> "" },
        databaseSession = session,
        persistSession = { session.save() }
    )

    private fun createSession(file: File, password: CharArray): DatabaseSession {
        val session = DatabaseSession()
        runBlocking {
            assertTrue(session.create(file, "template-group-probe", password) is KdbxResult.Success)
        }
        return session
    }

    @Test
    fun `安装模板后 Meta EntryTemplatesGroup 指向模板组且重开文件保持`() = runBlocking {
        val dir = Files.createTempDirectory("template_group_meta").toFile()
        val file = File(dir, "template-meta.kdbx")
        val password = "template-group-probe-password-2026".toCharArray()

        val session = createSession(file, password)
        assertTrue(
            "安装必须成功",
            coordinator(session).installEntryTemplates() is KdbxResult.Success
        )

        val db = session.databaseFlow.value!!
        val templateGroup = db.rootGroup.subgroups.firstOrNull {
            it.name == VaultTemplateFactory.TEMPLATE_GROUP_NAME
        }
        assertNotNull("必须创建「模板」分组", templateGroup)
        assertEquals(
            "Meta EntryTemplatesGroup 必须等于模板组 UUID",
            templateGroup!!.id,
            db.entryTemplatesGroup
        )
        assertNotNull("变更时间戳须一并写入", db.entryTemplatesGroupChanged)

        // 幂等：再次安装须失败（已登记），且 Meta 不被改动
        assertTrue(
            "重复安装须失败",
            coordinator(session).installEntryTemplates() is KdbxResult.Failure
        )
        assertEquals(templateGroup.id, session.databaseFlow.value!!.entryTemplatesGroup)
        session.close()

        // 自家往返（必要但不充分）：重开文件后 Meta 仍指向模板组
        val reopen = DatabaseSession()
        assertTrue(reopen.open(file, password) is KdbxResult.Success)
        assertEquals(
            "重开文件后 EntryTemplatesGroup 必须仍指向模板组",
            templateGroup.id,
            reopen.databaseFlow.value!!.entryTemplatesGroup
        )
        runBlocking { reopen.close() }
    }

    @Test
    fun `存量库已有模板组但 Meta 未登记时回填而非重复建组`() = runBlocking {
        val dir = Files.createTempDirectory("template_group_backfill").toFile()
        val file = File(dir, "template-backfill.kdbx")
        val password = "template-group-probe-password-2026".toCharArray()

        val session = createSession(file, password)
        // 模拟存量库：直接落一个「模板」组（老版本安装路径的产物），Meta 尚未登记
        session.saveGroup(VaultTemplateFactory.buildTemplateGroup())
        session.save()
        val preExistingId = session.databaseFlow.value!!.rootGroup.subgroups
            .first { it.name == VaultTemplateFactory.TEMPLATE_GROUP_NAME }.id
        assertNull("前置：Meta 尚未登记", session.databaseFlow.value!!.entryTemplatesGroup)

        assertTrue(
            "Meta 未登记时应回填并视为安装完成",
            coordinator(session).installEntryTemplates() is KdbxResult.Success
        )

        val db = session.databaseFlow.value!!
        assertEquals("Meta 必须回填到既有模板组", preExistingId, db.entryTemplatesGroup)
        assertEquals(
            "不得重复建组",
            1,
            db.rootGroup.subgroups.count { it.name == VaultTemplateFactory.TEMPLATE_GROUP_NAME }
        )
        session.close()
    }

    @Test
    fun `产出模板组互操作对拍产物`() {
        val dir = Files.createTempDirectory("template_group_probe").toFile()
        val file = File(dir, "template-group-probe.kdbx")
        val password = PROBE_PASSWORD.toCharArray()

        val session = createSession(file, password)
        assertTrue(
            "安装必须成功",
            runBlocking { coordinator(session).installEntryTemplates() } is KdbxResult.Success
        )
        val templateGroupId = session.databaseFlow.value!!.entryTemplatesGroup
        assertNotNull("安装后 Meta 必须已登记模板组", templateGroupId)
        runBlocking { session.close() }

        // ── 落盘留存供外用对拍 ────────────────────────────────────────────
        val fileBytes = file.readBytes()
        val outDir = File(System.getProperty("user.dir"), PROBE_DIR_NAME).apply { mkdirs() }
        val outFile = File(outDir, PROBE_FILE_NAME)
        outFile.writeBytes(fileBytes)
        val sha256Hex = HashUtil.sha256(fileBytes).joinToString("") { "%02x".format(it) }
        File(outDir, PROBE_NOTE_NAME).writeText(
            buildString {
                appendLine("# 对拍探针产物说明（由 TemplateGroupInteropProbeTest 生成，可随时重跑）")
                appendLine()
                appendLine("- 产物：`${outFile.absolutePath}`")
                appendLine("- 字节数：${fileBytes.size}")
                appendLine("- SHA-256：`$sha256Hex`")
                appendLine("- 口令：`$PROBE_PASSWORD`")
                appendLine("- Meta 状态：`EntryTemplatesGroup = ${templateGroupId!!.toHexString()}`（真实安装链路写入）")
                appendLine()
                appendLine("## 外用验证（官方实现端到端对拍）")
                appendLine()
                appendLine("```bash")
                appendLine("python tools/template-group-interop/verify_template_group.py '${outFile.absolutePath}' '$PROBE_PASSWORD'")
                appendLine("echo -n '$PROBE_PASSWORD' | keepassxc-cli export -f xml '${outFile.absolutePath}'")
                appendLine("```")
                appendLine()
                appendLine("期望：官方 CLI 正常导出，XML `<Meta><EntryTemplatesGroup>` 等于「模板」分组 UUID。")
            }
        )
        assertTrue("产物必须留在磁盘上供外用对拍", outFile.isFile && outFile.length() > 200)
    }

    private companion object {
        const val PROBE_PASSWORD = "template-group-probe-password-2026"
        const val PROBE_DIR_NAME = "build/interop-probe"
        const val PROBE_FILE_NAME = "keepasskey-template-group-probe.kdbx"
        const val PROBE_NOTE_NAME = "TEMPLATE_GROUP_PROBE.md"
    }
}
