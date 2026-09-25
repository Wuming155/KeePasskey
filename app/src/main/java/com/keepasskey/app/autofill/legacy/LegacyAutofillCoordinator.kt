package com.keepasskey.app.autofill.legacy

import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旧版无障碍通道「选择器 → 服务」的单例桥（ISSUE-P3-324）。
 *
 * 回填必须由服务执行（只有它持有无障碍节点操作能力），而条目选择发生在选择器 Activity；
 * 本桥把「用户已选中并确认的凭据」从选择器安全地递交给服务，与框架通道
 * 「经 `EXTRA_AUTHENTICATION_RESULT` 回传、框架回调填充」等价——无框架可借，故自建握手。
 *
 * 生命周期与敏感性（对齐 `AutofillPickerViewModel.Credentials` 的既有口径）：
 * - 载荷只以**单个**待决状态存在，服务消费即清除；选择器覆盖写入（再次选择以最新为准）；
 * - 不落任何持久化、不进日志（[PendingFill.toString] 已脱敏）；
 * - 服务侧在消费前后双重复核库锁定态，锁定即丢弃（凭据来源于已解锁会话）；
 * - String 承载是 `ACTION_SET_TEXT` API 的硬约束（只接受 CharSequence），不可擦除 String
 *   的既有缺口与 ISSUE-P2-15 同源——生命周期被压缩到「提交 → 消费 → 回填」一段。
 */
@Singleton
class LegacyAutofillCoordinator @Inject constructor() {

    data class PendingFill(
        val targetPackage: String,
        val username: String,
        val password: String
    ) {
        /**
         * 覆写默认 `toString()`——本类型同时持有明文口令与用户名，默认实现会把两者
         * 整份展开（一次日志 / 异常插值即泄漏）。仅呈现包名与长度，内容一律不物化。
         */
        override fun toString(): String =
            "PendingFill(targetPackage=$targetPackage, username=<redacted len=${username.length}>, " +
                "password=<redacted len=${password.length}>)"
    }

    private val _pending = MutableStateFlow<PendingFill?>(null)

    /** 待决回填载荷（null = 无）；服务侧观察并在非空时消费 */
    val pending: StateFlow<PendingFill?> = _pending.asStateFlow()

    /**
     * 选择器侧提交（用户已在受保护窗口显式选中条目并完成确认）。
     * 新提交覆盖旧载荷（陈旧的未消费载荷随之作废，绝不让上一次的凭据填进下一次）。
     */
    fun submit(fill: PendingFill) {
        _pending.value = fill
    }

    /**
     * 服务侧消费（取走并清除）。单消费者（服务内单协程顺序收集），取走即置空；
     * 即便出现理论上的双读，也只是读到 null 而非重复回填——fail-safe 方向正确。
     */
    fun consume(): PendingFill? {
        val current = _pending.value ?: return null
        _pending.value = null
        return current
    }

    /** 丢弃未决载荷（服务销毁 / 库锁定时兜底，杜绝陈旧明文跨窗口滞留） */
    fun clear() {
        if (_pending.value != null) {
            AppLog.i(TAG, "旧版通道待决回填载荷已丢弃（销毁或锁定兜底）")
        }
        _pending.value = null
    }

    private companion object {
        const val TAG = "LegacyAutofill"
    }
}
