package com.keepasskey.app.di

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「会话终止观察者装配清单」一致性检查（静态源码比对）。
 *
 * 这一类防护的失效形态是**装配被静默移除**：观察者实现完好、行为单测全绿，但
 * `DatabaseModule` 不再注册它 —— 锁定 / 换库时实际不会清理。JVM 侧无法启动 Hilt 图，
 * 故沿用仓库既有静态接线检查先例（`ObscuredTouchWiringTest` / `CredentialProviderIntegrityWiringTest`）。
 *
 * 守护的清单（每条均有独立缺陷编号）：
 * - `SyncCacheEvictor`：ISSUE-P1-07（`cacheDir/sync` 密文快照随锁定销毁）
 * - `FileBinaryStore`：ISSUE-P2-24（大附件明文缓存随锁定清空）
 * - `ClipboardSecurityManager`：ISSUE-P2-51（敏感剪贴板值随锁定清空）
 * - `AutofillLastFilledStore`：ISSUE-P3-109（上次填充记忆随锁定 / 换库清除）
 */
class DatabaseModuleLockObserversWiringTest {

    @Test
    fun `会话终止观察者全部完成装配`() {
        val source = readSource("app/src/main/java/com/keepasskey/app/di/DatabaseModule.kt")

        val required = listOf(
            "addLockObserver(cacheEvictor)",
            "addLockObserver(binaryStore)",
            "addLockObserver(clipboardSecurityManager)",
            "addLockObserver(autofillLastFilledStore)"
        )
        val missing = required.filterNot { source.contains(it) }
        assertTrue("DatabaseModule 未注册以下会话终止观察者：$missing", missing.isEmpty())
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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        /** 自工作目录向上回溯的层数（app 模块测试工作目录为 app/，1 层即仓库根） */
        const val ROOT_SEARCH_DEPTH = 4
    }
}
