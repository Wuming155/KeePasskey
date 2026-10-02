package com.keepasskey.app.security

import android.content.Context
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话开合跨进程标记（ISSUE-P3-438）。
 *
 * 区分「上次进程死亡时密码库会话仍处于打开态」与「用户主动锁库 / 超时锁定已正常收尾」：
 * 前者（系统回收 / 崩溃 / force-stop）在下次冷启动于解锁页呈现一次性轻提示，
 * 让「为什么需要重输主密码」可解释（对齐 kp2a `AppKilledInfo` 语义）。
 *
 * 判定信号**复用既有** [DatabaseSession.state] 进程级状态机（`OPENED` / `DIRTY` = 会话打开；
 * `LOCKED` / `CLOSED` = 已正常收尾）——主动锁库、超时锁定、熄屏熔断全部经 `lock()`
 * 把状态推回 `LOCKED`，天然与「进程死亡时仍打开」区分开。跨进程只持久化**一个布尔**
 * （无任何敏感数据；同型先例：`ClipboardSecurityManager` 的跨进程待清标记）。
 *
 * 时序契约：[initialize] 必须在进程唯一冷启动点（`MainApplication.onCreate`）**同步**
 * 取走持久化值——`state` 是 `StateFlow`，采集器注册即收到首值（`CLOSED`）并覆写持久层；
 * 若先启动采集后取值，「异常关闭」事实会在任何 ViewModel 消费之前被首值冲掉。
 * 取走后的「待消费」事实驻留本单例内存，由 [consumeAbnormalClose] 一次性交付。
 */
@Singleton
class SessionCloseMarker @Inject constructor(
    @ApplicationContext context: Context,
    private val databaseSession: DatabaseSession
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var initialized = false

    /** 待消费的「上次异常关闭」事实（进程内存态，消费一次即清） */
    private var pendingAbnormalClose = false

    /**
     * 进程级冷启动初始化（幂等）：同步取走持久化标记，再启动会话态采集。
     * 由 `MainApplication.onCreate`（主线程、唯一冷启动点）调用——启动采集前取值
     * 保证 StateFlow 首值覆写不吞掉上次进程死亡时的开合事实。
     */
    fun initialize() {
        if (initialized) return
        initialized = true
        pendingAbnormalClose = prefs.getBoolean(KEY_SESSION_OPEN, false)
        prefs.edit().putBoolean(KEY_SESSION_OPEN, false).apply()
        scope.launch {
            databaseSession.state.collect { state ->
                prefs.edit().putBoolean(KEY_SESSION_OPEN, isOpenState(state)).apply()
            }
        }
    }

    /**
     * 一次性消费「上次会话未正常关闭」事实：true = 上次进程死亡时会话仍处于打开态。
     * 幂等——第二次调用恒为 false（解锁页据此只呈现一次）。
     */
    fun consumeAbnormalClose(): Boolean {
        if (!pendingAbnormalClose) return false
        pendingAbnormalClose = false
        return true
    }

    companion object {
        /**
         * 会话态 → 「会话打开」映射（纯函数，JVM 单测直测）：
         * `OPENED` / `DIRTY` = 打开；`LOCKED` / `CLOSED` = 已正常收尾。
         */
        fun isOpenState(state: DatabaseSession.SessionState): Boolean = when (state) {
            DatabaseSession.SessionState.OPENED,
            DatabaseSession.SessionState.DIRTY -> true

            DatabaseSession.SessionState.LOCKED,
            DatabaseSession.SessionState.CLOSED -> false
        }

        private const val PREFS_NAME = "session_close_marker"
        private const val KEY_SESSION_OPEN = "session_open_at_last_death"
    }
}
