package com.keepasskey.database.session

import com.keepasskey.database.exception.KdbxSessionChangedException
import kotlinx.coroutines.currentCoroutineContext

/**
 * 只能在实际改树/写盘的会话 mutex 内调用；不另取锁，避免嵌套重入。
 *
 * 无请求上下文时放行是**有意的内部入口契约**：同步合并、导入续用、会话自检等路径由
 * 调用方在受理点自行固定票据或另做身份校验。普通用户写路径必须在受理点经
 * [withAcceptedRequest] 传播请求，不能依赖本函数的缺省放行；也不得把缺省改为拒绝——
 * 那会让上述内部路径全部失败。
 */
internal suspend fun requireCurrentSessionRequest(current: SessionCredentialTicket?) {
    val request = currentCoroutineContext()[SessionCredentialRequest] ?: return
    if (request.ticket == null || request.ticket !== current) throw KdbxSessionChangedException()
}
