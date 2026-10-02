package com.keepasskey.app.security

import com.keepasskey.app.testutil.stripCommentsOnly
import com.keepasskey.database.session.DatabaseSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-438：会话开合跨进程标记的语义锁定。
 *
 * 断言分两层：
 * 1. **行为层**（[SessionCloseMarker.isOpenState] 纯函数）：`OPENED` / `DIRTY` 判「会话打开」，
 *    `LOCKED` / `CLOSED` 判「已正常收尾」——主动锁库 / 超时锁定 / 熄屏熔断全部经 `lock()`
 *    推回 `LOCKED`，与「进程死亡时会话仍打开」在信号层即区分开（AC①②）；
 * 2. **接线层**（源码守卫）：冷启动取走持久值必须先于会话态采集器启动——`state` 是
 *    StateFlow，采集器注册即收到首值（`CLOSED`）并覆写持久层；若顺序颠倒，
 *    「上次异常关闭」事实会在任何消费之前被冲掉（时序契约见 `SessionCloseMarker` KDoc）。
 *
 * 注：`SharedPreferences` / `DatabaseSession` 在宿主 JVM 桩上不可构造（本仓不引入 Robolectric /
 * mock 框架），故消费侧一次性语义（`consumeAbnormalClose` 幂等）由源码守卫与真机走查覆盖。
 */
class SessionCloseMarkerTest {

    // ---------------------------------------------------------------- 行为层

    @Test
    fun `打开与脏状态判为会话打开`() {
        assertTrue(SessionCloseMarker.isOpenState(DatabaseSession.SessionState.OPENED))
        assertTrue(SessionCloseMarker.isOpenState(DatabaseSession.SessionState.DIRTY))
    }

    @Test
    fun `锁定与关闭判为已正常收尾`() {
        assertFalse(SessionCloseMarker.isOpenState(DatabaseSession.SessionState.LOCKED))
        assertFalse(SessionCloseMarker.isOpenState(DatabaseSession.SessionState.CLOSED))
    }

    // ---------------------------------------------------------------- 接线层（源码守卫）

    @Test
    fun `冷启动取走持久值先于会话态采集器启动`() {
        val body = stripCommentsOnly(readSource(MARKER_SOURCE))
        val initializeStart = body.indexOf("fun initialize()")
        val collectStart = body.indexOf("databaseSession.state.collect")
        val pendingCapture = body.indexOf("pendingAbnormalClose = prefs.getBoolean")
        assertTrue(
            "[$MARKER_SOURCE] 未找到 initialize() 函数（是否被改名/移动）",
            initializeStart >= 0
        )
        assertTrue(
            "[$MARKER_SOURCE] 未找到会话态采集器（是否被改名/移动）",
            collectStart >= 0
        )
        assertTrue(
            "[$MARKER_SOURCE] 未找到持久值同步取走语句（是否被改名/移动）",
            pendingCapture >= 0
        )
        assertTrue(
            "[$MARKER_SOURCE] 时序契约被破坏：必须在 initialize() 内先取走持久值" +
                "（pendingAbnormalClose = prefs.getBoolean…），再启动 state 采集器——" +
                "StateFlow 首值（CLOSED）会覆写持久层，顺序颠倒会吞掉「上次异常关闭」事实",
            pendingCapture in initializeStart until collectStart
        )
    }

    @Test
    fun `冷启动接线挂进程唯一初始化点`() {
        val source = stripCommentsOnly(readSource(APP_SOURCE))
        assertTrue(
            "[$APP_SOURCE] MainApplication.onCreate 必须同步调用 sessionCloseMarker.initialize()" +
                "（时序契约：早于任何 ViewModel 消费，见 SessionCloseMarker KDoc）",
            source.contains("sessionCloseMarker.initialize()")
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被改名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val MARKER_SOURCE =
            "app/src/main/java/com/keepasskey/app/security/SessionCloseMarker.kt"
        const val APP_SOURCE = "app/src/main/java/com/keepasskey/app/MainApplication.kt"
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
