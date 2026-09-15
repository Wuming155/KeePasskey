package com.keepasskey.app.ui.screens.detail

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-58（审计 RUST-03）AC④ 的**第二调用点接线检查**（静态源码比对）。
 *
 * 覆盖面说明（如实声明）：
 * - `SettingsHealthController`（整库扫描）由 `HealthScanOffMainThreadTest` 以**真实运行现场**
 *   断言（记录仓库被调用时的线程名必须落在 `DefaultDispatcher-worker-*`）；
 * - `EntryDetailRevealController.decryptPasswordForDisplay`（单条解密 + 原生强度内核估算）
 *   需要 Android 侧 ViewModel/仓库装配，JVM 侧难以构造等价现场，故此处沿用仓库既有静态接线
 *   检查先例（`CredentialProviderIntegrityWiringTest` / `ObscuredTouchWiringTest`），
 *   守护「解密 + 熵估算整段被 `withContext(Dispatchers.Default)` 包裹」这一不变式——
 *   该段一旦回到主线程裸执行，本用例立即失败。
 */
class EntryRevealEntropyOffMainThreadWiringTest {

    @Test
    fun `详情页解密与熵估算在 Default 派发器执行`() {
        val source = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailRevealController.kt"
        )

        // 断言 `withContext(Dispatchers.Default)` 块内同时包含仓库解密读取与熵估算两个语句，
        // 且两者位于同一 withContext 作用域内（用非贪婪跨行匹配锁定相邻关系）
        val guarded = Regex(
            """withContext\(Dispatchers\.Default\)\s*\{[\s\S]{0,400}?getEntryPasswordChars\(entryId\)[\s\S]{0,200}?PasswordEntropyEstimator\.estimateBits\("""
        ).containsMatchIn(source)

        assertTrue(
            "详情页「解密读取 + 熵估算（原生强度内核）」必须整段置于 withContext(Dispatchers.Default) 内",
            guarded
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
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
