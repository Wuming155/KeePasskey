package com.keepasskey.app.ui

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「一次点击即可完成」整改批次的**接线守护**（静态源码比对，沿用 `UiMd3AlignmentWiringTest` /
 * `ColdStartAttachmentPurgeWiringTest` 的同源先例）。
 *
 * 本批六项整改的失效形态都是「**被后人静默改回**」，而它们在宿主 JVM 上无法靠渲染断言：
 * 1. 详情页 TOTP「复制」退回只弹提示不写剪贴板（谎报成功，用户粘贴贴出上一条目内容）；
 * 2. 列表行验证码徽标退回纯展示（复制验证码得进详情页点三次）；
 * 3. 安全设置三处单值设置退回「可点行 → 弹窗 → 选中」两次点击 + 一次模态打断；
 * 4. 泄露检测开关退回「只写偏好不扫描」（用户须滚回页面顶部再点重新扫描）；
 * 5. WebDAV / S3 退回「保存 → 测试连接 → 立即同步」三次点击；
 * 6. 系统设置排障提示退回纯文案（用户须自行走 4~5 层系统设置）。
 *
 * 另含**防空扫断言**（§77 纪律）：扫描器一旦因路径漂移 / 正则失效而扫不到东西，
 * 用例必须失败而不是「零命中即通过」。
 */
class OneTapInteractionWiringTest {

    // ---------------------------------------------------------------- 甲a · ISSUE-P3-184

    @Test
    fun `详情页 TOTP 复制必须走真实复制通道而非只弹提示`() {
        val cards = stripComments(readSource(DETAIL_CARDS))
        val viewModel = stripComments(readSource(DETAIL_VIEW_MODEL))
        val copyCoordinator = stripComments(readSource(DETAIL_COPY_COORDINATOR))
        val coordinatorBody = functionBody(copyCoordinator, "fun copyTotpCode()")

        assertTrue(
            "TOTP 复制按钮必须直接调用复制回调（onClick = onCopyTotp），实际未接线",
            cards.contains("IconButton(onClick = onCopyTotp)")
        )
        assertFalse(
            "历史缺陷形态不得回归：复制按钮只弹「已复制」提示而不写剪贴板",
            cards.contains("onShowMessage(UiMessage(R.string.detail_totp_copied))")
        )
        assertTrue(
            "ViewModel 门面必须委托复制协作者（不得在门面上退回只弹提示）",
            functionBody(viewModel, "fun copyTotpCode()").contains("copyCoordinator.copyTotpCode()")
        )
        assertTrue(
            "copyTotpCode 必须经受保护剪贴板写入（copySensitiveText）",
            copyCoordinator.contains("copySensitiveText") && coordinatorBody.contains("copySensitiveText")
        )
        assertTrue(
            "取不到码时必须发失败文案而非成功文案（不谎报成功）",
            coordinatorBody.contains("R.string.detail_totp_copy_failed")
        )
    }

    // ---------------------------------------------------------------- 甲b

    @Test
    fun `列表行验证码徽标可点复制且 HOTP 被排除`() {
        val layouts = stripComments(readSource(VAULT_ROW_LAYOUTS))
        val controller = stripComments(readSource(VAULT_ACTION_CONTROLLER))
        val screen = stripComments(readSource(VAULT_LIST_SCREEN))

        assertTrue(
            "徽标必须挂 clickable（否则复制验证码仍须进详情页）",
            layouts.contains("clickable(")
        )
        assertTrue(
            "HOTP 不得可点复制（复制而不推进会复用计数器）",
            layouts.contains("if (entry.isHotp) null else onCopyTotpCode")
        )
        assertTrue(
            "控制器侧必须有第二道 HOTP 防线",
            functionBody(controller, "fun copyTotpCode(entry: UiVaultEntry)").contains("if (entry.isHotp) return")
        )
        assertTrue(
            "列表页必须把徽标点击接到 ViewModel 的复制入口",
            screen.contains("onCopyTotpCode = { onCopyTotp(entry) }")
        )
    }

    // ---------------------------------------------------------------- 乙

    @Test
    fun `三处单值设置已就地化且选择弹窗不得回归`() {
        val screen = stripComments(readSource(SECURITY_SCREEN))
        val dialogs = stripComments(readSource(SECURITY_DIALOGS))
        val components = stripComments(readSource(SECURITY_COMPONENTS))

        assertFalse(
            "旧形态（可点行 → 弹窗）不得回归",
            screen.contains("SecurityClickableRow(")
        )
        assertEquals(
            "三处单值设置（自动锁定超时 / 最长锁定时长 / 剪贴板清空倒计时）都应走就地选择控件",
            3,
            Regex("SecurityChoiceChips\\(").findAll(screen).count()
        )
        listOf("AutoLockTimeoutDialog", "ClipboardTimeoutDialog", "LockoutMaxDurationDialog").forEach { gone ->
            assertFalse(
                "已被就地选择取代的选择弹窗不得回归：$gone",
                dialogs.contains(gone) || screen.contains(gone)
            )
        }
        assertTrue(
            "选项表与控件必须同源（FlowRow 承载 5~7 档，分段控件会溢出）",
            components.contains("FlowRow(") &&
                components.contains("AUTO_LOCK_TIMEOUT_CHOICES") &&
                components.contains("CLIPBOARD_TIMEOUT_CHOICES") &&
                components.contains("LOCKOUT_MAX_DURATION_CHOICES")
        )
        assertTrue(
            "高危动作的二次确认不得被一并就地化（FLAG_SECURE 风险弹窗必须保留）",
            dialogs.contains("FlagSecureRiskDialog")
        )
    }

    // ---------------------------------------------------------------- 丙

    @Test
    fun `泄露检测开关开启方向就地触发扫描且互斥守卫不得移除`() {
        val navGraph = stripComments(readSource(SETTINGS_NAV_GRAPH))
        val viewModel = stripComments(readSource(SETTINGS_VIEW_MODEL))
        val healthController = stripComments(readSource(HEALTH_CONTROLLER))

        assertTrue(
            "导航层必须在开启分支调用「开启并扫描」而非只写偏好",
            navGraph.contains("enableBreachCheckAndScan")
        )
        // ISSUE-P3-257：实现体已下沉至健康控制器，ViewModel 侧只准保留单语句委托
        assertTrue(
            "ViewModel 必须收为单语句委托（ISSUE-P3-257 不得新增含实现体的成员）",
            viewModel.contains("fun enableBreachCheckAndScan() = healthController.enableBreachCheckAndScan()")
        )
        val body = functionBody(healthController, "fun enableBreachCheckAndScan()")
        val persistAt = body.indexOf("setBreachCheckEnabled(true)")
        assertTrue("必须先写偏好（持久化回调缺失即红）", persistAt >= 0)
        assertTrue(
            "必须先写偏好再扫描（顺序不可倒置）",
            persistAt < body.indexOf("rescanHealth()")
        )
        assertTrue("必须触发扫描", body.contains("rescanHealth()"))
        assertTrue(
            "扫描互斥守卫（isHealthScanning 早退）不得移除——自动触发路径依赖它防并发",
            healthController.contains("if (healthStateFlow.value.isHealthScanning) return")
        )
    }

    // ---------------------------------------------------------------- 丁

    @Test
    fun `保存并同步先保存后同步且同步不使用表单实参`() {
        val screen = stripComments(readSource(CLOUD_SYNC_SCREEN))
        val controller = stripComments(readSource(SYNC_CONTROLLER))
        val viewModel = stripComments(readSource(SETTINGS_VIEW_MODEL))

        assertTrue(
            "同步必须在保存成功之后才触发（保存失败不得进入测试/同步）",
            screen.contains("if (persistConfig()) onSyncAfterSave()")
        )
        assertTrue(
            "两条入口必须共用同一份保存逻辑（避免凭据数组清空口径分叉）",
            Regex("persistConfig").findAll(screen).count() >= 3
        )
        assertTrue(
            "连接测试必须提供完成回调，顺序编排才能收在状态层",
            controller.contains("fun testSyncConnection(onResult: ((Boolean) -> Unit)? = null)")
        )
        val testConnection = functionBody(controller, "fun testSyncConnection(")
        assertTrue(
            "回调必须在状态写入（isSyncing=false）之后触发，否则会撞上 triggerSync 的忙态早退",
            testConnection.indexOf("onResult?.invoke(verified)") >
                testConnection.indexOf("isSyncing = false")
        )
        // 关键约束：顺序编排的入口不接收任何表单实参 ⇒ 同步只可能走**已保存**的配置
        assertTrue(
            "编排入口不得接收表单实参（凭据 CharArray 已在保存时消费擦除）",
            viewModel.contains("fun verifyConnectionThenSync()")
        )
        // ISSUE-P3-257：实现体已下沉至同步控制器，ViewModel 侧只准保留单语句委托
        assertTrue(
            "ViewModel 必须收为单语句委托（ISSUE-P3-257 不得新增含实现体的成员）",
            viewModel.contains("fun verifyConnectionThenSync() = syncController.verifyConnectionThenSync()")
        )
        assertTrue(
            "守卫语义必须保留：未验证连接时不得直接同步（实现体在 [SettingsSyncController]）",
            functionBody(controller, "fun verifyConnectionThenSync()").contains("isConnectionVerified")
        )
    }

    // ---------------------------------------------------------------- 戊

    @Test
    fun `系统设置排障入口必须一次点击直达且可降级`() {
        val nav = stripComments(readSource(SYSTEM_SETTINGS_NAV))
        val healthCard = stripComments(readSource(AUTOFILL_HEALTH_CARD))
        // ISSUE-P3-215：无障碍提示自解锁页迁至设置页安全分区，判据随之改指该承载文件
        val accessibilityCard = stripComments(readSource(SECURITY_COMPONENTS))

        assertTrue(
            "自动填充服务页必须用官方 action（不得臆测未公开 extra）",
            nav.contains("Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE")
        )
        assertTrue(
            "无障碍设置页必须用官方 action",
            nav.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS")
        )
        assertTrue(
            "启动必须包 runCatching（厂商 ROM 拦截时降级，不得成为新的崩溃点）",
            nav.contains("runCatching { context.startActivity(intent) }")
        )
        assertTrue(
            "不可解析时不得给出死链（resolveActivity 为空即不返回 Intent）",
            nav.contains("resolveActivity(context.packageManager) != null")
        )
        assertTrue(
            "健康卡必须对「系统未选中本应用」这一项接线直达入口",
            healthCard.contains("AutofillHealthIssue.SYSTEM_NOT_ENABLED") &&
                healthCard.contains("autofillServiceIntent")
        )
        assertTrue(
            "无障碍状态卡必须接线直达入口（ISSUE-P3-215：承载位置改为设置页安全分区）",
            accessibilityCard.contains("accessibilityIntent") && accessibilityCard.contains("launchSafely")
        )
    }

    // ---------------------------------------------------------------- 防空扫

    @Test
    fun `本批整改的扫描目标文件全部存在（防空扫）`() {
        val targets = listOf(
            DETAIL_CARDS, DETAIL_VIEW_MODEL, DETAIL_COPY_COORDINATOR, VAULT_ROW_LAYOUTS, VAULT_ACTION_CONTROLLER,
            VAULT_LIST_SCREEN, SECURITY_SCREEN, SECURITY_DIALOGS, SECURITY_COMPONENTS,
            SETTINGS_NAV_GRAPH, SETTINGS_VIEW_MODEL, HEALTH_CONTROLLER,
            CLOUD_SYNC_SCREEN, SYNC_CONTROLLER, SYSTEM_SETTINGS_NAV,
            AUTOFILL_HEALTH_CARD, UNLOCK_SCREEN
        )
        targets.forEach { path ->
            assertTrue("扫描目标不存在（路径已漂移）：$path", File(repositoryRoot, path).isFile)
        }
        assertEquals("扫描目标清单不得被悄悄删项（ISSUE-P3-188 增列复制协作者，16 → 17）", 17, targets.size)
    }

    // ---------------------------------------------------------------- helpers

    /** 摘出某个函数**声明行之后**的花括号配平函数体（供「必须出现在该函数内」类断言使用）。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "未找到函数签名：$signature" }
        val open = source.indexOf('{', start)
        return if (open < 0) {
            source.substring(start)
        } else {
            var depth = 0
            var end = open
            while (end < source.length) {
                when (source[end]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(open, end + 1)
                    }
                }
                end++
            }
            source.substring(open)
        }
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    /** 去块注释与整行行注释：避免 KDoc 里举例写的代码把扫描带偏。 */
    private fun stripComments(source: String): String = stripCommentsOnly(source)
    private companion object {

        const val DETAIL_CARDS = "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailCards.kt"
        const val DETAIL_VIEW_MODEL =
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailViewModel.kt"
        const val DETAIL_COPY_COORDINATOR =
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailCopyCoordinator.kt"
        const val VAULT_ROW_LAYOUTS =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultEntryRowLayouts.kt"
        const val VAULT_ACTION_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListActionController.kt"
        const val VAULT_LIST_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListScreen.kt"
        const val SECURITY_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/SecuritySettingsScreen.kt"
        const val SECURITY_DIALOGS =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/SecuritySettingsDialogs.kt"
        const val SECURITY_COMPONENTS =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/SecuritySettingsComponents.kt"
        const val SETTINGS_NAV_GRAPH =
            "app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsNavGraph.kt"
        const val SETTINGS_VIEW_MODEL =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt"
        const val HEALTH_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsHealthController.kt"
        const val CLOUD_SYNC_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncScreen.kt"
        const val SYNC_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt"
        const val SYSTEM_SETTINGS_NAV =
            "app/src/main/java/com/keepasskey/app/ui/components/SystemSettingsNavigation.kt"
        const val AUTOFILL_HEALTH_CARD =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/AutofillHealthCard.kt"
        const val UNLOCK_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/unlock/UnlockScreen.kt"

        const val ROOT_SEARCH_DEPTH = 6

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
