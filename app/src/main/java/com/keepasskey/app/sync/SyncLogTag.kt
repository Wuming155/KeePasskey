package com.keepasskey.app.sync

/**
 * 同步链路统一日志标签（ISSUE-P3-25 拆分）。
 *
 * 拆分前 `SyncCoordinator` 以内部 `TAG = "SyncCoordinator"` 记录整条同步链路的日志；
 * 拆出的协作方必须沿用同一字面量，否则既有按标签过滤的调试日志视图（设置页日志导出）
 * 会丢失事件、日志文本也不再与拆分前逐字一致。故收敛为单一常量，禁止就地复制字面量。
 */
internal const val SYNC_LOG_TAG = "SyncCoordinator"
