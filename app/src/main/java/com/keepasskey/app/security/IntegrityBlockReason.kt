package com.keepasskey.app.security

import androidx.annotation.StringRes
import com.keepasskey.app.R

/**
 * 生物识别快速解锁被完整性闸门拦下时的**具体命中信号**（ISSUE-P2-227）。
 *
 * ## 为什么需要它
 *
 * 整改前只有一句「设备存在安全风险，已禁用生物识别快速解锁」，用户无法区分
 * 「本机跑的正是可调试构建」（开发者自测的必然结果）与「设备真被 root / 注入框架攻破」
 * （需立即处置）——两者混为一谈既误导也丧失可信度。**本枚举只改归因呈现，不改判据**：
 * 风险分级与 fail-closed 后果完全沿用 [RuntimeIntegrityPolicy.evaluate]。
 *
 * ## 声明顺序即危害度顺序
 *
 * 从「明确动态攻击特征」→「静态可疑特征」→「扫描未判定」排列；消费侧取 [from] 返回清单的
 * 首项即为最应告知用户的一项（解锁提示点名首项，完整清单在设置页安全分区呈现）。
 *
 * ## 文案口径（沿用 ISSUE-P1-10 与 [com.keepasskey.app.passkey.CredentialRejectionReason]）
 *
 * 每项只映射一条**预定义、无插值**的字符串资源。可调试构建与侧载渠道在安装方声明层面
 * 无法区分真假（任何应用都能自称某个 installer 包名），把 installer 原文放进文案反而误导，
 * 故一律不插值。
 */
enum class IntegrityBlockReason(@StringRes val messageRes: Int) {

    /** 调试器正附加在本进程（`Debug.isDebuggerConnected()` / `waitingForDebugger()`） */
    DEBUGGER_ATTACHED(R.string.sec_biometric_block_debugger),

    /** 本进程正被其他进程 `ptrace`（`/proc/self/status` 的 `TracerPid > 0`，ISSUE-P3-83） */
    BEING_TRACED(R.string.sec_biometric_block_traced),

    /** 注入框架痕迹（`/proc/self/maps` 特征串或 Frida 服务端落点，ISSUE-P3-120 判据） */
    HOOK_FRAMEWORK(R.string.sec_biometric_block_hook),

    /** Root 二进制 / Superuser 落点存在 */
    ROOT_ARTIFACTS(R.string.sec_biometric_block_root),

    /** Magisk 痕迹路径存在 */
    MAGISK(R.string.sec_biometric_block_magisk),

    /** 安装包带 `FLAG_DEBUGGABLE`（debug 构建） */
    DEBUGGABLE_BUILD(R.string.sec_biometric_block_debuggable),

    /** 安装来源可判定且不在受信任清单内 */
    UNTRUSTED_INSTALLER(R.string.sec_biometric_block_installer),

    /** 首次扫描未完成或快照已陈旧：按保守策略禁用，但**并未**判定出任何风险 */
    SCAN_UNDETERMINED(R.string.sec_biometric_block_undetermined);

    companion object {

        /**
         * 信号集合 → 命中原因清单（**纯函数**，JVM 单测穷举）。
         *
         * 返回顺序即枚举声明顺序（危害度降序）；无任一命中时返回空清单，
         * 由消费侧回落到既有的通用文案（不得凭空造一个原因）。
         *
         * @param undetermined 快照是否处于「未判定 / 已陈旧」态——此时信号集往往全是默认 false，
         *   若不点名，用户看到的仍是笼统提示。
         */
        fun from(signals: IntegritySignals, undetermined: Boolean): List<IntegrityBlockReason> {
            if (undetermined) return listOf(SCAN_UNDETERMINED)
            return entries.filter { hit(it, signals) }
        }

        private fun hit(reason: IntegrityBlockReason, s: IntegritySignals): Boolean = when (reason) {
            DEBUGGER_ATTACHED -> s.debuggerAttached
            BEING_TRACED -> s.beingTraced
            HOOK_FRAMEWORK -> s.hookFrameworkDetected
            ROOT_ARTIFACTS -> s.rootArtifactsDetected
            MAGISK -> s.magiskDetected
            DEBUGGABLE_BUILD -> s.appDebuggable
            UNTRUSTED_INSTALLER -> s.untrustedInstallSource
            SCAN_UNDETERMINED -> false
        }
    }
}
