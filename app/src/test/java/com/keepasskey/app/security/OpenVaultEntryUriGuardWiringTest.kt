package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P3-575` 的**接线守卫**（源码文本断言，体例沿用 `RemoteVaultUnlockGuardWiringTest` /
 * `VaultPermissionNoticeWiringTest`）。
 *
 * 本条目的失效形态是「缺陷原样复发但全绿」：`OpenVaultEntryActivity` 的 scheme 校验被摘掉、
 * 登记前的用户确认被删、或 `VaultLifecycleCoordinator` 的相对路径守卫被移走——这三件事都不会让
 * 任何既有用例变红（界面不崩、行为无异常返回）。故按同一先例以源码钉住四组不可回退的约束：
 *
 * 1. **准入策略接线**：外部入口必须经 [VaultOpenUriPolicy] 判 scheme 与显示名，
 *    且不得复活「按 `uri.scheme == "file"` 取路径末段」的旧分支；
 * 2. **登记前确认**：`importExternalDatabase(` 必须出现在确认按钮回调**之后**
 *    （即登记只能由用户确认触发，不得由外部输入静默执行）；
 * 3. **解锁 fail-closed**：`VaultLifecycleCoordinator.unlockActiveDatabase` 必须在
 *    `File(activeDb.path)` 之前经 [VaultOpenUriPolicy.isUnlockableStoredPath] 拒绝 scheme 前缀 / 相对路径；
 * 4. **白名单理由与代码一致**：`ExportedComponentHygieneTest` 不得再声称「content/file scheme」。
 */
class OpenVaultEntryUriGuardWiringTest {

    private val activitySource: String
        get() = readSource(ACTIVITY)

    private val coordinatorSource: String
        get() = readSource(COORDINATOR)

    @Test
    fun `外部入口必须经准入策略判 scheme 与显示名`() {
        assertTrue(
            "[$ACTIVITY] 未接线 VaultOpenUriPolicy.acceptInboundUri（scheme 白名单被摘？）",
            activitySource.contains("VaultOpenUriPolicy.acceptInboundUri")
        )
        assertTrue(
            "[$ACTIVITY] 未接线 VaultOpenUriPolicy.acceptDisplayName（显示名形态校验被摘？）",
            activitySource.contains("VaultOpenUriPolicy.acceptDisplayName")
        )
    }

    @Test
    fun `不得复活按 file scheme 取路径末段的旧分支`() {
        assertFalse(
            "[$ACTIVITY] 又出现 uri.scheme == \"file\" 分支 —— file:// 已在准入时拒绝，此分支即缺陷原形态",
            activitySource.contains("uri.scheme == \"file\"")
        )
    }

    @Test
    fun `登记只能由用户确认触发（外部输入不得静默改状态）`() {
        assertTrue(
            "[$ACTIVITY] 缺少登记前确认对话框（AlertDialog）",
            activitySource.contains("AlertDialog")
        )
        assertTrue(
            "[$ACTIVITY] 缺少确认按钮（setPositiveButton）",
            activitySource.contains("setPositiveButton")
        )
        val confirmIdx = activitySource.indexOf("setPositiveButton")
        val importIdx = activitySource.indexOf("importExternalDatabase(")
        assertTrue("[$ACTIVITY] 未找到 importExternalDatabase 调用", importIdx > 0)
        assertTrue(
            "[$ACTIVITY] importExternalDatabase 必须出现在确认按钮之后 —— 否则登记又被静默执行",
            confirmIdx in 0 until importIdx
        )
        listOf(
            "R.string.external_open_confirm_title",
            "R.string.external_open_confirm_ok",
            "R.string.external_open_confirm_cancel"
        ).forEach { key ->
            assertTrue("[$ACTIVITY] 确认对话框未引用 $key", activitySource.contains(key))
        }
    }

    @Test
    fun `解锁前必须拒绝不可解锁的已登记路径形态`() {
        assertTrue(
            "[$COORDINATOR] 未接线 VaultOpenUriPolicy.isUnlockableStoredPath（解锁 fail-closed 守卫被摘？）",
            coordinatorSource.contains("VaultOpenUriPolicy.isUnlockableStoredPath")
        )
        val guardIdx = coordinatorSource.indexOf("VaultOpenUriPolicy.isUnlockableStoredPath")
        val fileIdx = coordinatorSource.indexOf("File(activeDb.path)")
        assertTrue("[$COORDINATOR] 未找到 File(activeDb.path)", fileIdx > 0)
        assertTrue(
            "[$COORDINATOR] 路径形态守卫必须在 File(activeDb.path) 之前（否则 file:/… 仍被相对解析）",
            guardIdx in 0 until fileIdx
        )
    }

    @Test
    fun `白名单理由不得再声称接受 content file scheme`() {
        val hygiene = readSource(HYGIENE_TEST)
        assertFalse(
            "[$HYGIENE_TEST] 白名单理由仍声称「content/file scheme」——与改后代码不符（ISSUE-P3-575 AC③）",
            hygiene.contains("content/file scheme")
        )
    }

    @Test
    fun `确认文案中英成对存在`() {
        val zh = readSource(ZH_STRINGS)
        val en = readSource(EN_STRINGS)
        listOf(
            "external_open_confirm_title",
            "external_open_confirm_message",
            "external_open_confirm_ok",
            "external_open_confirm_cancel"
        ).forEach { key ->
            assertTrue("中文文案缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文文案缺 $key", en.contains("name=\"$key\""))
        }
        assertTrue("中文文案缺 repo_unsupported_db_path", zh.contains("name=\"repo_unsupported_db_path\""))
        assertTrue("英文文案缺 repo_unsupported_db_path", en.contains("name=\"repo_unsupported_db_path\""))
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 4

        const val ACTIVITY = "app/src/main/java/com/keepasskey/app/OpenVaultEntryActivity.kt"
        const val COORDINATOR =
            "app/src/main/java/com/keepasskey/app/data/repository/VaultLifecycleCoordinator.kt"
        const val HYGIENE_TEST =
            "app/src/test/java/com/keepasskey/app/security/ExportedComponentHygieneTest.kt"
        const val ZH_STRINGS = "app/src/main/res/values/strings.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"

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
    }
}
