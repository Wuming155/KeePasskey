package com.keepasskey.app.ui.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.R
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult

/**
 * ISSUE-P3-453：类型化错误码 → `@StringRes` 资源文案的唯一映射表。
 *
 * ## 背景
 *
 * 下层模块（`core` / `database`）没有 UI 上下文、拿不到 app 字符串资源，此前只能把用户可见
 * 提示写成硬编码中文塞进 `KdbxResult.Failure` 第二参（如 `"保存数据库失败: ${t.message}"`），
 * 经 `UiMessage(R.string.op_failed, listOf(result.message))` 拼进「操作失败：%1$s」后，
 * **前半句随语言切换、后半句永远是中文**。
 *
 * 收敛方式＝下层只产 [KdbxError] 常量（ASCII 机器标识），文案由本表映射到 `values-zh` /
 * `values-en` 成对资源；异常细节只留在 `KdbxResult.Failure.error`（日志面）。
 *
 * ## 契约（改本表前必读）
 *
 * - **每个 `KdbxError` 常量都必须在本表登记**，否则 UI 侧静默回落 [err_unknown]——那属于
 *   *可观测降级* 而非崩溃，但会让用户看到「未知错误」而丢失线索。
 *   防回潮单测 `KdbxErrorTextsTest` 会**遍历反射**校验一一覆盖（缺一个即红）。
 * - 与 `res/values` 下的 strings.xml **中英两版必须同批新增**，缺任一份会让对应语言
 *   回落到默认语言，等于没本地化。
 * - **不得**把错误码（`kdbx.` 前缀字符串）直接上浮 UI：它是机器标识，不是文案。
 */
object KdbxErrorTexts {

    /**
     * 错误码 → 资源 ID。
     *
     * 用 `Map` 而非 `when` 链式：单测要遍历反射校验覆盖完整性时，`when` 的分支不可枚举，
     * 而 `Map` 可直接比对 [KdbxError] 的 `declaredFields` 全集。
     */
    @Suppress("MapGetWithNotNullAssertion")
    private val TABLE: Map<String, Int> = mapOf(
        KdbxError.UNKNOWN to R.string.err_unknown,
        KdbxError.CREATE_FAILED to R.string.err_open_create_failed,
        KdbxError.UNLOCK_FAILED to R.string.err_open_unlock_failed,
        KdbxError.SAVE_READ_ONLY to R.string.err_save_read_only,
        KdbxError.SAVE_NO_WRITER to R.string.err_save_no_writer,
        KdbxError.SAVE_NO_DATABASE to R.string.err_save_no_database,
        KdbxError.SAVE_CREDENTIALS_LOST to R.string.err_save_credentials_lost,
        KdbxError.SAVE_FAILED to R.string.err_save_failed,
        KdbxError.EXPORT_NO_DATABASE to R.string.err_export_no_database,
        KdbxError.EXPORT_CREDENTIALS_LOST to R.string.err_export_credentials_lost,
        KdbxError.EXPORT_FAILED to R.string.err_export_failed,
        KdbxError.CREDENTIALS_READ_ONLY to R.string.err_credentials_read_only,
        KdbxError.CREDENTIALS_NO_WRITER to R.string.err_credentials_no_writer,
        KdbxError.CREDENTIALS_NO_DATABASE to R.string.err_credentials_no_database,
        KdbxError.CREDENTIALS_CHANGE_FAILED to R.string.err_credentials_change_failed,
        KdbxError.CREDENTIALS_KEYFILE_ONLY_UNBIND to R.string.err_credentials_keyfile_only_unbind,
        KdbxError.DRIFT_FILE_UNREADABLE to R.string.err_drift_file_unreadable,
        KdbxError.DRIFT_NO_ACTIVE_DATABASE to R.string.err_drift_no_active_database,
        KdbxError.DRIFT_SESSION_TREE_CHANGED to R.string.err_drift_session_tree_changed
    )

    /** 错误码 → 资源 ID；未登记/陌生错误码一律回落 [R.string.err_unknown]（可观测降级，非崩溃）。 */
    @StringRes
    fun resIdFor(code: String): Int = TABLE[code] ?: R.string.err_unknown
}

/**
 * [KdbxResult.Failure] 的**用户可见文案参数**（已本地化）。
 *
 * 优先用 app 层显式给的 [KdbxResult.Failure.userText]（NewType 场景已本地化），
 * 否则按 `code` 经 [KdbxErrorTexts] 映射——这条分支正是 ISSUE-P3-453 的落点：
 * 下层模块（core / database）走这条路，英文界面因此不再残留中文。
 *
 * 只作为参数传给 `UiMessage` / `strings.get(...)`，**不要**直接当整句展示。
 *
 * 写成 **`@Composable` 函数**而非属性：本仓 Kotlin 版本下顶层 `@Composable val`
 * （无 backing field）会被判「注解不适用于该目标」，而在 getter 上标 `@Composable`
 * 又会逼迫每个调用点进入组合上下文——文末那批调用点都在 `viewModelScope` 里，
 * 因此统一走函数形式：`result.textArg()`（组合内）/ `result.textArg(strings)`（注入侧）。
 */
@Composable
fun KdbxResult.Failure.textArg(): String =
    userText ?: stringResource(KdbxErrorTexts.resIdFor(code))

/**
 * 同 [textArg] 的注入版：给**非 Composable** 的 ViewModel / 控制器使用
 * （它们持有 [StringsProvider] 通道，可脱离组合直接取串）。
 *
 * 与无参版构成**同名重载**：调用点按是否持有通道自行选择，调用方不必引入第二个名字。
 */
fun KdbxResult.Failure.textArg(strings: StringsProvider): String =
    userText ?: strings.get(KdbxErrorTexts.resIdFor(code))
