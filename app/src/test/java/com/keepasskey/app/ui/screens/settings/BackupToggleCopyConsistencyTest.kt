package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「保存前保留上一版备份」开关的**文案 ↔ 实现**一致性守卫（ISSUE-P3-107）。
 *
 * 缺陷背景：该开关的真实语义是[会话级]「每次成功写入前，把写入前的稳定版本另存为**同目录**的
 * `<库名>.kdbx.bak`（滚动保留一份，成功换密后删除）」——由 `DatabaseSession.createBackupBeforeSave`
 * 透传到 `AtomicFileWriter` 生效。而设置页文案曾写作「同步前自动备份 / 上传前将本地数据库备份至
 * **安全目录**」，描述的是一条**全仓不存在**的「同步前把库上传到安全目录」实现，
 * 且未告知保留期与换密后的删除行为。
 *
 * 本用例双向锁定：
 * - **实现侧**：开关必须下发到 `databaseSession.createBackupBeforeSave`（滚动备份偏好），
 *   而非任何「同步 / 上传」路径——这是文案所述行为真实存在的唯一证据；
 * - **文案侧**：中英两条必须点明 `.kdbx.bak` 与「换密后删除」，且**不得**再出现
 *   「安全目录 / 上传前」这类无实现支撑的措辞。
 */
class BackupToggleCopyConsistencyTest {

    @Test
    fun `开关必须下发到会话的滚动备份偏好`() {
        val code = stripComments(readSource(CONTROLLER_SOURCE))
        val body = functionBody(code, "fun setCreateBackupBeforeSave(")

        assertTrue(
            "[$CONTROLLER_SOURCE] 开关必须下发到 databaseSession.createBackupBeforeSave（滚动 .bak 偏好）",
            body.contains("databaseSession?.createBackupBeforeSave = enabled")
        )
        assertTrue(
            "[$CONTROLLER_SOURCE] 偏好必须持久化（否则重启后开关状态与行为脱节）",
            body.contains("updateExtended { it.copy(createBackupBeforeSave = enabled) }")
        )
    }

    @Test
    fun `备份开关文案必须描述滚动备份与换密删除`() {
        val zhSub = stringValue(readSource(ZH_STRINGS), "sync_backup_sub")
        val enSub = stringValue(readSource(EN_STRINGS), "sync_backup_sub")

        assertTrue("文案须点明产物文件 .kdbx.bak", zhSub.contains(".kdbx.bak"))
        assertTrue("文案须点明换密后删除（保留期终点）", zhSub.contains("更换主密码") && zhSub.contains("删除"))
        assertTrue("en 文案须点明产物文件 .kdbx.bak", enSub.contains(".kdbx.bak"))
        assertTrue(
            "en 文案须点明 password change 后删除",
            enSub.contains("password change") && enSub.contains("deleted")
        )
    }

    @Test
    fun `备份开关文案不得再描述不存在的同步前上传备份`() {
        val zh = stringValue(readSource(ZH_STRINGS), "sync_backup_sub") +
            stringValue(readSource(ZH_STRINGS), "sync_backup_title")
        val en = stringValue(readSource(EN_STRINGS), "sync_backup_sub") +
            stringValue(readSource(EN_STRINGS), "sync_backup_title")

        assertFalse(
            "中文文案不得宣称「备份至安全目录」——全仓无该实现（本开关只写同目录 .bak）",
            zh.contains("安全目录")
        )
        assertFalse(
            "中文文案不得宣称「上传前 / 同步前」备份——备份动作发生在**每次保存**，与同步无关",
            zh.contains("上传前") || zh.contains("同步前")
        )
        assertFalse(
            "en 文案不得宣称 safe folder",
            en.contains("safe folder", ignoreCase = true)
        )
        assertFalse(
            "en 文案不得宣称 before sync / before uploading",
            en.contains("before sync", ignoreCase = true) ||
                en.contains("before uploading", ignoreCase = true)
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    /** 提取 `<string name="…">…</string>` 的正文 */
    private fun stringValue(xml: String, name: String): String {
        val marker = "<string name=\"$name\">"
        val start = xml.indexOf(marker)
        assertTrue("未找到字符串资源：$name", start >= 0)
        val bodyStart = start + marker.length
        val end = xml.indexOf("</string>", bodyStart)
        assertTrue("字符串资源未闭合：$name", end >= 0)
        return xml.substring(bodyStart, end)
    }

    /**
     * 按花括号配对提取函数体（含函数体本身）。
     * 调用前须剔除注释，否则注释中的 `{` / `}` 会破坏配对。
     */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到函数：$signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("函数缺少函数体：$signature", open >= 0)

        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("函数体未闭合：$signature")
    }

    /** 剔除块注释与行注释——整改说明本身会写出被断言的字面量 */
    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private companion object {
        const val CONTROLLER_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt"
        const val ZH_STRINGS = "app/src/main/res/values/strings.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
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

        const val ROOT_SEARCH_DEPTH = 4
    }
}
