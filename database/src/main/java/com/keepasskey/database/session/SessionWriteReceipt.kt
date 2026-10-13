package com.keepasskey.database.session

import kotlinx.coroutines.CancellationException

/**
 * 写出通道的同步提交证据。回执不含凭据、字节或文件路径。
 *
 * writer 在实际替换完成处调用 [committed]，不能等切回调用调度器后才通知。
 * 非原子通道在首次可能改变目标之前调用 [writing]；随后异常而未确认提交，视为结果不确定。
 * 仅能证明目标未改变的 writer 可以调用 [notCommitted]。提交证据不可被后续异常回退。
 */
class SessionWriteReceipt {
    enum class Status { NOT_STARTED, WRITING, NOT_COMMITTED, COMMITTED, UNKNOWN }

    @Volatile
    var status: Status = Status.NOT_STARTED
        private set

    @Synchronized
    fun writing() {
        if (status == Status.NOT_STARTED) status = Status.WRITING
    }

    @Synchronized
    fun committed() {
        status = Status.COMMITTED
    }

    @Synchronized
    fun notCommitted() {
        if (status != Status.COMMITTED && status != Status.UNKNOWN) status = Status.NOT_COMMITTED
    }

    @Synchronized
    fun uncertain() {
        if (status != Status.COMMITTED) status = Status.UNKNOWN
    }

    /** 返回取消也可能发生在已提交之后；只按同步证据分类。 */
    internal fun failed() {
        if (status == Status.WRITING) uncertain()
    }
}

/** 已提交轮换的取消回执；调用方仍须传播取消，并按新凭据完成同步与陈旧封印清理。 */
class CredentialRotationCancellationException(
    val writeStatus: SessionWriteReceipt.Status,
    cause: CancellationException
) : CancellationException("主凭据轮换等待已取消") {
    init {
        initCause(cause)
    }
}
