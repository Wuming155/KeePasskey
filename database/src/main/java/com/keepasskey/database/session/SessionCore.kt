package com.keepasskey.database.session

import com.keepasskey.database.file.KdbxDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * 会话可变状态容器（ISSUE-P3-31 批次 D 结构拆分）。
 *
 * 把原先内联在 [DatabaseSession] 中的可变状态（两条 StateFlow、活动文件 / 路径 / 写盘通道、
 * 只读标志）收敛到一个 internal 容器，供 [DatabaseSession] 门面与各协作类共享。
 * 字段与拆分前的 `_state` / `_database` / `activeFile` / `activePathIdentifier` /
 * `saveWriter` / `readOnlyMode` 一一对应，语义不变。
 */
internal class SessionCore {

    /** 仅在会话 mutex 内更新；受理方只读已发布的不可变票据。 */
    @Volatile
    var credentialTicket: SessionCredentialTicket? = null
        private set

    fun establishSessionTicket(pathIdentifier: String) {
        credentialTicket = SessionCredentialTicket(pathIdentifier)
    }

    fun invalidateSessionTicket() { credentialTicket = null }

    /** 接管预先分配的凭据代次，提交收尾本身不分配票据。 */
    fun adoptCredentialTicket(ticket: SessionCredentialTicket?) {
        credentialTicket = ticket
    }

    fun advanceCredentialTicket(): SessionCredentialTicket? =
        credentialTicket?.next().also { credentialTicket = it }

    /** 会话状态机（对应拆分前的 `_state`）。 */
    val state = MutableStateFlow(DatabaseSession.SessionState.CLOSED)

    /** 内存中的活动数据库模型（对应拆分前的 `_database`）。 */
    val database = MutableStateFlow<KdbxDatabase?>(null)

    /** 活动本地文件（对应拆分前的 `activeFile`）。 */
    var activeFile: File? = null

    /** 活动路径标识（对应拆分前的 `activePathIdentifier`）。 */
    var activePathIdentifier: String? = null

    /** 保存时的二进制写出通道（对应拆分前的 `saveWriter`）。 */
    var saveWriter: (suspend (ByteArray) -> Unit)? = null

    /** 凭据轮换的提交证据通道；普通 File 在真实替换点回报，流式通道声明写入与确认边界。 */
    var trackedSaveWriter: (suspend (ByteArray, SessionWriteReceipt) -> Unit)? = null

    /** 写入结果不确定时阻止继续覆盖；仅成功重新打开/新建或显式关闭会话后解除。 */
    val credentialWriteUncertain = MutableStateFlow(false)

    /** 只读模式（对应拆分前的 `readOnlyMode`）。 */
    var readOnlyMode: Boolean = false
}
