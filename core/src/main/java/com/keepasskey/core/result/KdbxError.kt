package com.keepasskey.core.result

/**
 * 类型化错误码（ISSUE-P3-453）。
 *
 * ## 为何存在
 *
 * 下层模块（`database` / `core`）**没有 UI 上下文、也拿不到 app 的字符串资源**，
 * 此前只能把用户可见提示写成硬编码中文塞进 `KdbxResult.Failure` 的第二参，例如
 * `Failure(t, "保存数据库失败: ${t.message}")`。后果有二：
 *
 * 1. **英文界面残留中文**——`UiMessage(R.string.op_failed, listOf(result.message))` 把它拼进
 *    「操作失败：%1$s」，前半句随语言切换、后半句永远是中文；
 * 2. **异常细节透出 UI**——`${t.message}` 可能携带文件路径 / 端点 / 主机地址
 *    （`ISSUE-P3-311` 同源红线），且随异常类型变化不可控。
 *
 * 收敛方式＝下层只产出**机器可读的错误码**（本文件的常量），由 app 层的
 * `KdbxErrorTexts` 映射为 `@StringRes` 资源文案；异常细节只留在 `Failure.error`（日志面）。
 *
 * ## 契约
 *
 * - 取值一律为 **ASCII 前缀 `kdbx.` + 点分路径**，便于 app 层按前缀识别并防回潮
 *   （`tools/audit/check_user_visible_cjk.py` 规则 A 会拦住任何中文字面量）。
 * - **不得**把错误码直接上浮 UI：它是机器标识，不是文案。
 * - 新增错误码时**必须**同时在 app 层 `KdbxErrorTexts` 补映射（zh/en 成对）；
 *   映射缺失时 app 层回落 `unknown`，属可观测的降级而非崩溃。
 */
object KdbxError {

    /** 未分类错误（默认码）。原 `DEFAULT_USER_MESSAGE = "未知错误"` 的本地化替代。 */
    const val UNKNOWN = "kdbx.unknown"

    // ---- 打开 / 创建（SessionOpener）----
    const val CREATE_FAILED = "kdbx.open.create_failed"
    const val UNLOCK_FAILED = "kdbx.open.unlock_failed"

    /** 文件损坏 / 结构校验失败（与凭据错误分型；`ISSUE-P2-521` 解锁页「从备份恢复」入口的判据）。 */
    const val UNLOCK_CORRUPT_FILE = "kdbx.open.corrupt_file"

    // ---- 保存 / 导出（SessionPersistence）----
    const val SAVE_READ_ONLY = "kdbx.save.read_only"
    const val SAVE_NO_WRITER = "kdbx.save.no_writer"
    const val SAVE_NO_DATABASE = "kdbx.save.no_database"
    const val SAVE_CREDENTIALS_LOST = "kdbx.save.credentials_lost"
    const val SAVE_FAILED = "kdbx.save.failed"

    const val EXPORT_NO_DATABASE = "kdbx.export.no_database"
    const val EXPORT_CREDENTIALS_LOST = "kdbx.export.credentials_lost"
    const val EXPORT_FAILED = "kdbx.export.failed"

    // ---- 主凭据变更（SessionPersistence.changeCredentials / changeKeyFileOnly）----
    const val CREDENTIALS_READ_ONLY = "kdbx.credentials.read_only"
    const val CREDENTIALS_NO_WRITER = "kdbx.credentials.no_writer"
    const val CREDENTIALS_NO_DATABASE = "kdbx.credentials.no_database"
    const val CREDENTIALS_CHANGE_FAILED = "kdbx.credentials.change_failed"
    const val CREDENTIALS_KEYFILE_ONLY_UNBIND = "kdbx.credentials.keyfile_only_unbind"

    // ---- 外部文件漂移处理（app/data/repository）----
    const val DRIFT_FILE_UNREADABLE = "kdbx.drift.file_unreadable"
    const val DRIFT_NO_ACTIVE_DATABASE = "kdbx.drift.no_active_database"
    const val DRIFT_SESSION_TREE_CHANGED = "kdbx.drift.session_tree_changed"

}
