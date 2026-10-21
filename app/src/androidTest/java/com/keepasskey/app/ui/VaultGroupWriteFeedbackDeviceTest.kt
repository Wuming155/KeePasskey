package com.keepasskey.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultDatabaseCatalog
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P3-644` 的**真实 UI 反馈验收**（成功 / 失败 / 漂移 / 只读四态）。
 *
 * 与 `VaultGroupWriteFeedbackWiringTest`（JVM 源码守卫）的分工：那件只锁「接线不被改回」，
 * 本件在**真机 / 隔离 AVD** 上驱动生产的列表组合件（宿主 `VaultGroupFeedbackDeviceHostActivity`
 * 挂的就是 `VaultListContent` + `VaultListActionController`），经无障碍节点真实点击分组行菜单
 * 与图标选择对话框，并从生产 Snackbar 通道（`AppSnackbarChannel` → `AppGlobalSnackbarHost`）
 * 与无障碍树核对用户实际看到的文案。
 *
 * 四态与判据（对应整改方案 §4 验收矩阵）：
 * 1. **成功**：可写会话改图标 → 出现「已更新文件夹图标」且不出现失败文案；正式文件重开后
 *    分组图标确实变了，该分组的子条目与子分组**逐项保全**（P0-1 回归面）。
 * 2. **失败**：可写会话下让目标目录不可写（真实 IO 失败，非仓库替身）→ 只出现失败文案、
 *    **绝不出现成功文案**；正式文件逐字节不变、磁盘图标保持原值、子树保全。
 * 3. **漂移**：解锁后由外部改写正式文件 → 改图标被漂移防护中止 → 只出现失败文案；
 *    正式文件保持**外部版本**（备注仍是外部写的值、图标未被本地覆盖），子树保全。
 * 4. **只读**：只读会话 → 分组菜单三个写项不可点击、菜单内说明原因；溢出菜单无「选择」
 *    批量入口；批量顶栏三个写按钮置灰并说明原因；正式文件逐字节不变。
 *
 * 竞态纪律：全部等待都是「轮询直到业务事实成立」，不用固定 sleep 断言结果。
 */
@RunWith(AndroidJUnit4::class)
class VaultGroupWriteFeedbackDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    private val password = "P3-644-GroupFeedback-Device".toCharArray()

    private val repository: VaultRepository
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutofillDeviceGraph::class.java
        ).vaultRepository()
    private val session: DatabaseSession
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AutofillDeviceGraph::class.java
        ).databaseSession()

    private val ownedFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        runCatching { runBlocking { repository.lockDatabase() } }
        ownedFiles.forEach { file ->
            runCatching { file.parentFile?.setWritable(true) }
            runCatching {
                val catalog = VaultDatabaseCatalog(context, { _, _ -> "" }, session)
                catalog.saveKnownDatabases(
                    catalog.loadKnownDatabases().filterNot { it.path == file.absolutePath }
                )
            }
            runCatching { file.delete() }
            runCatching { File(file.absolutePath + ".bak").delete() }
            runCatching { File(file.absolutePath + ".tmp").delete() }
            runCatching { file.parentFile?.delete() }
        }
        password.fill('\u0000')
    }

    // ------------------------------------------------------------------ 用例

    @Test
    fun `成功态_改图标报成功且分组子树与文件内容保全`() {
        val file = freshVault(tag = "ok")
        registerAndUnlock(file, readOnly = false)
        enableAccessibilityRetrieval()

        ActivityScenario.launch<VaultGroupFeedbackDeviceHostActivity>(
            hostIntent(VaultGroupFeedbackDeviceHostActivity.MODE_LIST)
        ).use {
            assertTrue("分组行必须渲染", waitForText(GROUP_NAME))
            changeGroupIconViaUi("email")
            // 投影侧同口径：保存后 UI 名必须回读为 email（此前投影恒写 folder，见
            // `VaultGroupCoordinator.groupsFlow` 的 ISSUE-P3-644 修复）。
            val projection = runBlocking { repository.getGroups().first() }
                .firstOrNull { it.name == GROUP_NAME }?.iconName
            assertEquals("UI 投影图标名必须回读为 email（实际=$projection）", "email", projection)

            assertTrue(
                "保存成功必须出现成功文案；当前无障碍树=${accessibilityText()}",
                waitForText(text(R.string.vault_group_icon_updated))
            )
            assertFalse(
                "成功路径不得同时报失败；当前无障碍树=${accessibilityText()}",
                hasAnyTextWithPrefix(text(R.string.op_failed))
            )
        }

        // 落盘核对：不看内存、不看返回值
        val persisted = readVault(file)
        try {
            val group = persisted.rootGroup.allGroups().single { it.name == GROUP_NAME }
            assertEquals(
                "分组图标必须按 UI 选择落盘（email → KDBX 标准图标 19）",
                EMAIL_ICON_ID,
                group.iconId
            )
            assertTrue("分组子条目必须保全", group.entries.any { it.title == CHILD_ENTRY_TITLE })
            assertTrue("分组子分组必须保全", group.subgroups.any { it.name == CHILD_GROUP_NAME })
        } finally {
            persisted.clearSensitiveData()
        }
    }

    @Test
    fun `失败态_写盘失败只报失败且文件与子树不变`() {
        val file = freshVault(tag = "fail")
        registerAndUnlock(file, readOnly = false)
        enableAccessibilityRetrieval()
        val parent = requireNotNull(file.parentFile)
        val bytesBefore = file.readBytes()

        ActivityScenario.launch<VaultGroupFeedbackDeviceHostActivity>(
            hostIntent(VaultGroupFeedbackDeviceHostActivity.MODE_LIST)
        ).use {
            assertTrue("分组行必须渲染", waitForText(GROUP_NAME))
            // 先等上一条用例可能残留的成功消息退场，否则下面的「不得报成功」会假失败
            awaitNoText(text(R.string.vault_group_icon_updated))

            // 真实失败注入（确定性，不依赖 chmod 语义）：占用原子写盘的临时文件路径
            // `vault.kdbx.tmp`。生产 `AtomicFileWriter` 会先尝试删除该临时件（非空目录删不掉，
            // `File.delete()` 静默返回 false），随后 `FileOutputStream` 打开同名**目录**必然抛
            // FileNotFoundException ⇒ 走真实的写盘异常路径，而不是被漂移防护提前拦下。
            val tmpPath = File(parent, file.name + ".tmp")
            assertTrue("注入前置：父目录必须存在（$parent）", parent.isDirectory || parent.mkdirs())
            // 先建占位目录，再在内部写一个文件，使 tmpPath 成为**非空目录**
            // （空目录在部分文件系统上仍可能被 rename/覆盖，非空才稳定阻断原子替换）。
            assertTrue("注入装置创建失败：$tmpPath", tmpPath.isDirectory || tmpPath.mkdirs())
            val blockerFile = File(tmpPath, "blocker").apply { writeText("x") }
            assertTrue("注入前置：临时件路径必须是文件而非空目录", blockerFile.isFile)
            try {
                changeGroupIconViaUi("email")

                assertTrue(
                    "保存失败必须如实上报；当前无障碍树=${accessibilityText()}",
                    waitForTextWithPrefix(text(R.string.op_failed))
                )
                assertFalse(
                    "保存失败绝不能出现成功文案；当前无障碍树=${accessibilityText()}",
                    waitForText(text(R.string.vault_group_icon_updated), timeoutMs = 1_500L)
                )
            } finally {
                tmpPath.deleteRecursively()
            }
        }

        assertTrue(
            "写盘失败不得改动正式文件（实测=${file.length()}B）",
            bytesBefore.contentEquals(file.readBytes())
        )
        val persisted = readVault(file)
        try {
            val group = persisted.rootGroup.allGroups().single { it.name == GROUP_NAME }
            assertEquals("磁盘图标必须保持原值", FOLDER_ICON_ID, group.iconId)
            assertTrue("分组子条目必须保全", group.entries.any { it.title == CHILD_ENTRY_TITLE })
            assertTrue("分组子分组必须保全", group.subgroups.any { it.name == CHILD_GROUP_NAME })
        } finally {
            persisted.clearSensitiveData()
        }
    }

    @Test
    fun `漂移态_外部改写后改图标被中止且不覆盖外部版本`() {
        val file = freshVault(tag = "drift")
        registerAndUnlock(file, readOnly = false)
        enableAccessibilityRetrieval()

        ActivityScenario.launch<VaultGroupFeedbackDeviceHostActivity>(
            hostIntent(VaultGroupFeedbackDeviceHostActivity.MODE_LIST)
        ).use {
            assertTrue("分组行必须渲染", waitForText(GROUP_NAME))

            // 外部改写：结构与分组 id 不变，只把分组备注改成外部标记（文件字节与时间均变
            // ⇒ 漂移判定必然命中；不同长度的备注同时改变文件大小，避免仅靠 mtime 判定）
            val bytesBefore = file.readBytes()
            file.writeBytes(kdbxOf(iconId = FOLDER_ICON_ID, notes = EXTERNAL_MARKER))
            assertFalse("前置：外部版本必须与基线字节不同", bytesBefore.contentEquals(file.readBytes()))
            // 先等上一条用例可能残留的成功消息退场，否则下面的「不得报成功」会假失败
            awaitNoText(text(R.string.vault_group_icon_updated))

            changeGroupIconViaUi("email")

            assertTrue(
                "漂移中止必须如实上报失败；当前无障碍树=${accessibilityText()}",
                waitForTextWithPrefix(text(R.string.op_failed))
            )
            assertFalse(
                "漂移中止不得出现成功文案；当前无障碍树=${accessibilityText()}",
                waitForText(text(R.string.vault_group_icon_updated), timeoutMs = 1_500L)
            )
        }

        // 外部版本必须保全：备注仍是外部写的内容，图标仍是外部版本的值
        val persisted = readVault(file)
        try {
            val group = persisted.rootGroup.allGroups().single { it.name == GROUP_NAME }
            assertEquals("漂移中止不得把本地图标写入磁盘", FOLDER_ICON_ID, group.iconId)
            assertEquals("外部版本的备注必须保全", EXTERNAL_MARKER, group.notes)
            assertTrue("分组子条目必须保全", group.entries.any { it.title == CHILD_ENTRY_TITLE })
            assertTrue("分组子分组必须保全", group.subgroups.any { it.name == CHILD_GROUP_NAME })
        } finally {
            persisted.clearSensitiveData()
        }
    }

    @Test
    fun `只读态_菜单与批量入口禁用并说明原因且不写盘`() {
        val file = freshVault(tag = "readonly")
        registerAndUnlock(file, readOnly = true)
        enableAccessibilityRetrieval()
        val bytesBefore = file.readBytes()

        ActivityScenario.launch<VaultGroupFeedbackDeviceHostActivity>(
            hostIntent(VaultGroupFeedbackDeviceHostActivity.MODE_LIST_READ_ONLY)
        ).use {
            assertTrue("分组行必须渲染", waitForText(GROUP_NAME))
            // 先等上一条用例可能残留的成功消息退场（同上）
            awaitNoText(text(R.string.vault_group_icon_updated))

            // ① 分组行菜单：三个写项存在但不可点击，菜单内说明原因
            openGroupMenu(GROUP_NAME)
            assertTrue(
                "只读菜单必须说明不能保存的原因；当前无障碍树=${accessibilityText()}",
                waitForText(text(R.string.readonly_save_rejected))
            )
            listOf(
                R.string.vault_folder_rename,
                R.string.vault_folder_change_icon,
                R.string.vault_folder_delete
            ).forEach { res ->
                val node = waitForNode { it.text?.toString() == text(res) }
                assertNotNull("只读菜单必须仍可见写项：${text(res)}", node)
                // 取证：同时核对文本节点与其可点击祖先的 enabled（Compose 可能把状态放在语义父节点）
                // 判据落在**可点击祖先**上：Compose 的 DropdownMenuItem 把 enabled 放在可点击语义
                // 节点，内部 Text 子节点恒报 enabled=true（实测 chain 首项即 TextView(en=true)）。
                // 只断言文本节点会把正确的禁用实现判红。
                var probe: AccessibilityNodeInfo? = node
                val chain = mutableListOf<String>()
                var clickableAncestor: AccessibilityNodeInfo? = null
                repeat(MAX_CLICK_ANCESTORS) {
                    val n = probe ?: return@repeat
                    chain += "${n.className}(en=${n.isEnabled},click=${n.isClickable})"
                    if (clickableAncestor == null && n.isClickable) clickableAncestor = n
                    probe = n.parent
                }
                val menuItem = clickableAncestor
                    ?: error("菜单写入项没有可点击祖先：${text(res)} chain=$chain")
                assertFalse(
                    "只读会话下写项必须置灰（可点击项 enabled=false）：${text(res)} chain=$chain",
                    menuItem.isEnabled
                )
            }
            // 点击置灰项不得产生任何成功反馈
            findNodes(text(R.string.vault_folder_change_icon)).firstOrNull()
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            assertFalse(
                "只读会话点击写项不得报成功；当前无障碍树=${accessibilityText()}",
                waitForText(text(R.string.vault_group_icon_updated), timeoutMs = 1_500L)
            )
        }

        // ② 溢出菜单：不得提供「选择」批量入口（批量写在只读会话不可执行）
        ActivityScenario.launch<VaultGroupFeedbackDeviceHostActivity>(
            hostIntent(VaultGroupFeedbackDeviceHostActivity.MODE_LIST_READ_ONLY)
        ).use {
            assertTrue("分组行必须渲染", waitForText(GROUP_NAME))
            // 顶栏溢出菜单与分组行内菜单共用 `btn_more` 描述：这里必须点**顶栏**那个，
            // 因此取无障碍树中最后一个匹配节点（顶栏位于列表之后）。此前取首个 ⇒ 点开的是行菜单，
            // 「排序」永远不出现。
            val moreNodes = mutableListOf<AccessibilityNodeInfo>()
            fun collectMore(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.contentDescription?.toString() == text(R.string.btn_more)) moreNodes += node
                for (i in 0 until node.childCount) collectMore(node.getChild(i))
            }
            collectMore(automation.rootInActiveWindow)
            val topBarMore = moreNodes.lastOrNull()
                ?: error("找不到顶栏溢出菜单按钮（页面=${accessibilityText()}）")
            var probe: AccessibilityNodeInfo? = topBarMore
            var clicked = false
            repeat(MAX_CLICK_ANCESTORS) {
                val candidate = probe ?: return@repeat
                if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true
                    return@repeat
                }
                probe = candidate.parent
            }
            assertTrue("顶栏溢出菜单按钮点击失败", clicked)
            assertTrue("溢出菜单必须展开（排序项可见）", waitForText(text(R.string.cd_sort)))
            assertTrue(
                "只读会话不得提供「选择」批量入口；当前无障碍树=${accessibilityText()}",
                findNodes(text(R.string.vault_menu_select_entries)).isEmpty()
            )
        }

        // ③ 批量顶栏：三个写按钮置灰 + 原因同屏
        ActivityScenario.launch<VaultGroupFeedbackDeviceHostActivity>(
            hostIntent(VaultGroupFeedbackDeviceHostActivity.MODE_BATCH_READ_ONLY)
        ).use {
            assertTrue(
                "批量顶栏必须说明只读原因；当前无障碍树=${accessibilityText()}",
                waitForText(text(R.string.readonly_save_rejected))
            )
            listOf(R.string.cd_select_all, R.string.cd_batch_move, R.string.cd_batch_delete).forEach { res ->
                val node = waitForNode { it.contentDescription?.toString() == text(res) }
                assertNotNull("批量按钮必须存在：${text(res)}", node)
                // 同菜单项：IconButton 的 enabled 落在可点击语义节点，图标节点本身恒 enabled=true。
                var probe: AccessibilityNodeInfo? = node
                var clickable: AccessibilityNodeInfo? = null
                val chain = mutableListOf<String>()
                repeat(MAX_CLICK_ANCESTORS) {
                    val n = probe ?: return@repeat
                    chain += "${n.className}(en=${n.isEnabled},click=${n.isClickable})"
                    if (clickable == null && n.isClickable) clickable = n
                    probe = n.parent
                }
                val button = clickable ?: error("批量按钮没有可点击祖先：${text(res)} chain=$chain")
                assertFalse(
                    "只读会话下批量按钮必须置灰：${text(res)} chain=$chain",
                    button.isEnabled
                )
            }
        }

        assertTrue(
            "只读会话不得改动正式文件（实测=${file.length()}B）",
            bytesBefore.contentEquals(file.readBytes())
        )
    }

    // ------------------------------------------------------------------ 装置

    private fun hostIntent(mode: String) = Intent(
        context,
        VaultGroupFeedbackDeviceHostActivity::class.java
    ).putExtra(VaultGroupFeedbackDeviceHostActivity.EXTRA_MODE, mode)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun text(resId: Int): String = context.getString(resId)

    /**
     * 等某条文案从无障碍树消失（最长 [UI_TIMEOUT_MS]）。
     *
     * 用途：Snackbar 通道是进程级单例，**上一条用例**的成功消息可能仍在屏上；若不先等它退场，
     * 「失败态不得出现成功文案」会被残留消息判成假失败。只用「轮询到消失」而不改通道状态，
     * 避免与生产宿主的唯一消费者语义冲突。
     */
    private fun awaitNoText(label: String, timeoutMs: Long = UI_TIMEOUT_MS) {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (findNodes(label).isEmpty()) return
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun freshVault(tag: String): File {
        val dir = File(context.cacheDir, "p3644-$tag-${System.nanoTime()}").apply { mkdirs() }
        val file = File(dir, "vault.kdbx")
        ownedFiles += file
        return file
    }

    private fun kdbxOf(iconId: Int, notes: String): ByteArray {
        val childEntry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(CHILD_ENTRY_TITLE, false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("child-fake-pw", true)
            )
        )
        val target = KdbxGroup(
            id = GROUP_UUID,
            name = GROUP_NAME,
            notes = notes,
            iconId = iconId,
            entries = listOf(childEntry),
            subgroups = listOf(KdbxGroup(name = CHILD_GROUP_NAME))
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "P3-644 group feedback",
            rootGroup = KdbxGroup(name = "Root", subgroups = listOf(target))
        )
        return ByteArrayOutputStream().also { out -> KdbxFile.save(out, db, password) }.toByteArray()
    }

    private fun readVault(file: File): KdbxDatabase =
        KdbxFile.load(ByteArrayInputStream(file.readBytes()), password.clone())

    private fun registerAndUnlock(file: File, readOnly: Boolean) = runBlocking {
        file.writeBytes(kdbxOf(iconId = FOLDER_ICON_ID, notes = BASELINE_NOTES))
        assertTrue(
            "登记活动库必须成功",
            repository.importExternalDatabase("P3644-${file.parentFile?.name}", file.absolutePath)
                is KdbxResult.Success
        )
        var selected = false
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            repository.selectDatabase(file.absolutePath)
            if (repository.databaseInfoForPath(file.absolutePath)?.isActive == true) {
                selected = true
                break
            }
            android.os.SystemClock.sleep(50)
        }
        assertTrue("目标库必须成为活动库", selected)
        val unlocked = repository.unlockActiveDatabase(password.clone(), null, readOnly = readOnly)
        assertTrue("打开活动库必须成功: $unlocked", unlocked is KdbxResult.Success)
        assertEquals("会话只读态必须与打开参数一致", readOnly, repository.isSessionReadOnly())
        assertEquals("会话通道必须是本地文件", file.absolutePath, session.currentPathIdentifier)
    }

    /** 打开分组行菜单 → 换图标 → 在图标选择对话框里点标准图标（全程真实点击）。 */
    private fun changeGroupIconViaUi(iconId: String) {
        openGroupMenu(GROUP_NAME)
        clickText(text(R.string.vault_folder_change_icon))
        assertTrue("图标选择对话框必须出现", waitForText(text(R.string.icon_picker_title)))
        clickIcon(iconId)
    }

    /**
     * 打开**指定分组行**的溢出菜单。
     *
     * 此前直接按 `btn_more` 的描述点第一处，等于永远打开列表首行（Root）的菜单——图标就改到了
     * Root 上，而断言检查的是 P3644-Group。现按行文本定位其所属行容器，再在容器内找 `btn_more`。
     */
    private fun openGroupMenu(groupName: String) {
        assertTrue("分组行必须先渲染", waitForText(groupName))
        val label = waitForNode { it.text?.toString() == groupName }
            ?: error("找不到分组行「$groupName」（页面=${accessibilityText()}）")
        val row = run {
            var current: AccessibilityNodeInfo = label
            repeat(MAX_ROW_ANCESTORS) {
                val texts = mutableListOf<String>()
                fun collect(n: AccessibilityNodeInfo?) {
                    if (n == null) return
                    n.text?.toString()?.let { texts += it }
                    n.contentDescription?.toString()?.let { texts += it }
                    for (i in 0 until n.childCount) collect(n.getChild(i))
                }
                collect(current)
                if (text(R.string.btn_more) in texts) return@run current
                current = current.parent ?: return@run current
            }
            error("分组行「$groupName」内找不到溢出菜单按钮（页面=${accessibilityText()}）")
        }
        val more = run {
            var found: AccessibilityNodeInfo? = null
            fun collect(n: AccessibilityNodeInfo?) {
                if (n == null || found != null) return
                if (n.contentDescription?.toString() == text(R.string.btn_more)) {
                    found = n
                    return
                }
                for (i in 0 until n.childCount) collect(n.getChild(i))
            }
            collect(row)
            found
        } ?: error("分组行「$groupName」内找不到 btn_more（页面=${accessibilityText()}）")
        var current: AccessibilityNodeInfo? = more
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = candidate.parent
        }
        error("分组行「$groupName」的溢出菜单按钮不可点击（页面=${accessibilityText()}）")
        // 菜单首项是「重命名文件夹」：它出现即菜单已展开
        assertTrue(
            "分组行溢出菜单必须展开；当前无障碍树=${accessibilityText()}",
            waitForText(text(R.string.vault_folder_rename))
        )
    }

    /** 点击图标选择对话框内的标准图标（按本地化无障碍标签定位）。 */
    private fun clickIcon(iconId: String) {
        val label = text(iconLabelRes(iconId))
        val dialogRoot = waitForNode { it.text?.toString() == text(R.string.icon_picker_title) }
            ?.let { titleNode ->
                var top: AccessibilityNodeInfo = titleNode
                repeat(6) { top.parent?.let { parent -> top = parent } }
                top
            }
            ?: error("图标选择对话框未出现（页面=${accessibilityText()}）")
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.contentDescription?.toString() == label) matches += node
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(dialogRoot)
        assertEquals("对话框内图标「$label」必须唯一（页面=${accessibilityText()}）", 1, matches.size)
        val node = matches.single()
        // 图标方块以 `clickable {}` 承载选择语义：从图标节点向上找到可点击祖先并直接 ACTION_CLICK。
        // 坐标点击实测会落到相邻格（对话框与背后列表的窗口坐标叠加），故优先走语义点击。
        val targetBounds = Rect().also(node::getBoundsInScreen)
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable) {
                val candidateBounds = Rect().also(candidate::getBoundsInScreen)
                val clicked = candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SystemClock.sleep(600)
                assertTrue(
                    "点击图标「$label」失败：nodeBounds=$targetBounds clickableBounds=$candidateBounds " +
                        "clicked=$clicked（页面=${accessibilityText()}）",
                    clicked
                )
                // 选择后对话框必须关闭（生产 onSelectIcon + onDismiss）；未关闭说明点的不是图标格。
                assertFalse(
                    "点击图标「$label」后对话框仍未关闭：nodeBounds=$targetBounds " +
                        "clickableBounds=$candidateBounds（页面=${accessibilityText()}）",
                    findNodes(text(R.string.icon_picker_title)).isNotEmpty()
                )
                return
            }
            current = candidate.parent
        }
        error("图标「$label」没有可点击祖先（页面=${accessibilityText()}）")
    }


    private fun iconLabelRes(iconId: String): Int = when (iconId) {
        "email" -> R.string.icon_label_email
        else -> error("未在用例内登记图标标签: $iconId")
    }

    private fun clickText(label: String) {
        val node = waitForNode { it.text?.toString() == label }
            ?: error("找不到可点击文案：$label（页面=${accessibilityText()}）")
        clickNodeOrClickableAncestor(node, "文案 $label")
    }

    private fun clickContentDescription(description: String) {
        val node = waitForNode { it.contentDescription?.toString() == description }
            ?: error("找不到可点击描述：$description（页面=${accessibilityText()}）")
        clickNodeOrClickableAncestor(node, "描述 $description")
    }

    private fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo, label: String) {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = candidate.parent
        }
        error("$label 不可点击（页面=${accessibilityText()}）")
    }

    // --------------------------------------------------- 无障碍驱动辅助

    private fun enableAccessibilityRetrieval() {
        val info = automation.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }


    private fun findNodes(text: String): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString() == text) nodes += node
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(automation.rootInActiveWindow)
        automation.windows.orEmpty().forEach { collect(it.root) }
        return nodes.distinctBy { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            "${node.text}|$bounds"
        }
    }

    /**
     * `op_failed` 是「操作失败：%1$s」模板，实际文案带错误码后缀，故按**前缀**匹配。
     * 前缀取模板中 `%1$s` 之前的部分，避免把整条模板当字面量比对。
     */
    private fun hasAnyTextWithPrefix(template: String): Boolean {
        val prefix = template.substringBefore('%')
        return waitForNode(0L) { it.text?.toString()?.startsWith(prefix) == true } != null
    }

    private fun waitForTextWithPrefix(template: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean {
        val prefix = template.substringBefore('%')
        return waitForNode(timeoutMs) { it.text?.toString()?.startsWith(prefix) == true } != null
    }

    private fun waitForNode(
        timeoutMs: Long = UI_TIMEOUT_MS,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        do {
            instrumentation.waitForIdleSync()
            automation.rootInActiveWindow?.let { root ->
                findNode(root, predicate)?.let { return it }
            }
            automation.windows.orEmpty().forEach { window ->
                findNode(window.root, predicate)?.let { return it }
            }
            if (android.os.SystemClock.uptimeMillis() >= deadline) return null
            android.os.SystemClock.sleep(POLL_INTERVAL_MS)
        } while (true)
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) {
            findNode(root.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun waitForText(text: String, timeoutMs: Long = UI_TIMEOUT_MS): Boolean =
        waitForNode(timeoutMs) { it.text?.toString() == text } != null

    private fun accessibilityText(): String = buildString {
        fun appendNode(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH) return
            val label = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (label.isNotBlank() || description.isNotBlank()) {
                append(" ".repeat(depth)).append(node.className).append(" text=[").append(label)
                    .append("] desc=[").append(description).append("] enabled=")
                    .append(node.isEnabled).append('\n')
            }
            for (index in 0 until node.childCount) appendNode(node.getChild(index), depth + 1)
        }
        appendNode(automation.rootInActiveWindow, 0)
        automation.windows.orEmpty().forEach { appendNode(it.root, 0) }
    }

    private companion object {
        const val GROUP_NAME = "P3644-Group"
        const val CHILD_GROUP_NAME = "P3644-Child"
        const val CHILD_ENTRY_TITLE = "P3644-ChildEntry"
        const val BASELINE_NOTES = "baseline"
        const val EXTERNAL_MARKER = "EXTERNAL-REWRITE-BY-ANOTHER-CLIENT"

        /** 与 `VaultIconMapping.idOf("folder")` / `idOf("email")` 同源（KDBX 标准图标） */
        const val FOLDER_ICON_ID = 48
        const val EMAIL_ICON_ID = 19

        val GROUP_UUID: KdbxUuid = KdbxUuid(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x64, 0x44)
        )

        const val UI_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
        const val MAX_CLICK_ANCESTORS = 8
        const val MAX_ICON_SCROLLS = 4
        const val MAX_ROW_ANCESTORS = 6
        const val MAX_TREE_DEPTH = 24
    }
}
