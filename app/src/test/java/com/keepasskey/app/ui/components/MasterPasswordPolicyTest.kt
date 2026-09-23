package com.keepasskey.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P2-288` AC⑤：主口令门槛三态（短口令被拒 / 弱口令需确认 / 达门槛直通）回归，
 * 兼锁定门槛文案中英成对与调用点接同一判据（禁两处各写一份）。
 *
 * 判据与阈值登记：`PD-38`（长度 < 8 阻断、强度 < 40 bits 显式二次确认、评估缺失不按弱）。
 */
class MasterPasswordPolicyTest {

    @Test
    fun `三态门槛：短口令阻断、弱口令二次确认、达门槛直通（AC⑤）`() {
        // 短口令：无论强度如何一律阻断（含强度缺失）
        assertEquals(
            MasterPasswordPolicy.Verdict.TOO_SHORT,
            MasterPasswordPolicy.verdictOf(passwordLength = 1, strengthBits = null)
        )
        assertEquals(
            MasterPasswordPolicy.Verdict.TOO_SHORT,
            MasterPasswordPolicy.verdictOf(passwordLength = 7, strengthBits = 128)
        )
        // 弱口令：达长度但低于强度下限 ⇒ 显式二次确认
        assertEquals(
            MasterPasswordPolicy.Verdict.WEAK_REQUIRES_CONFIRM,
            MasterPasswordPolicy.verdictOf(passwordLength = 8, strengthBits = 12)
        )
        assertEquals(
            MasterPasswordPolicy.Verdict.WEAK_REQUIRES_CONFIRM,
            MasterPasswordPolicy.verdictOf(passwordLength = 16, strengthBits = 39)
        )
        // 边界即达门槛：恰 40 bits 不按弱
        assertEquals(
            MasterPasswordPolicy.Verdict.OK,
            MasterPasswordPolicy.verdictOf(passwordLength = 8, strengthBits = 40)
        )
        assertEquals(
            MasterPasswordPolicy.Verdict.OK,
            MasterPasswordPolicy.verdictOf(passwordLength = 12, strengthBits = 96)
        )
        // 评估不可用（null）不按弱处理（如实不谎报）
        assertEquals(
            MasterPasswordPolicy.Verdict.OK,
            MasterPasswordPolicy.verdictOf(passwordLength = 8, strengthBits = null)
        )
    }

    @Test
    fun `两处调用点接同一判据且门槛文案中英成对（AC①／AC⑤）`() {
        val wizard = readSource("app/src/main/java/com/keepasskey/app/ui/screens/database/CreateVaultWizardDialog.kt")
        val masterChange = readSource("app/src/main/java/com/keepasskey/app/ui/screens/settings/MasterKeyChangeDialog.kt")
        listOf(wizard to "建库向导", masterChange to "改密对话框").forEach { (source, name) ->
            assertTrue(
                "$name 必须接 MasterPasswordPolicy 单一判据（禁各写一份）",
                source.contains("MasterPasswordPolicy.verdictOf") ||
                    source.contains("MasterPasswordPolicy.MIN_LENGTH")
            )
            assertTrue(
                "$name 必须接弱口令二次确认对话框（AC②）",
                source.contains("MasterPasswordWeakConfirmDialog")
            )
        }

        val zh = readSource("app/src/main/res/values/strings.xml")
        val en = readSource("app/src/main/res/values-en/strings.xml")
        listOf("master_pwd_too_short", "master_pwd_weak_title", "master_pwd_weak_body", "master_pwd_weak_confirm")
            .forEach { key ->
                assertTrue("中文文案缺 $key", zh.contains("name=\"$key\""))
                assertTrue("英文文案缺 $key", en.contains("name=\"$key\""))
            }
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
