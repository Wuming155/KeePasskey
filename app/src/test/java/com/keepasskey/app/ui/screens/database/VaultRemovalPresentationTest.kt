package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultRemovalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P1-241` 的**判定与文案映射**用例（纯函数，全部宿主 JVM 可测）。
 *
 * ## 本项要防的失效形态
 * 整改前「移除密码库」只有**一套**确认文案（「这只会从本机的密码库切换列表中移除该条目，
 * 不会删除物理文件」），而数据层对应用私有库**真的 `File(filesDir, id).delete()`**
 * ——文案与行为相反，用户按文案操作即**永久丢库**（真机取证见 §243 §7）。
 *
 * 故断言指向四种复现形态：
 * 1. **判据漏型**：存储类型 → 真实对象的映射必须按类型穷举（私有目录内的文件 / `content://` /
 *    应用外绝对路径 / 用户输入的裸相对文件名 / 同名前缀的兄弟目录），漏一种就可能把「会删」
 *    说成「不删」；
 * 2. **兜底方向错**：判不出私有目录时**必须**取「不删文件」一侧（反过来的错误才是数据丢失）；
 * 3. **文案漂移**：私有库侧须明示「永久删除」+「无法恢复」+ **指名被删文件**，外部库侧须维持
 *    「只摘登记、不删文件」，且两套的标题 / 正文 / 确认按钮**三处措辞均不得相同**；
 * 4. **中英文不同步**：两侧取值同批锁定（英文侧同样缺一即红）。
 *
 * 说明：本项**不**断言文件是否真被删除——那属数据层行为，由
 * [VaultRemovalWiringTest] 钉住「删除只在私有库分支发生」，其余场景由设备侧回归取证。
 */
class VaultRemovalPresentationTest {

    // ========== AC④ 之一：存储类型 → 真实对象（穷举） ==========

    @Test
    fun `应用私有目录内的库文件判定为私有库`() {
        listOf(
            "$FILES_DIR/passwords.kdbx",
            "$FILES_DIR/测试.kdbx",
            "$FILES_DIR/.hidden.kdbx",
            "$FILES_DIR/e2e-issue240.kdbx"
        ).forEach { path ->
            assertEquals(
                "「$path」的应用私有目录直属文件必须判为私有库（移除即删文件）",
                VaultRemovalKind.PRIVATE_FILE,
                VaultRemovalKind.of(path, FILES_DIR)
            )
        }

        // 私有目录字符串带尾斜杠（平台返回值差异）时不得漏判：漏判的后果正是
        // 「弹窗承诺不删文件，文件却被删掉」
        assertEquals(
            "私有目录尾斜杠不得致漏判",
            VaultRemovalKind.PRIVATE_FILE,
            VaultRemovalKind.of("$FILES_DIR/passwords.kdbx", "$FILES_DIR/")
        )
    }

    @Test
    fun `私有目录之外的一切来源判定为外部库`() {
        listOf(
            // SAF 文档（打开已有 / 新建自选位置）
            "content://com.android.providers.downloads.documents/document/1234",
            "content://com.keepasskey.fileprovider/vault/backup.kdbx",
            // 应用外绝对路径
            "/storage/emulated/0/Documents/外部.kdbx",
            // 兄弟目录 / 前缀相似目录：不得因字符串前缀相近而误判为私有库
            "/data/user/0/com.keepasskey/files2/passwords.kdbx",
            "/data/user/0/com.keepasskey/files_backup/passwords.kdbx",
            // 用户在「打开已有」里把本地路径填成裸相对文件名（父目录为 null）
            "passwords.kdbx",
            "./passwords.kdbx",
            // 远端地址（WebDAV / S3）
            "https://dav.example.com/remote.kdbx"
        ).forEach { path ->
            assertEquals(
                "「$path」的物理文件在应用之外，必须判为外部库（只摘登记、不删文件）",
                VaultRemovalKind.EXTERNAL_LINK,
                VaultRemovalKind.of(path, FILES_DIR)
            )
        }
    }

    @Test
    fun `私有目录不可用或路径为空时一律判为外部库`() {
        // 兜底方向是**安全侧**：判不出「是不是应用私有目录」时宁可不删——若反过来取
        // PRIVATE_FILE，等于在判定失败时替用户删库
        assertEquals(
            "私有目录不可用（上下文为空）时必须取不删文件一侧",
            VaultRemovalKind.EXTERNAL_LINK,
            VaultRemovalKind.of("$FILES_DIR/passwords.kdbx", null)
        )
        assertEquals(
            "私有目录为空串时必须取不删文件一侧",
            VaultRemovalKind.EXTERNAL_LINK,
            VaultRemovalKind.of("$FILES_DIR/passwords.kdbx", "")
        )
        assertEquals(
            "空路径必须取不删文件一侧",
            VaultRemovalKind.EXTERNAL_LINK,
            VaultRemovalKind.of("", FILES_DIR)
        )
    }

    // ========== AC①②：文案与动作的映射 ==========

    @Test
    fun `私有库确认为破坏性动作且指名被删文件`() {
        val confirmation = VaultRemovalConfirmation.of(privateVault(), FILES_DIR)

        assertEquals("判据必须是私有库", VaultRemovalKind.PRIVATE_FILE, confirmation.kind)
        assertTrue("私有库的移除是破坏性动作（AC② 要求红底危险按钮）", confirmation.destructive)
        assertEquals(
            "破坏性动作必须指名被删文件（AC②）",
            listOf("passwords.kdbx"),
            confirmation.messageArgs
        )
        assertEquals(R.string.db_picker_remove_private_title, confirmation.titleRes)
        assertEquals(R.string.db_picker_remove_private_desc, confirmation.messageRes)
        assertEquals(R.string.db_picker_remove_private_confirm, confirmation.confirmRes)
    }

    @Test
    fun `外部库确认为非破坏性动作且不指名文件`() {
        val confirmation = VaultRemovalConfirmation.of(externalVault(), FILES_DIR)

        assertEquals("判据必须是外部库", VaultRemovalKind.EXTERNAL_LINK, confirmation.kind)
        assertFalse("外部库只摘登记，不得呈现为破坏性动作", confirmation.destructive)
        assertTrue("外部库不涉及任何文件，正文不得带文件名参数", confirmation.messageArgs.isEmpty())
        assertEquals(R.string.db_picker_remove_external_title, confirmation.titleRes)
        assertEquals(R.string.db_picker_remove_external_desc, confirmation.messageRes)
        assertEquals(R.string.db_picker_remove_external_confirm, confirmation.confirmRes)
    }

    @Test
    fun `两类确认的措辞与动作不得共用同一套`() {
        val privateConfirmation = VaultRemovalConfirmation.of(privateVault(), FILES_DIR)
        val externalConfirmation = VaultRemovalConfirmation.of(externalVault(), FILES_DIR)

        assertNotEquals("标题不得共用", privateConfirmation.titleRes, externalConfirmation.titleRes)
        assertNotEquals("正文不得共用", privateConfirmation.messageRes, externalConfirmation.messageRes)
        assertNotEquals(
            "确认按钮不得共用（AC②：不得与「仅移除关联」共用同一套确认措辞）",
            privateConfirmation.confirmRes,
            externalConfirmation.confirmRes
        )
        assertNotEquals(
            "动作危险性不得相同（否则界面无从区分破坏性动作）",
            privateConfirmation.destructive,
            externalConfirmation.destructive
        )
    }

    // ========== AC①④：文案取值本身（中英同步） ==========

    @Test
    fun `中文文案如实声明永久删除且不可恢复`() {
        val title = zh("db_picker_remove_private_title")
        val desc = zh("db_picker_remove_private_desc")
        val confirm = zh("db_picker_remove_private_confirm")

        assertTrue("标题须点明「永久删除」：$title", title.contains("永久删除"))
        assertTrue("标题须点明被删对象是应用私有目录中的文件：$title", title.contains("应用私有目录"))
        assertTrue("正文须写明「无法恢复」：$desc", desc.contains("无法恢复"))
        assertTrue("正文须带文件名占位符（否则不会指名被删文件）：$desc", desc.contains("%1\$s"))
        assertFalse("正文不得再出现「不会删除物理文件」这一反向承诺：$desc", desc.contains("不会删除物理文件"))
        assertTrue("确认按钮须是破坏性措辞：$confirm", confirm.contains("永久删除"))
    }

    @Test
    fun `中文外部库文案维持仅移除关联`() {
        val title = zh("db_picker_remove_external_title")
        val desc = zh("db_picker_remove_external_desc")
        val confirm = zh("db_picker_remove_external_confirm")

        assertTrue("标题须是「移除关联」语义：$title", title.contains("关联"))
        assertTrue("正文须维持「不会删除物理文件」这一与真实行为一致的承诺：$desc", desc.contains("不会删除物理文件"))
        assertFalse("外部库不得被描述为永久删除：$desc", desc.contains("永久删除"))
        assertTrue("确认按钮须是「仅移除关联」：$confirm", confirm.contains("仅移除关联"))
    }

    @Test
    fun `英文文案与中文同步`() {
        val privateTitle = en("db_picker_remove_private_title")
        val privateDesc = en("db_picker_remove_private_desc")
        val privateConfirm = en("db_picker_remove_private_confirm")
        val externalDesc = en("db_picker_remove_external_desc")
        val externalConfirm = en("db_picker_remove_external_confirm")

        assertTrue("英文标题须点明永久删除：$privateTitle", privateTitle.contains("Permanently delete"))
        assertTrue("英文正文须写明不可恢复：$privateDesc", privateDesc.contains("cannot be recovered"))
        assertTrue("英文正文须带文件名占位符：$privateDesc", privateDesc.contains("%1\$s"))
        assertTrue("英文确认按钮须是破坏性措辞：$privateConfirm", privateConfirm.contains("Delete permanently"))
        assertTrue(
            "英文外部库正文须维持「不删物理文件」的承诺：$externalDesc",
            externalDesc.contains("will not be deleted")
        )
        assertTrue("英文外部库确认按钮须是「仅移除关联」：$externalConfirm", externalConfirm.contains("Remove link only"))
        // 交叉否定：两套措辞不得互相串味（私有侧不得承诺不删、外部侧不得声称永久删除）
        assertFalse(
            "英文私有库正文不得残留外部库那套「不删文件」承诺：$privateDesc",
            privateDesc.contains("will not be deleted")
        )
        assertFalse(
            "英文外部库正文不得声称永久删除：$externalDesc",
            externalDesc.contains("Permanently")
        )
    }

    // ========== 夹具与解析助手 ==========

    private fun privateVault(): VaultDatabaseInfo = VaultDatabaseInfo(
        id = "passwords.kdbx",
        name = "passwords.kdbx",
        path = "$FILES_DIR/passwords.kdbx",
        isRemote = false,
        syncType = "本地设备存储"
    )

    private fun externalVault(): VaultDatabaseInfo = VaultDatabaseInfo(
        id = "content://com.android.providers.downloads.documents/document/1234",
        name = "下载的库.kdbx",
        path = "content://com.android.providers.downloads.documents/document/1234",
        isRemote = false,
        syncType = "系统文件选择器"
    )

    private fun zh(name: String): String = stringValue(zhStrings, name)

    private fun en(name: String): String = stringValue(enStrings, name)

    private val zhStrings: String by lazy { readRepoFile("app/src/main/res/values/strings.xml") }

    private val enStrings: String by lazy { readRepoFile("app/src/main/res/values-en/strings.xml") }

    /** 取 `<string name="…">值</string>` 的值；缺失即断言失败（键被改名 / 删除后本类静默失效） */
    private fun stringValue(source: String, name: String): String {
        val match = Regex("<string name=\"$name\">([^<]*)</string>").find(source)
        assertTrue("字符串资源 $name 不存在（是否被改名/删除）", match != null)
        return match!!.groupValues[1]
    }

    /** 源码全文；app 模块测试工作目录为 `app/`，向上回溯定位仓库根 */
    private fun readRepoFile(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val candidate = dir ?: return@repeat
            val target = File(candidate, relativePath)
            if (target.isFile) return target.readText()
            dir = candidate.parentFile
        }
        error("无法定位仓库文件：$relativePath（起始：${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 4

        /** 应用私有目录（取值形态取自 §243 真机取证时的 `run-as` 读数） */
        const val FILES_DIR = "/data/user/0/com.keepasskey/files"
    }
}
