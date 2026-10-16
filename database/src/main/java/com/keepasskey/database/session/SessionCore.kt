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

    /**
     * `ISSUE-P1-631`：最后确认持久化版本的整库密文快照（漂移合并的真实共同基线）。
     *
     * 写入点全部位于会话 Mutex 内的**确认写盘**收尾（打开 / 重载 / 新建 / 保存 /
     * Meta 提交 / 凭据轮换）或会话终止路径；读取经 [DatabaseSession.persistedBaselineSnapshot]。
     * 快照是整库密文的独立副本——与序列化缓冲同属会话派生敏态数据，会话终止（换库前置释放 /
     * 锁定 / 关闭）时必须**先清零再置空**，不得留给 GC。
     */
    private var persistedBaseline: ByteArray? = null

    private var persistedBaselineRevisionValue: Long = 0

    private var persistedBaselinePathValue: String? = null

    /**
     * 登记 / 替换持久化基线（确认写盘成功时调用；[bytes] 为 null 表示本次写盘未留存，
     * 旧基线一并作废）。内部克隆 [bytes]：调用方随后对原数组的清零不影响基线。
     *
     * 修订单调前移：登记、清除都会 +1——「读取基线（含修订）→ 计算合并 → 提交」窗口内的
     * 任意持久化或会话变化都能被 `adoptDatabaseIfUnchanged(expectedPersistedBaselineRevision)`
     * 检出（宁可误中止，不可静默以过期基线提交）。
     *
     * **异常安全（先克隆后替换）**：克隆在**触碰旧值之前**完成——若克隆失败（如 OOM），
     * 旧基线保持**原值与原修订**（状态仍是自洽的既有登记），绝不会留下「已被清零、但修订未
     * 前移」的半损坏状态（那会让下游读到全零字节却误认为仍是有效基线）。
     */
    fun retainPersistedBaseline(bytes: ByteArray?, pathIdentifier: String?) {
        val replacement = bytes?.clone()
        persistedBaseline?.fill(0)
        persistedBaseline = replacement
        persistedBaselinePathValue = pathIdentifier
        persistedBaselineRevisionValue += 1
    }

    /** 会话终止 / 换库前置释放：先清零快照再置空（修订同样前移，令在途校验失效） */
    fun clearPersistedBaseline() {
        persistedBaseline?.fill(0)
        persistedBaseline = null
        persistedBaselinePathValue = null
        persistedBaselineRevisionValue += 1
    }

    /** 基线密文快照的**克隆**（无基线返回 null）；调用方拥有并负责用毕清零 */
    fun persistedBaselineClone(): ByteArray? = persistedBaseline?.clone()

    fun persistedBaselineRevision(): Long = persistedBaselineRevisionValue

    fun persistedBaselinePathIdentifier(): String? = persistedBaselinePathValue

    /**
     * 仅供测试：暴露内部快照**引用本身**（非克隆）——用于断言「清除 / 替换后旧缓冲是否
     * 已被就地清零」（克隆会给到副本，无法观测内部缓冲的擦除）。
     * 生产代码不得调用；用例不得对被暴露数组做写入。
     */
    @androidx.annotation.VisibleForTesting
    internal fun persistedBaselineInternalBytesForTesting(): ByteArray? = persistedBaseline
}
