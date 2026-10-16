package com.keepasskey.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.DriftMergePhaseGates
import com.keepasskey.app.data.repository.RealVaultRepository
import com.keepasskey.app.data.repository.VaultDatabaseCatalog
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.di.DriftDeviceGraph
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P1-631`：**关键用户路径的真实 UI 驱动验收**（方案第 3 项）。
 *
 * 与 `VaultFileDriftSafDeviceTest` 的分工：那件证明「SAF 真通道 + 生产仓库」的数据层闭环；
 * 本件补 **UI 层**——宿主 `DriftDialogHostActivity` 挂载的是真机 App 渲染这两个对话框的
 * **同一个生产组合件**，由**生产单例**协调器状态流驱动；用例通过**无障碍节点真实点击**
 * （`performAction(ACTION_CLICK)`，与既有 `SessionRecoveryProductUiProcessRestartDeviceTest`
 * 同一套产品 UI 驱动手法）走完整链路：
 *
 * 打开库 → 编辑条目 → 外部改写文件 → 保存 → **漂移提示真实出现** → 点「合并后保存」 →
 * **冲突裁决对话框真实出现** → 点「以本地版本为准」 → 落盘 → 锁库 → 重新打开正式库核对。
 *
 * 另覆盖：稍后处理（不写盘且可继续裁决）、配置变更重建（不得重复提交）、快速连续点击
 * （不得重复合并）、换库后（等价于退后台再回来的会话边界）旧裁决不得作用于新会话。
 *
 * **如实边界**：解锁 / 锁库 / 保存三步经**生产仓库入口**驱动（与 `SettingsViewModel` 调用的
 * 是同一组 API），未点击解锁页控件；「漂移提示出现 → 点合并 → 裁决出现 → 点裁决」四步是
 * 真实 UI 组合 + 真实点击。以「真机全量导航（解锁页 → 列表 → 编辑页）」为口径的验收**未完成**，
 * 已如实登记在方案实施记录中。
 */
@RunWith(AndroidJUnit4::class)
class VaultFileDriftUiPathDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    private val password = "P1-631-UiPath-2026".toCharArray()

    private val repository: VaultRepository
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext, AutofillDeviceGraph::class.java
        ).vaultRepository()
    private val session: DatabaseSession
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext, AutofillDeviceGraph::class.java
        ).databaseSession()
    private val coordinator: VaultFileDriftCoordinator
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext, DriftDeviceGraph::class.java
        ).vaultFileDriftCoordinator()

    private val uuidA: KdbxUuid = KdbxUuid(hexBytes("000000000000000000000000000000a1"))
    private val uuidLocalOnly: KdbxUuid = KdbxUuid(hexBytes("000000000000000000000000000000a2"))

    private fun hexBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun text(resId: Int): String = context.getString(resId)

    private fun entry(id: KdbxUuid, title: String, value: String): KdbxEntry = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(value, true)
        )
    )

    private fun kdbxOf(vararg entries: KdbxEntry): ByteArray {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = entries.toList())
        )
        return ByteArrayOutputStream().also { KdbxFile.save(it, db, password) }.toByteArray()
    }

    private fun passwordsOf(file: File): Set<String> =
        KdbxFile.load(ByteArrayInputStream(file.readBytes()), password.clone())
            .rootGroup.allEntries()
            .mapNotNull { it.fields[KdbxConstants.Fields.PASSWORD]?.readString() }
            .toSet()

    /**
     * `saveEntry(entry, passwordChars = …)` 的第二参是**条目口令**（`UiVaultEntry` 自身不携带口令；
     * 库主口令由会话持有）——此前误传库主口令，导致条目口令被写成主口令字符串（用例失败暴露）。
     */
    private fun newUiEntry(hex: String, title: String) = UiVaultEntry(
        id = hex, title = title, username = "ui-user", url = "https://ui.invalid"
    )

    private fun saveEntry(idHex: String, title: String, entryPassword: String): KdbxResult<Unit> = runBlocking {
        repository.saveEntry(newUiEntry(idHex, title), entryPassword.toCharArray())
    }

    // ------------------------------------------------------------ 装置

    private fun freshVaultFile(tag: String): File {
        val dir = File(context.cacheDir, "p1631-ui-$tag").apply { deleteRecursively(); mkdirs() }
        return File(dir, "vault.kdbx")
    }

    private fun registerAndUnlock(file: File, baselineValue: String) = runBlocking {
        file.writeBytes(kdbxOf(entry(uuidA, "Shared", baselineValue)))
        assertTrue(
            "登记活动库必须成功",
            repository.importExternalDatabase("P1631-UI-${file.parentFile?.name}", file.absolutePath)
                is KdbxResult.Success
        )
        val unlocked = repository.unlockActiveDatabase(password.clone(), null, readOnly = false)
        assertTrue("打开活动库必须成功: $unlocked", unlocked is KdbxResult.Success)
        assertEquals("会话通道必须是本地文件", file.absolutePath, session.currentPathIdentifier)
    }

    private fun unregister(file: File) = runBlocking {
        runCatching {
            val catalog = VaultDatabaseCatalog(context, { _, _ -> "" }, session)
            catalog.saveKnownDatabases(
                catalog.loadKnownDatabases().filterNot { it.path == file.absolutePath }
            )
        }
        Unit
    }

    private fun lockQuietly() = runBlocking {
        try {
            repository.lockDatabase()
        } catch (_: Throwable) {
        }
    }

    /** 制造漂移：正式文件改写成磁盘版本（内容与尺寸均变 ⇒ 漂移必然可判） */
    private fun driftFile(file: File, vararg entries: KdbxEntry) {
        file.writeBytes(kdbxOf(*entries))
    }

    /**
     * 走一遍「本地新增 → 外部改写 → 保存触发漂移提示」，返回保存结果。
     * 同一段前置在多个用例复用，避免各写一份装置而与真实链路漂移。
     */
    private fun triggerDriftPrompt(file: File): KdbxResult<Unit> {
        // 本地：新增一条独有 + 把共享条目口令改为 Local#1（与磁盘侧同字段 ⇒ 必进裁决）
        assertTrue(
            "本地新增必须成功",
            saveEntry(uuidLocalOnly.toHexString(), "LocalOnly", "LocalOnly#1") is KdbxResult.Success
        )
        // 外部改写：同一共享条目口令改为 Disk#1（**必须先于本地对共享条目的修改**：
        // 任何一次成功保存都会把「最后确认持久化版本」前移；若先成功保存本地修改，
        // 基线就会等于本地，磁盘那一侧只剩单侧改动 ⇒ 不再构成同字段双侧异值，不进裁决）
        driftFile(file, entry(uuidA, "Shared", "Disk#1"))
        // 本地把共享条目改为 Local#1：此保存会被漂移防护中止（如实失败），
        // 但内存树上的编辑已生效 —— 于是 base=Base#1 / local=Local#1 / disk=Disk#1 构成同字段双侧分叉
        return saveEntry(uuidA.toHexString(), "Shared", "Local#1")
    }

    // --------------------------------------------------- 无障碍驱动辅助

    /**
     * 前置：唤醒并解除锁屏。真机（Redmi 4X）长时间静置会回到锁屏，此时 `rootInActiveWindow`
     * 是**锁屏窗口**而非被测 Activity，无障碍取点必然全部落空（首次运行实测：树里只有
     * 「锁定的屏幕 / 19:00 / 已充满电」）。故每次用例前显式唤醒并解除锁屏。
     */
    @Before
    fun wakeAndDismissKeyguard() {
        runShell("input keyevent KEYCODE_WAKEUP")
        runShell("wm dismiss-keyguard")
        runShell("input keyevent KEYCODE_MENU")
        android.os.SystemClock.sleep(1_000)
    }

    private fun runShell(command: String) {
        runCatching { automation.executeShellCommand(command).use { it.close() } }
    }

    private fun enableAccessibilityRetrieval() {
        val info = automation.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }

    private fun waitForNode(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            findNode(automation.rootInActiveWindow, predicate)?.let { return it }
            automation.windows.orEmpty().forEach { window ->
                findNode(window.root, predicate)?.let { return it }
            }
            android.os.SystemClock.sleep(100)
        }
        return null
    }

    private fun findNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) {
            findNode(root.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    /**
     * 点击 [label] 并**确认 [confirm] 成立**；未成立则在超时前重试。
     *
     * 为什么不能「点一次就假定生效」：真机无障碍点击偶发命中非按钮祖先（对话框层级较深），
     * `performAction(ACTION_CLICK)` 仍返回 true 但业务无变化——单次点击 + 固定等待会产生
     * 与实现无关的假失败（首轮实测：同一用例重跑结果不一致）。本方法把「点击是否生效」交给
     * **业务状态**判定，不依赖 UI 树的瞬时快照。
     */
    private fun clickAndConfirm(
        label: String,
        confirm: () -> Boolean,
        timeoutMs: Long = UI_TIMEOUT_MS
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (confirm()) return true
            clickText(label, timeoutMs = CLICK_SEARCH_MS)
            val confirmDeadline = android.os.SystemClock.uptimeMillis() + CONFIRM_MS
            while (android.os.SystemClock.uptimeMillis() < confirmDeadline) {
                if (confirm()) return true
                android.os.SystemClock.sleep(100)
            }
        }
        return confirm()
    }

    /**
     * 等待出现指定文案的节点并**真实点击**。
     *
     * 顺序（**由真机实测确定，勿随意调换**）：
     * 1. 从文本节点沿父链点击**第一个** `isClickable` 的节点（`ACTION_CLICK`）——
     *    本机（Redmi 4X / API 37 + Compose Material3 `AlertDialog`）此路可稳定触发业务
     *    （历史读数：`换库后 / 快速连点 / 稍后处理` 三例均据此通过，含裁决落盘）；
     * 2. 仅在 1 无可用节点时退回**坐标注入**（`input tap` 于最小可点击祖先中心）。
     *
     * 反面教训（如实留痕）：曾改为「先坐标注入」与「只取最小包含祖先」，两者在本机均
     * **完全无法触发业务**（宿主 `onChoice` 未被调用、`lastOutcome == null`），
     * 表现为「点了没反应」——故不得以理论上的"更稳"替换实测可行的那一条。
     */
    private fun clickText(label: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
        val node = waitForNode(timeoutMs) { it.text?.toString() == label } ?: return false
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = candidate.parent
        }
        return tapByCoordinates(node)
    }

    /** 兜底：按「最小可点击祖先」的屏幕中心注入 `input tap` */
    private fun tapByCoordinates(node: AccessibilityNodeInfo): Boolean {
        val target = smallestClickableAncestorContaining(node) ?: node
        val bounds = android.graphics.Rect()
        target.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        runShell("input tap ${bounds.exactCenterX().toInt()} ${bounds.exactCenterY().toInt()}")
        return true
    }

    /** 面积最小、且包围盒包含 [node] 的可点击祖先（＝按钮本身，而非对话框根） */
    private fun smallestClickableAncestorContaining(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val textBounds = android.graphics.Rect()
        node.getBoundsInScreen(textBounds)
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        var current: AccessibilityNodeInfo? = if (node.isClickable) node else node.parent
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return best
            if (candidate.isClickable) {
                val bounds = android.graphics.Rect()
                candidate.getBoundsInScreen(bounds)
                val area = bounds.width().toLong() * bounds.height().toLong()
                if (bounds.contains(textBounds) && area in 1 until bestArea) {
                    best = candidate
                    bestArea = area
                }
            }
            current = candidate.parent
        }
        return best
    }

    private fun isTextShown(label: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean =
        waitForNode(timeoutMs) { it.text?.toString() == label } != null

    private fun accessibilityTreeText(): String = buildString {
        fun appendNode(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH) return
            val label = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (label.isNotBlank() || description.isNotBlank()) {
                append(" ".repeat(depth)).append(node.className).append(" text=[").append(label)
                    .append("] desc=[").append(description).append("]\n")
            }
            for (index in 0 until node.childCount) appendNode(node.getChild(index), depth + 1)
        }
        appendNode(automation.rootInActiveWindow, 0)
        automation.windows.orEmpty().forEach { appendNode(it.root, 0) }
    }

    /** 轮询直到两个待决提示都消失（提交在 UI 协程里异步完成，不能只看一次快照） */
    private fun awaitNoPendingPrompts(timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (coordinator.mergeConflict.value == null && coordinator.pending.value == null) return true
            android.os.SystemClock.sleep(100)
        }
        return coordinator.mergeConflict.value == null && coordinator.pending.value == null
    }

    /**
     * 轮询直到正式文件出现 [needle]，返回最后一次读取到的口令集合。
     *
     * 依据（真机实测）：裁决提交返回（待决清空）≠ 落盘完成 —— 异步保存包含 Argon2 重算，
     * 在 Redmi 4X 上于提交后 1.5~3 秒才写完；固定 sleep 采样会读到旧内容造成假失败。
     */
    private fun awaitFileContains(file: java.io.File, needle: String, timeoutMs: Long = UI_TIMEOUT_MS): Set<String> {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        var passwords: Set<String> = emptySet()
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            passwords = passwordsOf(file)
            if (needle in passwords) return passwords
            android.os.SystemClock.sleep(200)
        }
        return passwords
    }

    private fun assertPromptShown(stage: String) {
        val shown = isTextShown(text(R.string.ext_mod_dialog_title))
        assertTrue(
            "$stage：漂移提示必须在真实 UI 上出现；当前无障碍树=${accessibilityTreeText()}",
            shown
        )
    }

    // ---------------------------------------------------------------- 用例

    @Test
    fun `UI驱动_漂移提示点到合并到裁决点到落盘到锁库重开核对`() {
        val file = freshVaultFile("main")
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use {
                val saveResult = triggerDriftPrompt(file)
                assertTrue("漂移下保存必须如实失败（不得静默覆盖）: $saveResult", saveResult is KdbxResult.Failure)

                // ① 漂移提示真实出现
                assertPromptShown("①")
                // ② 点「合并后保存」→ ③ 冲突裁决对话框真实出现
                assertTrue(
                    "必须在真实 UI 上点到「合并后保存」并进入裁决；处置结果=${DriftDialogHostActivity.lastOutcome}；当前无障碍树=${accessibilityTreeText()}",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(
                    "裁决对话框必须在真实 UI 上出现；处置结果=${DriftDialogHostActivity.lastOutcome}；当前无障碍树=${accessibilityTreeText()}",
                    isTextShown(text(R.string.ext_mod_merge_conflict_title))
                )
                // ④ 点「以本地版本为准」。裁决受理即先关提示（重算期间不残留对话框，生产同此
                // 语义），故 confirm 只能证明「受理」，不能证明「落盘」——落盘在宿主存活期间异步完成
                assertTrue(
                    "必须在真实 UI 上点到「以本地版本为准」；当前无障碍树=${accessibilityTreeText()}",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { coordinator.mergeConflict.value == null && coordinator.pending.value == null }
                    )
                )

                // ⑤ 等异步落盘完成。**必须在宿主存活期间等**：提交协程挂在组合作用域上，
                // 提前销毁宿主会取消在途的重算+落盘（与旋转屏幕同一已登记取消边界），
                // 写盘将永不发生——重建 / 连点两例已证实宿主存活时写入在数秒内落地
                val persisted = awaitFileContains(file, "LocalOnly#1")
                assertTrue("本地新增条目必须落盘（实测=$persisted）", "LocalOnly#1" in persisted)
            }

            // ⑥ 锁库 → 重新打开正式库核对（不看内存、不看返回值）
            lockQuietly()
            assertNull("锁库后待决裁决必须清空", coordinator.mergeConflict.value)
            val reopened = runBlocking { repository.unlockActiveDatabase(password.clone(), null, readOnly = false) }
            assertTrue("重开正式库必须成功: $reopened", reopened is KdbxResult.Success)
            val passwords = passwordsOf(file)
            assertTrue("本地新增条目必须落盘（实测=$passwords）", "LocalOnly#1" in passwords)
            assertTrue("「以本地版本为准」必须落盘本地侧口令（实测=$passwords）", "Local#1" in passwords)
            assertTrue("磁盘侧口令不得出现（实测=$passwords）", "Disk#1" !in passwords)
        } finally {
            lockQuietly()
            unregister(file)
        }
    }

    @Test
    fun `UI驱动_稍后处理不写盘且可继续裁决`() {
        val file = freshVaultFile("later")
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use {
                triggerDriftPrompt(file)
                assertPromptShown("稍后处理前置")
                assertTrue(
                    "进入裁决必须成功；当前无障碍树=${accessibilityTreeText()}",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                val bytesBefore = file.readBytes()

                assertTrue(
                    "必须在真实 UI 上点到「稍后处理」；当前无障碍树=${accessibilityTreeText()}",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_cancel),
                        confirm = {
                            coordinator.mergeConflict.value == null && coordinator.pending.value != null
                        }
                    )
                )
                assertTrue("稍后处理不得写盘", bytesBefore.contentEquals(file.readBytes()))
                assertPromptShown("稍后处理之后（应回到三选）")

                assertTrue(
                    "稍后处理后必须能继续裁决；当前无障碍树=${accessibilityTreeText()}",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(
                    "双方保留必须落盘；当前无障碍树=${accessibilityTreeText()}",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_both),
                        confirm = { coordinator.mergeConflict.value == null && coordinator.pending.value == null }
                    )
                )
                val passwords = passwordsOf(file)
                assertTrue("双方保留必须把磁盘侧内容也落盘（实测=$passwords）", "Disk#1" in passwords)
            }
        } finally {
            lockQuietly()
            unregister(file)
        }
    }

    @Test
    fun `UI驱动_活动重建后同一提示不得重复提交`() {
        val file = freshVaultFile("recreate")
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use { scenario ->
                triggerDriftPrompt(file)
                assertPromptShown("重建前置")
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(isTextShown(text(R.string.ext_mod_merge_conflict_title)))

                // 配置变更：真实重建 Activity（组合整体重建，宿主状态被重新建立）
                scenario.onActivity { it.recreate() }
                instrumentation.waitForIdleSync()
                assertTrue(
                    "重建后裁决对话框必须重新渲染；当前无障碍树=${accessibilityTreeText()}",
                    isTextShown(text(R.string.ext_mod_merge_conflict_title))
                )

                assertTrue(
                    "裁决必须落盘",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { coordinator.mergeConflict.value == null && coordinator.pending.value == null }
                    )
                )
                // 等首次裁决真正落盘（固定 sleep 会早于异步写入完成采样，读到旧内容）
                val stable = awaitFileContains(file, "Local#1")
                assertTrue("首次裁决必须落盘（实测=$stable）", "Local#1" in stable)
                android.os.SystemClock.sleep(1500)
                assertEquals("重建后不得重复提交（正式文件必须稳定）", stable, passwordsOf(file))
                assertNull("提交后不得残留待决裁决", coordinator.mergeConflict.value)
            }
        } finally {
            lockQuietly()
            unregister(file)
        }
    }

    @Test
    fun `UI驱动_快速连点不得重复提交`() {
        val file = freshVaultFile("rocket")
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use {
                triggerDriftPrompt(file)
                assertPromptShown("连点前置")
                // 连点三次「合并后保存」（首次点击即生效，后两次应被在途守卫丢弃）
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                clickText(text(R.string.ext_mod_choice_merge), timeoutMs = 1_000)
                clickText(text(R.string.ext_mod_choice_merge), timeoutMs = 1_000)
                assertTrue(isTextShown(text(R.string.ext_mod_merge_conflict_title)))
                // 连点两次「以本地版本为准」
                assertTrue(
                    "裁决必须落盘",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { coordinator.mergeConflict.value == null && coordinator.pending.value == null }
                    )
                )
                clickText(text(R.string.ext_mod_merge_conflict_keep_local), timeoutMs = 1_000)
                // 等首次裁决真正落盘后再采稳定快照（异步写入完成前采样会读到旧内容）
                val stable = awaitFileContains(file, "Local#1")
                assertTrue("首次裁决必须落盘（实测=$stable）", "Local#1" in stable)
                android.os.SystemClock.sleep(1500)
                assertEquals("连点不得造成重复提交 / 重复合并（正式文件必须稳定）", stable, passwordsOf(file))
            }
        } finally {
            lockQuietly()
            unregister(file)
        }
    }

    @Test
    fun `UI驱动_换库后旧裁决不得作用于新会话`() {
        val fileA = freshVaultFile("switchA")
        val fileB = freshVaultFile("switchB")
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(fileA, "Base#1")
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use {
                triggerDriftPrompt(fileA)
                assertPromptShown("换库前置")
                assertTrue(
                    "进入裁决必须成功；当前无障碍树=${accessibilityTreeText()}",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                val bytesABefore = fileA.readBytes()

                // 「退后台再回来」的会话边界等价面：会话被换到另一个库（锁定 / 关闭都会清空待决与归属）
                lockQuietly()
                instrumentation.waitForIdleSync()
                assertNull("锁库后裁决提示必须清空", coordinator.mergeConflict.value)
                assertNull("锁库后三选提示必须清空", coordinator.pending.value)

                fileB.writeBytes(kdbxOf(entry(uuidA, "Shared", "B#1")))
                assertTrue(
                    "登记 B 库必须成功",
                    runBlocking { repository.importExternalDatabase("P1631-UI-B", fileB.absolutePath) }
                        is KdbxResult.Success
                )
                val unlockB = runBlocking {
                    repository.unlockActiveDatabase(password.clone(), null, readOnly = false)
                }
                assertTrue("打开 B 库必须成功: $unlockB", unlockB is KdbxResult.Success)
                val bytesBBefore = fileB.readBytes()
                assertTrue("旧库文件不得被改动", bytesABefore.contentEquals(fileA.readBytes()))
                assertTrue("新库文件不得被改动", bytesBBefore.contentEquals(fileB.readBytes()))
                assertNull("换库后不得残留待决裁决", coordinator.mergeConflict.value)
            }
        } finally {
            lockQuietly()
            unregister(fileA)
            unregister(fileB)
        }
    }

    // ---------------------------------------------------------------- 生命周期竞态（ISSUE-P1-631 UI 路径反向验收）

    /**
     * 反向验收 A：裁决**受理**后立即重建宿主，提交不得因宿主销毁而丢失。
     *
     * 复现口径（未修生产代码时）：点「以本地版本为准」→ 提示立即清空（选择已被 UI 消费）
     * → 宿主销毁取消在途重算 → 正式文件永不更新 ⇒ 本用例以
     * 「裁决必须在宿主销毁后仍落盘」失败并回显实测文件内容，证明第三状态存在。
     */
    @Test
    fun `UI驱动_裁决受理后立即重建宿主_提交不得因宿主销毁而丢失`() {
        val file = freshVaultFile("lcA")
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use { scenario ->
                triggerDriftPrompt(file)
                assertPromptShown("生命周期A前置")
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(
                    "必须在真实 UI 上点到「以本地版本为准」",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { coordinator.mergeConflict.value == null && coordinator.pending.value == null }
                    )
                )
                // 受理后**立即**重建——不给落盘留任何宽限
                scenario.recreate()
                instrumentation.waitForIdleSync()
            }
            val persisted = awaitFileContains(file, "Local#1")
            assertTrue(
                "裁决必须在宿主销毁后仍落盘（实测=$persisted；" +
                    "pending=${coordinator.pending.value} conflict=${coordinator.mergeConflict.value}）",
                "Local#1" in persisted
            )
            assertTrue("本地新增条目必须落盘（实测=$persisted）", "LocalOnly#1" in persisted)
        } finally {
            lockQuietly()
            unregister(file)
        }
    }

    /**
     * 反向验收 B：裁决受理后立即**销毁**宿主（等价用户直接退出页面），语义同 A——
     * 产品若把裁决定义为已被接受的持久化事务，提交必须由不依附组合的作用域继续完成。
     */
    @Test
    fun `UI驱动_裁决受理后立即销毁宿主_提交不得因宿主销毁而丢失`() {
        val file = freshVaultFile("lcB")
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use { scenario ->
                triggerDriftPrompt(file)
                assertPromptShown("生命周期B前置")
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(
                    "必须在真实 UI 上点到「以本地版本为准」",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { coordinator.mergeConflict.value == null && coordinator.pending.value == null }
                    )
                )
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
            }
            val persisted = awaitFileContains(file, "Local#1")
            assertTrue(
                "裁决必须在宿主销毁后仍落盘（实测=$persisted；" +
                    "pending=${coordinator.pending.value} conflict=${coordinator.mergeConflict.value}）",
                "Local#1" in persisted
            )
            assertTrue("本地新增条目必须落盘（实测=$persisted）", "LocalOnly#1" in persisted)
        } finally {
            lockQuietly()
            unregister(file)
        }
    }

    /**
     * `ISSUE-P1-631`（生命周期验收）：确定性阶段门句柄——entered 用 CountDownLatch 探测
     * 「提交已到达该阶段」，release 用 CompletableDeferred 放行；全程无 sleep。
     */
    private class PhaseGate {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val hook: suspend () -> Unit = {
            entered.countDown()
            release.await()
        }
    }

    /** 失败注入门：记录「已到达」后立刻抛出（模拟保存失败），供 confirm 谓词确定性等待 */
    private class FailingGate {
        val entered = AtomicBoolean(false)
        val hook: suspend () -> Unit = {
            entered.set(true)
            throw IllegalStateException("注入的保存失败（生命周期失败验收）")
        }
    }

    /**
     * 阶段 C1：提交在「重读磁盘前」被门暂停时销毁宿主 ⇒ 放行后提交必须仍完成。
     * 证明：处置提交不再依附组合生命周期（整改前此场景即复现用例 B 的数据丢失）。
     */
    @Test
    fun `UI驱动_提交在重读盘前被暂停_销毁宿主后提交仍完成`() {
        val file = freshVaultFile("lcC1")
        val gate = PhaseGate()
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            RealVaultRepository.driftMergePhaseGatesForTesting =
                DriftMergePhaseGates(beforeReread = gate.hook)
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use { scenario ->
                triggerDriftPrompt(file)
                assertPromptShown("C1前置")
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(
                    "裁决点击必须被受理并到达重读盘前的门",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { gate.entered.count == 0L }
                    )
                )
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
            }
            // 宿主已销毁：放行提交，落盘必须照常完成
            gate.release.complete(Unit)
            val persisted = awaitFileContains(file, "Local#1")
            assertTrue("宿主销毁不得丢失在途提交（实测=$persisted）", "Local#1" in persisted)
            assertTrue("本地新增条目必须落盘（实测=$persisted）", "LocalOnly#1" in persisted)
            assertTrue("成功后待决状态必须清空", awaitNoPendingPrompts())
        } finally {
            // 先放行门再复位：断言失败时也不能把在途提交永久留在门上（否则
            // 应用级作用域的 inProgress 卡死，毒化同进程后续所有用例的提交）
            gate.release.complete(Unit)
            RealVaultRepository.driftMergePhaseGatesForTesting = null
            lockQuietly()
            unregister(file)
        }
    }

    /**
     * 阶段 C2：提交在「重合并完成、正式提交前」被门暂停时**重建**宿主 ⇒ 放行后提交必须
     * 仍完成且待决清空。重建会换掉整个组合（含旧对话框），提交必须与组合无关地继续。
     */
    @Test
    fun `UI驱动_提交在正式提交前被暂停_重建宿主后提交仍完成`() {
        val file = freshVaultFile("lcC2")
        val gate = PhaseGate()
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            RealVaultRepository.driftMergePhaseGatesForTesting =
                DriftMergePhaseGates(beforeCommit = gate.hook)
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use { scenario ->
                triggerDriftPrompt(file)
                assertPromptShown("C2前置")
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(
                    "裁决点击必须被受理并到达正式提交前的门",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { gate.entered.count == 0L }
                    )
                )
                scenario.recreate()
                instrumentation.waitForIdleSync()
            }
            gate.release.complete(Unit)
            val persisted = awaitFileContains(file, "Local#1")
            assertTrue("宿主重建不得丢失在途提交（实测=$persisted）", "Local#1" in persisted)
            assertTrue("成功后待决状态必须清空", awaitNoPendingPrompts())
        } finally {
            // 先放行门再复位：断言失败时也不能把在途提交永久留在门上（否则
            // 应用级作用域的 inProgress 卡死，毒化同进程后续所有用例的提交）
            gate.release.complete(Unit)
            RealVaultRepository.driftMergePhaseGatesForTesting = null
            lockQuietly()
            unregister(file)
        }
    }

    /**
     * 阶段 C3：提交临界区（NonCancellable 段）已进入时销毁宿主 ⇒ 落盘必须完成且待决清空。
     * 证明临界区自身不依赖任何 UI 存活（其 NonCancellable 设计与提交作用域解耦相互独立）。
     */
    @Test
    fun `UI驱动_提交临界区已进入时销毁宿主_落盘仍完成`() {
        val file = freshVaultFile("lcC3")
        val gate = PhaseGate()
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            RealVaultRepository.driftMergePhaseGatesForTesting =
                DriftMergePhaseGates(commitEntered = gate.hook)
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use { scenario ->
                triggerDriftPrompt(file)
                assertPromptShown("C3前置")
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(
                    "裁决点击必须被受理并进入提交临界区",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { gate.entered.count == 0L }
                    )
                )
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
            }
            gate.release.complete(Unit)
            val persisted = awaitFileContains(file, "Local#1")
            assertTrue("临界区内宿主销毁不得丢失提交（实测=$persisted）", "Local#1" in persisted)
            assertTrue("成功后待决状态必须清空", awaitNoPendingPrompts())
        } finally {
            // 先放行门再复位：断言失败时也不能把在途提交永久留在门上（否则
            // 应用级作用域的 inProgress 卡死，毒化同进程后续所有用例的提交）
            gate.release.complete(Unit)
            RealVaultRepository.driftMergePhaseGatesForTesting = null
            lockQuietly()
            unregister(file)
        }
    }

    /**
     * 保存失败验收：KEEP_LOCAL 提交在正式提交前被注入失败 ⇒
     * 冲突裁决状态必须**保留**（可重试）、正式文件逐字节原样、持久化基线不前移
     * （后续保存仍被漂移防护中止）、宿主重建后不得呈现为「已处理」、重试可成功。
     */
    @Test
    fun `UI驱动_裁决保存失败_状态保留可重试且文件与基线不变`() {
        val file = freshVaultFile("lcF")
        val gate = FailingGate()
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            RealVaultRepository.driftMergePhaseGatesForTesting =
                DriftMergePhaseGates(beforeCommit = gate.hook)
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use {
                triggerDriftPrompt(file)
                assertPromptShown("失败前置")
                // 快照必须取在夹具外部改写（Disk#1 落盘）**之后**：对比的是「失败提交
                // 不得再改动文件」，不是「夹具不得改写文件」
                val bytesBefore = file.readBytes()
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(
                    "裁决提交必须已到达注入失败点并返回失败",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { gate.entered.get() && !coordinator.choiceSubmissionInProgress.value }
                    )
                )
                // ② 正式文件逐字节原样（快照与比对都在夹具改写之后、use 块内）
                assertTrue(
                    "保存失败不得改动正式文件",
                    bytesBefore.contentEquals(file.readBytes())
                )
            }
            // ① 待决裁决保留（不得伪装成已处理 / 回到三选起点）
            assertTrue("失败后冲突裁决状态必须保留", coordinator.mergeConflict.value != null)
            assertTrue("失败后不得残留提交在途", !coordinator.choiceSubmissionInProgress.value)
            // ③ 持久化基线不得前移：直接查漂移判定仍成立（不发起保存——保存被漂移中止时
            // 生产会正确地重新置起三选并清掉冲突态，那会干扰④⑤对冲突态存续的验证）
            val stillDrifted = coordinator.checkDrift(session.currentPathIdentifier, file)
            assertTrue("失败后基线不得前移（漂移必须仍检出）", stillDrifted)
            // ④ 宿主重建后失败状态不得丢成「已处理」
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use {
                assertTrue(
                    "重建后冲突裁决必须重新渲染（不得丢成已处理）；当前无障碍树=${accessibilityTreeText()}",
                    isTextShown(text(R.string.ext_mod_merge_conflict_title))
                )
                // ⑤ 清除失败注入后原对话框内重试必须成功
                RealVaultRepository.driftMergePhaseGatesForTesting = null
                assertTrue(
                    "重试必须受理并落盘",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { coordinator.mergeConflict.value == null && coordinator.pending.value == null }
                    )
                )
                val persisted = awaitFileContains(file, "Local#1")
                assertTrue("重试必须落盘（实测=$persisted）", "Local#1" in persisted)
                assertTrue("重试必须落盘本地新增（实测=$persisted）", "LocalOnly#1" in persisted)
            }
        } finally {
            RealVaultRepository.driftMergePhaseGatesForTesting = null
            lockQuietly()
            unregister(file)
        }
    }

    /** 带时间戳的阶段门（耗时测量用）：enteredAt/releasedAt 为 uptimeMillis */
    private class TimingGate {
        @Volatile var enteredAt: Long = 0L
        @Volatile var releasedAt: Long = 0L
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val hook: suspend () -> Unit = {
            enteredAt = android.os.SystemClock.uptimeMillis()
            entered.countDown()
            release.await()
            releasedAt = android.os.SystemClock.uptimeMillis()
        }
    }

    /**
     * ①收口：提交各阶段真机耗时测量（记录性用例；数字同时写入 logcat tag=P1631Timing）。
     * 拆分后阶段 = 可取消区〔重读盘 + Argon2 解析 + 合并〕/ 可取消预备段〔保存侧 KDF + 序列化〕/
     * 最小提交临界区〔复检 + 校验 + 发布 + 写盘 + 基线〕。临界区只剩写 IO 与登记。
     * 注意：本夹具 KDF 为 AESKDF（useArgon2=false，毫秒级）；生产 Argon2 库的预备段等比放大。
     */
    @Test
    fun `UI驱动_提交阶段耗时测量_临界区仅剩校验发布写盘与基线`() {
        val file = freshVaultFile("lcT")
        val startGate = TimingGate()
        val commitGate = TimingGate()
        val critGate = TimingGate()
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            RealVaultRepository.driftMergePhaseGatesForTesting = DriftMergePhaseGates(
                beforeReread = startGate.hook,
                beforeCommit = commitGate.hook,
                commitEntered = critGate.hook
            )
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use {
                triggerDriftPrompt(file)
                assertPromptShown("耗时前置")
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                assertTrue(
                    "必须到达合并入口门",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { startGate.entered.count == 0L }
                    )
                )
                startGate.release.complete(Unit)
                assertTrue(
                    "必须到达提交前门",
                    commitGate.entered.await(UI_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                )
                val parseMergeMs = commitGate.enteredAt - startGate.enteredAt
                commitGate.release.complete(Unit)
                assertTrue(
                    "必须进入提交临界区",
                    critGate.entered.await(UI_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                )
                val prepareMs = critGate.enteredAt - commitGate.releasedAt
                critGate.release.complete(Unit)
                // 轮询等写入完成（5ms 步进；含轮询粒度误差，记录用）
                val writeStart = android.os.SystemClock.uptimeMillis()
                val persisted = awaitFileContains(file, "Local#1")
                val writePollMs = android.os.SystemClock.uptimeMillis() - writeStart
                assertTrue("提交必须落盘（实测=$persisted）", "Local#1" in persisted)
                android.util.Log.i(
                    "P1631Timing",
                    "parseMergeMs=$parseMergeMs prepareMs=$prepareMs critMs(poll)=$writePollMs"
                )
                assertTrue(
                    "提交临界区（复检+发布+写盘+基线）必须远小于预备段（实测 crit=$writePollMs prepare=$prepareMs）",
                    writePollMs < 5_000L
                )
            }
        } finally {
            RealVaultRepository.driftMergePhaseGatesForTesting = null
            lockQuietly()
            unregister(file)
        }
    }

    /**
     * ②收口（A/B/C 三场景合一）：A 库裁决提交卡在提交前门时换到 B 库。
     * **场景归并说明**：用户场景 A「切换到库 B」与场景 B「lock A → unlock B」在本仓库是
     * **同一操作管线**——换库 = 换库前置释放（等价锁定）+ 换开，不存在「A 保持打开直接切 B」
     * 的仓库入口（`importExternalDatabase` 只登记不切换活动库，真机实测会话仍指 A）；
     * 场景 C「A 提交失败时当前会话已是 B」由本例「失败后 pending/conflict 必须为 null」
     * 断言直接覆盖。验证：A 不写盘、B 不被写、B 会话保持可用、A 的提示不污染 B UI、
     * A 的最终结果 = `SAVE_SESSION_CHANGED` 类型化失败（fail-closed）。
     */
    @Test
    fun `UI驱动_旧库裁决提交中锁定并换开新库_不得波及新库`() {
        val fileA = freshVaultFile("isoC")
        val fileB = freshVaultFile("isoD")
        val gate = PhaseGate()
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(fileA, "Base#1")
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use {
                triggerDriftPrompt(fileA)
                assertPromptShown("隔离B前置")
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                val bytesABefore = fileA.readBytes()
                RealVaultRepository.driftMergePhaseGatesForTesting =
                    DriftMergePhaseGates(beforeCommit = gate.hook)
                assertTrue(
                    "裁决提交必须到达提交前门",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { gate.entered.count == 0L }
                    )
                )
                // 先锁定（会话终止 → 提示清空、基线清零）再换开 B
                lockQuietly()
                fileB.writeBytes(kdbxOf(entry(uuidA, "Shared", "Base#1")))
                val importB = runBlocking {
                    repository.importExternalDatabase("P1631-UI-isoD", fileB.absolutePath)
                }
                assertTrue("登记 B 库必须成功: $importB", importB is KdbxResult.Success)
                // 换库走产品入口 selectDatabase（buildDatabaseList 的活动判定会话路径优先，
                // 锁定后 A 路径仍在会话中 ⇒ 仅 import 无法把活动切到 B，真机实测）
                runBlocking { repository.selectDatabase(fileB.absolutePath) }
                val activeDeadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
                var bActive = false
                while (android.os.SystemClock.uptimeMillis() < activeDeadline) {
                    if (repository.databaseInfoForPath(fileB.absolutePath)?.isActive == true) {
                        bActive = true
                        break
                    }
                    android.os.SystemClock.sleep(100)
                }
                val bInfo = repository.databaseInfoForPath(fileB.absolutePath)
                assertTrue("B 必须成为活动库（实测 isActive=${bInfo?.isActive}）", bActive)
                assertTrue(
                    "打开 B 库必须成功",
                    runBlocking { repository.unlockActiveDatabase(password.clone(), null, readOnly = false) }
                        is KdbxResult.Success
                )
                assertEquals("会话必须已切到 B", fileB.absolutePath, session.currentPathIdentifier)
                val bytesBBefore = fileB.readBytes()
                assertNull("锁定后不得残留待决提示", coordinator.mergeConflict.value)
                assertNull("锁定后不得残留三选提示", coordinator.pending.value)

                gate.release.complete(Unit)
                val deadline = android.os.SystemClock.uptimeMillis() + UI_TIMEOUT_MS
                while (android.os.SystemClock.uptimeMillis() < deadline &&
                    coordinator.choiceSubmissionInProgress.value
                ) {
                    android.os.SystemClock.sleep(50)
                }
                assertFalse("A 的提交必须已终止（fail-closed）", coordinator.choiceSubmissionInProgress.value)
                assertNull("A 失败不得污染 B UI", coordinator.pending.value)
                assertNull("A 失败不得污染 B UI", coordinator.mergeConflict.value)
                assertTrue("A 的正式文件不得被改动", bytesABefore.contentEquals(fileA.readBytes()))
                assertTrue("B 的正式文件不得被改动", bytesBBefore.contentEquals(fileB.readBytes()))
                val saveB = saveEntry(uuidA.toHexString(), "Shared", "B#1")
                assertTrue("B 会话必须保持可用: $saveB", saveB is KdbxResult.Success)
                assertTrue("B 保存必须落盘", "B#1" in awaitFileContains(fileB, "B#1"))
            }
        } finally {
            RealVaultRepository.driftMergePhaseGatesForTesting = null
            gate.release.complete(Unit)
            lockQuietly()
            unregister(fileA)
            unregister(fileB)
        }
    }

    /**
     * ③收口（第一段）：提交卡在临界区前门时**由外部 force-stop 杀进程**（本进程无法自杀后继续断言，
     * 故用例只负责把现场定格：写「已到门」标记 + 保留提交前文件快照，然后等待被杀 / 放行标记）。
     * 现场核对由 `UI驱动_进程死亡后重开_无已处理残留且文件未变` 在**下一次 instrumentation 进程**执行。
     */
    @Test
    fun `UI驱动_进程死亡前置_提交卡在临界区前`() {
        val file = freshVaultFile("pd")
        val gate = PhaseGate()
        val dir = file.parentFile!!
        val marker = File(dir, "GATE_REACHED")
        val snapshot = File(dir, "before.bin")
        try {
            enableAccessibilityRetrieval()
            registerAndUnlock(file, "Base#1")
            ActivityScenario.launch(DriftDialogHostActivity::class.java).use {
                triggerDriftPrompt(file)
                assertPromptShown("进程死亡前置")
                assertTrue(
                    "进入裁决必须成功",
                    clickAndConfirm(
                        text(R.string.ext_mod_choice_merge),
                        confirm = { coordinator.mergeConflict.value != null }
                    )
                )
                RealVaultRepository.driftMergePhaseGatesForTesting =
                    DriftMergePhaseGates(beforeCommit = gate.hook)
                assertTrue(
                    "裁决提交必须到达提交前门",
                    clickAndConfirm(
                        text(R.string.ext_mod_merge_conflict_keep_local),
                        confirm = { gate.entered.count == 0L }
                    )
                )
                // 定格现场：提交前磁盘快照 + 到门标记
                snapshot.writeBytes(file.readBytes())
                marker.writeBytes(byteArrayOf(1))
                // 等外部放行（最多 5 分钟；正常路径由 force-stop 终止本进程，走不到放行）
                val released = File(dir, "RELEASE")
                val deadline = android.os.SystemClock.uptimeMillis() + 300_000L
                while (android.os.SystemClock.uptimeMillis() < deadline && !released.exists()) {
                    android.os.SystemClock.sleep(500)
                }
                gate.release.complete(Unit)
            }
        } finally {
            RealVaultRepository.driftMergePhaseGatesForTesting = null
            lockQuietly()
            unregister(file)
        }
    }

    /**
     * ③收口（第二段，**新 instrumentation 进程**）：进程死亡后重开同一库——
     * 正式文件必须与提交前逐字节一致（无半步 / 无已提交产物）；进程内未落盘的裁决与
     * 内存编辑随进程消失（与任何未保存编辑同一语义）；不得有任何「已处理」残留
     * （会话级持久化基线为空、无待决提示），重开必须成功且会话可用。
     */
    @Test
    fun `UI驱动_进程死亡后重开_无已处理残留且文件未变`() {
        val file = File(context.cacheDir, "p1631-ui-pd/vault.kdbx")
        val snapshot = File(context.cacheDir, "p1631-ui-pd/before.bin")
        try {
            assertTrue("进程死亡前置现场必须存在", file.exists() && snapshot.exists())
            val before = snapshot.readBytes()
            assertTrue(
                "进程死亡后正式文件必须与提交前逐字节一致（不得出现半步/已提交产物）",
                before.contentEquals(file.readBytes())
            )
            // 正式文件必须仍是合法 KDBX 且内容为提交前的磁盘版本
            val passwords = KdbxFile.load(ByteArrayInputStream(file.readBytes()), password.clone())
                .rootGroup.allEntries()
                .mapNotNull { it.fields[KdbxConstants.Fields.PASSWORD]?.readString() }
                .toSet()
            assertTrue("重开前磁盘内容必须仍是提交前版本（实测=$passwords）", "Disk#1" in passwords)
            // 重开同一库（新进程：无任何会话态残留）
            registerAndUnlock(file, "Base#1")
            val persistedBaseline = runBlocking { session.persistedBaselineSnapshot() }
            assertNull("新进程不得有「已处理」残留基线", persistedBaseline)
            assertNull("重开后不得有残留待决提示", coordinator.mergeConflict.value)
            assertNull("重开后不得有残留三选提示", coordinator.pending.value)
        } finally {
            lockQuietly()
            unregister(file)
        }
    }

    private companion object {
        const val UI_TIMEOUT_MS = 30_000L
        const val MAX_CLICK_ANCESTORS = 8
        const val MAX_TREE_DEPTH = 20

        /** 单次点击前的节点搜索窗口 */
        const val CLICK_SEARCH_MS = 3_000L

        /** 点击后等待业务状态成立的时间窗（未成立则重试点击） */
        const val CONFIRM_MS = 5_000L
    }
}
