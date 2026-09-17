package com.keepasskey.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「已解锁常驻通知」的**偏好触发路径接线守卫**（ISSUE-P3-152 ①）。
 *
 * 缺陷背景：控制器原以 **2 秒**周期调 `settingsStore.load()`（逐 key 读约 50 项
 * `SharedPreferences` + 构造整个 `ExtendedSettings`）刷新目标态；而
 * `ExtendedSettingsStore` 自 ISSUE-P2-21 起已持有**进程级唯一内存权威快照**
 * `settings: StateFlow<ExtendedSettings>`（设置页每个写入点都 `publish + save`）——
 * 轮询既非必要、又是长期驻留的常态成本。
 *
 * 本类沿用仓库既有静态接线检查先例（`ObscuredTouchWiringTest` /
 * `CredentialProviderIntegrityWiringTest` / `EntryRevealEntropyOffMainThreadWiringTest`）：
 * 该控制器依赖 `NotificationManagerCompat` 与真实 `Context`，JVM 侧无法构造等价运行现场，
 * 故以源码级不变式守护两件事——**不得回到定时轮询**、**必须订阅内存快照**。
 */
class UnlockedNotificationWiringTest {

    private val source: String by lazy { readSource(CONTROLLER_PATH) }

    /**
     * **剥离注释后**的源码：负向断言（「不得出现 X」）基于此判定。
     *
     * 必要性：本文件 KDoc 为解释收敛动机**刻意引用了旧写法**（`settingsStore.load()`、
     * `PREFERENCE_REFRESH_INTERVAL_MS`），若直接在全文上做「不得包含」断言，说明性文字会把
     * 守卫判成失败。局限（如实声明）：这是文本级剥离，字符串字面量中的 `//`（如 URL）
     * 可能连带截断本行余下内容——只影响「不得出现」一侧，最坏是漏判，不会误判。
     */
    private val code: String by lazy {
        source
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
            .replace(Regex("//[^\n]*"), " ")
    }

    @Test
    fun `偏好来源为进程级快照订阅而非定时轮询`() {
        assertTrue(
            "必须订阅 ExtendedSettingsStore 的进程级内存快照（settings: StateFlow）作为偏好变更触发源",
            code.contains("settingsStore.settings.map")
        )
        assertTrue(
            "偏好读取必须走内存快照，不得每次重新读 SharedPreferences",
            code.contains("settingsStore.settings.value.showUnlockedNotification")
        )
        assertFalse(
            "不得再调用逐 key 读取持久化偏好的 load()",
            code.contains("settingsStore.load()")
        )
    }

    @Test
    fun `不得保留任何定时轮询痕迹`() {
        assertFalse(
            "定时轮询已被删除，不应再出现 delay( 的周期节拍",
            code.contains("delay(")
        )
        assertFalse(
            "轮询间隔常量应一并删除（原 PREFERENCE_REFRESH_INTERVAL_MS = 2_000L）",
            code.contains("PREFERENCE_REFRESH_INTERVAL")
        )
        assertFalse(
            "不得以 flow { while (true) … } 形式重新引入自旋式轮询",
            code.contains("while (true)")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CONTROLLER_PATH =
            "app/src/main/java/com/keepasskey/app/notification/UnlockedNotificationController.kt"
        const val ROOT_SEARCH_DEPTH = 4

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
    }
}
