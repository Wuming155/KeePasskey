package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步进阶偏好读取与详细日志开关（ISSUE-P3-25 拆分，纯搬运）。
 *
 * 依赖倒置落点：app 层偏好类型（[ExtendedSettings]）只在 app 模块内流转，
 * `sync` 模块仅经 [com.keepasskey.sync.network.SyncTransferOptions] 纯数据契约感知偏好。
 */
@Singleton
class SyncPreferences @Inject constructor(
    private val debugLog: DebugLogBuffer,
    /**
     * ISSUE-P3-03 (43a)：进阶同步偏好源（分块上传、上传前远端比对、冲突策略、详细日志）。
     * 可空 + 默认 null 仅为保持既有单测构造点兼容——null 时全部按 [ExtendedSettings] 默认值
     * 处理（即接线前的行为），生产路径由 Hilt 注入真实偏好源。
     */
    private val extendedSettingsStore: ExtendedSettingsStore? = null
) {

    /** 当前生效的进阶偏好（无偏好源时回落默认值，语义等价于接线前的固定行为）。 */
    fun currentSettings(): ExtendedSettings = extendedSettingsStore?.load() ?: ExtendedSettings()

    /**
     * ISSUE-P3-03 (43f)：详细同步日志——仅在用户开启「详细日志模式」时追加过程细节。
     *
     * 依赖关系说明（与设置页文案一致）：日志总开关（诊断日志）独立生效，本模式是
     * **叠加层**——总开关关闭时缓冲整条丢弃，详细模式不会单方面恢复记录。
     */
    fun verbose(settings: ExtendedSettings, message: String) {
        if (settings.verboseSyncLog) debugLog.debug(SYNC_LOG_TAG, message)
    }
}
