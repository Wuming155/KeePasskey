package com.keepasskey.core.result

/**
 * 跨模块统一结果类型，遵循函数式错误处理规范，向 UI 隐藏裸异常堆栈。
 */
sealed interface KdbxResult<out T> {

    data class Success<out T>(val data: T) : KdbxResult<T>

    data class Failure(
        val error: Throwable,
        val userMessage: String? = null
    ) : KdbxResult<Nothing> {
        /**
         * ISSUE-P1-10 (ZT-10)：未显式提供 userMessage 时一律回退为固定通用文案——
         * 裸异常 message 属不可信外部输入（可能携带主机地址、路径、协议细节等敏感标识），
         * 绝不直接上浮 UI。需要具体原因时由调用方显式构造 userMessage。
         */
        val message: String
            get() = userMessage ?: DEFAULT_USER_MESSAGE
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

    fun onFailure(action: (exception: Throwable, message: String) -> Unit): KdbxResult<T> {
        if (this is Failure) action(error, message)
        return this
    }

    fun <R> map(transform: (value: T) -> R): KdbxResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }

    companion object {
        private const val DEFAULT_USER_MESSAGE = "未知错误"

        inline fun <T> runCatching(block: () -> T): KdbxResult<T> {
            return try {
                Success(block())
            } catch (t: Throwable) {
                Failure(t)
            }
        }
    }
}
