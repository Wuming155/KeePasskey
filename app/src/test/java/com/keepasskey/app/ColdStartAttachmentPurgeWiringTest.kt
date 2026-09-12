package com.keepasskey.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-13（P1）附件明文缓存「冷启动清理」接线守护（静态源码比对）。
 *
 * 本项缺陷的失效形态不是「判定错」，而是**接线被静默移除**：JVM 侧无法构造
 * `Application.onCreate` 与真实 `cacheDir`，`SyncCache` 的删除行为也无从在宿主单测里断言。
 * 故以源码文本比对守护三条不变式（仓库根定位法沿用
 * [com.keepasskey.app.log.LogHygieneTest]，为同源静态检查先例）：
 *
 * 1. `MainApplication` 经 Hilt 注入 `FileBinaryStore`（**复用既有单例**，不是并行实现）；
 * 2. `onCreate` 起始段（早于自动锁定守护初始化）同步调用 `fileBinaryStore.clear()`；
 * 3. `FileBinaryStore` **同时保留**会话锁定清理（`onSessionLocked` → `clear`），
 *    两层清理都不缺少，且清理失败经 `purgeAttachmentCache` 收敛（只记日志、不外抛）。
 */
class ColdStartAttachmentPurgeWiringTest {

    private val mainApplicationSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/MainApplication.kt")

    private val fileBinaryStoreSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/data/binary/FileBinaryStore.kt")

    @Test
    fun `MainApplication 注入既有 FileBinaryStore 单例`() {
        assertTrue(
            "MainApplication 必须 @Inject 既有 FileBinaryStore（禁止新建并行实现）",
            mainApplicationSource.contains("lateinit var fileBinaryStore: FileBinaryStore")
        )
    }

    @Test
    fun `冷启动在会话打开之前调用 clear`() {
        val source = mainApplicationSource
        val onCreateBody = source.substringAfter("override fun onCreate()")

        assertTrue(
            "冷启动清理必须在 Application.onCreate 中同步执行",
            onCreateBody.contains(CLEAR_CALL)
        )
        val clearIndex = onCreateBody.indexOf(CLEAR_CALL)
        val autoLockIndex = onCreateBody.indexOf("autoLockManager.initialize()")
        assertTrue(
            "冷启动清理应位于自动锁定守护初始化之前（早于任何会话入口）",
            autoLockIndex < 0 || clearIndex < autoLockIndex
        )
    }

    @Test
    fun `会话锁定清理两层并存`() {
        val source = fileBinaryStoreSource

        assertTrue(
            "会话锁定清理（SessionLockObserver）不得因新增冷启动清理而被替换掉",
            source.contains("override fun onSessionLocked() = clear()")
        )
        assertTrue(
            "两层清理须共用失败收敛策略 purgeAttachmentCache（失败只记脱敏日志、绝不外抛）",
            source.contains("purgeAttachmentCache(") &&
                source.contains("override fun clear()")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CLEAR_CALL = "fileBinaryStore.clear()"

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

        const val ROOT_SEARCH_DEPTH = 4
    }
}
