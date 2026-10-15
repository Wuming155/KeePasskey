package com.keepasskey.app.quality

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P3-616` 的**离主线程接线守卫**（静态源码比对）。
 *
 * ## 锁定的缺陷
 *
 * 外部 Provider 元数据查询（`ContentResolver.query` + Cursor 读取）此前在四处主线程路径上
 * 同步执行：`OpenVaultEntryActivity` 的显示名解析、`DatabasePickerScreen` 的共享展示名解析器、
 * 以及 `OpenExistingVaultDialog` / `CreateVaultWizardDialog` 两个向导的 SAF 回调。外部应用
 * 可注册缓慢甚至无响应的 Provider，主线程等待即 ANR。
 *
 * ## 判据设计
 *
 * - **查询本体**（两处直查点）：`resolveDisplayName` / `queryDocumentDisplayName` 必须为
 *   `suspend`，且 `contentResolver.query(` 落在 `withContext(Dispatchers.IO)` 范围内；
 *   同时这两份文件内 `contentResolver.query(` **只允许一处**（防止新增旁路同步查询）。
 * - **调用接线**（三处对话框回调）：展示名解析必须在 `rememberCoroutineScope()` 的
 *   `scope.launch` 内调用（对话框离场即取消）；两个向导文件**不得**出现直查
 *   （必须经共享解析器，确保 IO 归属单一收口）。
 *
 * 覆盖面如实声明（`ISSUE-P3-616` 建议口径）：本机 JVM 无 Robolectric / 设备 mocking 面，
 * 无法构造「缓慢 Provider + 主线程不阻塞」的运行时等价现场；ANR 设备复现本次未执行
 * （见批次文档「未执行项」），本守卫只锁定调度器接线不变式——查询一旦回到主线程裸执行即红。
 */
class ProviderMetadataQueryOffMainThreadWiringTest {

    @Test
    fun `外部打开入口的显示名解析为 suspend 且查询落 IO 派发器`() {
        val source = stripped(OPEN_ENTRY_ACTIVITY)

        assertTrue(
            "OpenVaultEntryActivity 的 resolveDisplayName 必须为 suspend，" +
                "contentResolver.query 必须整体落在 withContext(Dispatchers.IO) 内" +
                "（ISSUE-P3-616：不得在主线程等待外部 Provider）",
            Regex(
                """suspend fun resolveDisplayName[\s\S]{0,300}?withContext\(Dispatchers\.IO\)[\s\S]{0,300}?contentResolver\.query\("""
            ).containsMatchIn(source)
        )
        assertEquals(
            "OpenVaultEntryActivity 内 contentResolver.query 只允许一处（且已被 IO 包裹）",
            1,
            Regex("""contentResolver\.query\(""").findAll(source).count()
        )
        assertTrue(
            "显示名解析必须在生命周期作用域协程内调用（Activity 销毁即取消，ISSUE-P3-616）",
            Regex("""lifecycleScope\.launch[\s\S]{0,200}?resolveDisplayName\(uri\)""").containsMatchIn(source)
        )
    }

    @Test
    fun `共享展示名解析器为 suspend 且查询落 IO 派发器`() {
        // 解析器自 DatabasePickerScreen 拆至 DocumentDisplayName（行数闸门，零行为变更）
        val source = stripped(SHARED_RESOLVER)

        assertTrue(
            "queryDocumentDisplayName 必须为 suspend，contentResolver.query 必须整体落在" +
                " withContext(Dispatchers.IO) 内（ISSUE-P3-616）",
            Regex(
                """suspend fun queryDocumentDisplayName[\s\S]{0,300}?withContext\(Dispatchers\.IO\)[\s\S]{0,300}?contentResolver\.query\("""
            ).containsMatchIn(source)
        )
        assertEquals(
            "共享解析器内 contentResolver.query 只允许一处（IO 查询收口）",
            1,
            Regex("""contentResolver\.query\(""").findAll(source).count()
        )
        val screen = stripped(DATABASE_PICKER_SCREEN)
        assertEquals(
            "DatabasePickerScreen 不得自建 Provider 查询（统一经共享解析器收口）",
            0,
            Regex("""contentResolver\.query\(""").findAll(screen).count()
        )
    }

    @Test
    fun `两个向导的 SAF 回调都在组合作用域协程内调用展示名解析且无直查`() {
        val openDialog = stripped(OPEN_EXISTING_DIALOG)
        assertTrue(
            "OpenExistingVaultDialog 必须持有 rememberCoroutineScope（ISSUE-P3-616：离场即取消）",
            openDialog.contains("rememberCoroutineScope()")
        )
        assertTrue(
            "OpenExistingVaultDialog 的 SAF 回调必须经 scope.launch 调用 queryDocumentDisplayName",
            Regex("""scope\.launch\s*\{[\s\S]{0,300}?queryDocumentDisplayName\(context, uri\)""")
                .containsMatchIn(openDialog)
        )
        assertEquals(
            "OpenExistingVaultDialog 不得直查 Provider（查询统一经共享解析器收口）",
            0,
            Regex("""contentResolver\.query\(""").findAll(openDialog).count()
        )

        val createDialog = stripped(CREATE_WIZARD_DIALOG)
        assertTrue(
            "CreateVaultWizardDialog 必须持有 rememberCoroutineScope（ISSUE-P3-616：离场即取消）",
            createDialog.contains("rememberCoroutineScope()")
        )
        assertEquals(
            "CreateVaultWizardDialog 两处 SAF 回调（选密钥文件 / 自选库位置）都必须经 scope.launch 调用 queryDocumentDisplayName",
            2,
            Regex("""scope\.launch\s*\{[\s\S]{0,320}?queryDocumentDisplayName\(context, uri\)""")
                .findAll(createDialog).count()
        )
        assertEquals(
            "CreateVaultWizardDialog 不得直查 Provider（查询统一经共享解析器收口）",
            0,
            Regex("""contentResolver\.query\(""").findAll(createDialog).count()
        )
    }

    private fun stripped(relativePath: String): String = stripCommentsOnly(readSource(relativePath))

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("被守卫文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val OPEN_ENTRY_ACTIVITY = "app/src/main/java/com/keepasskey/app/OpenVaultEntryActivity.kt"
        const val DATABASE_PICKER_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/DatabasePickerScreen.kt"
        const val SHARED_RESOLVER =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/DocumentDisplayName.kt"
        const val OPEN_EXISTING_DIALOG =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/OpenExistingVaultDialog.kt"
        const val CREATE_WIZARD_DIALOG =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/CreateVaultWizardDialog.kt"

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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }

        const val ROOT_SEARCH_DEPTH = 6
    }
}