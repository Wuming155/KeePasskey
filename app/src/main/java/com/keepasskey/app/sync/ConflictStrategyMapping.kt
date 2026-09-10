package com.keepasskey.app.sync

import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.sync.merge.SyncConflictStrategy

/**
 * 冲突解决策略的层间映射（ISSUE-P3-03 43a）。
 *
 * 依赖倒置落点：设置页的用户偏好枚举（[ConflictResolution]，含 `@StringRes` 标签，
 * 属 UI 语言）**不得**下沉到 `sync` 模块；由 app 层在此完成唯一一次映射，
 * `sync` 只认识自己的领域枚举 [SyncConflictStrategy]。
 *
 * 逐项语义对照（与设置页文案一致）：
 * - `AUTO_MERGE`（自动合并）→ `AUTO_MERGE`：字段级三方合并，无法合并时转手动；
 * - `PROMPT_USER`（每次询问）→ `PROMPT_USER`：双方各自修改过的条目全部交用户决策；
 * - `KEEP_REMOTE`（以云端为准）→ `KEEP_REMOTE`：冲突时采用云端版本；
 * - `KEEP_LOCAL`（以本地为准）→ `KEEP_LOCAL`：冲突时本地版本覆盖云端。
 */
internal fun ConflictResolution.toSyncStrategy(): SyncConflictStrategy = when (this) {
    ConflictResolution.AUTO_MERGE -> SyncConflictStrategy.AUTO_MERGE
    ConflictResolution.PROMPT_USER -> SyncConflictStrategy.PROMPT_USER
    ConflictResolution.KEEP_REMOTE -> SyncConflictStrategy.KEEP_REMOTE
    ConflictResolution.KEEP_LOCAL -> SyncConflictStrategy.KEEP_LOCAL
}
