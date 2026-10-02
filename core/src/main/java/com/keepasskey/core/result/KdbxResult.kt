package com.keepasskey.core.result

/**
 * 跨模块统一结果类型，遵循函数式错误处理规范，向 UI 隐藏裸异常堆栈。
 */
sealed interface KdbxResult<out T> {

    data class Success<out T>(val data: T) : KdbxResult<T>

    /**
     * @param error 底层异常。**只进日志**：其 `message` 属不可信外部输入（可能携带主机地址、
     *   路径、协议细节等敏感标识，ISSUE-P1-10 / ZT-10），绝不上浮 UI。
     * @param userText **已本地化**的用户文案（app 层专用，通常来自 `strings.get(R.string.xxx)`）。
     *   null 表示「本机读不出文案，由 UI 侧按 [code] 映射资源」——下层模块（core / database）
     *   拿不到 app 字符串资源，**一律**走这条路。
     * @param code 类型化错误码（稳定 ASCII 常量，见 [KdbxError]）。ISSUE-P3-453：此前下层模块把
     *   用户可见提示写成硬编码中文塞进第二参（如「保存数据库失败: …」），导致英文界面残留中文，
     *   且 `${t.message}` 把异常细节一并透出 UI。收敛后下层只产错误码，文案由 app 层映射。
     */
    data class Failure(
        val error: Throwable,
        val userText: String? = null,
        val code: String = KdbxError.UNKNOWN
    ) : KdbxResult<Nothing> {
        /**
         * ISSUE-P1-10 (ZT-10)：未显式提供文案时，**不得**回退为裸异常 message——
         * 那属不可信外部输入。此处回退的是**错误码**（机器标识，非文案）：
         *
         * **本属性已退役（ISSUE-P3-453），禁止再用于 UI。**
         * - UI 面：app 层用 `failure.uiTextArg`（经 `KdbxErrorTexts` 映射的 `@StringRes` 参数）；
         * - 日志面：直接读 `failure.error`（异常细节只在此出现）。
         *
         * 保留为 `DeprecationLevel.ERROR` 而非删除，是为了让**每一处**遗留消费点在编译期暴露，
         * 而不是静默继续把错误码（或旧版硬编码中文）拼进用户可见提示。
         */
        @Deprecated(
            message = "ISSUE-P3-453：Failure.message 不得上 UI——UI 面改用 app 层扩展 " +
                "failure.uiTextArg（@StringRes 参数），日志面读 failure.error",
            replaceWith = ReplaceWith("code"),
            level = DeprecationLevel.ERROR
        )
        val message: String
            get() = userText ?: code
    }

    val isSuccess: Boolean
        get() = this is Success

    val isFailure: Boolean
        get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw error
    }

    fun onSuccess(action: (value: T) -> Unit): KdbxResult<T> {
        if (this is Success) action(data)
        return this
    }

    /** ISSUE-P3-453：回调第二参是**错误码**（[Failure.code]），不再是可直出 UI 的文案。 */
    fun onFailure(action: (exception: Throwable, code: String) -> Unit): KdbxResult<T> {
        if (this is Failure) action(error, code)
        return this
    }

    fun <R> map(transform: (value: T) -> R): KdbxResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    companion object {
        inline fun <T> runCatching(block: () -> T): KdbxResult<T> {
            return try {
                Success(block())
            } catch (t: Throwable) {
                Failure(t)
            }
        }
    }
}
