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
internal class SessionCore(
    /**
     * `ENG-20261028-18`：实例级密文复制策略（生产默认标准克隆）。
     * 允许测试在受控的会话实例上注入合成 OutOfMemoryError 或复制故障，
     * 验证异常安全与作废收口，避免跨测试全局 mutable hook。
     */
    private val baselineCloner: (ByteArray) -> ByteArray = { it.clone() }
) {

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
     * Meta 提交 / 凭据轮换）或会话终止路径；登记失败时由
     * [invalidatePersistedBaselineAfterFailedRetain] 作废旧值（`ENG-20261028-18`）。
     * 读取经 [DatabaseSession.persistedBaselineSnapshot]。
     *
     * 快照属会话派生敏态数据，会话终止（换库前置释放 / 锁定 / 关闭）时必须**先清零再置空**，
     * 不得留给 GC。形态有两种登记入口：[retainPersistedBaseline] 交付**独立副本**，
     * [retainPersistedBaselineOwned] 则**接管调用方缓冲本体**（少一次整库拷贝，见其 KDoc）。
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
     * 旧基线保持**原值与原修订**，本方法如实抛出；调用方（`retainPersistedBaselineBestEffort`）
     * 随后必须作废旧基线，绝不留下「已成功写盘却仍指向旧版本」的可信基线。
     */
    fun retainPersistedBaseline(bytes: ByteArray?, pathIdentifier: String?) {
        val replacement = bytes?.let(baselineCloner)
        persistedBaseline?.fill(0)
        persistedBaseline = replacement
        persistedBaselinePathValue = pathIdentifier
        persistedBaselineRevisionValue += 1
    }

    /**
     * `ENG-20261028-18`：**所有权移交式**登记——会话接管 [bytes] 本体的所有权，调用方此后
     * **不得再读写或清零**该数组。
     *
     * 与 [retainPersistedBaseline] 的克隆形态相比，本入口不再产生第二份整库密文副本：
     * 保存链写盘成功后，那份缓冲的唯一用途就是充当基线，克隆它只换来一次整库分配与拷贝
     * （大库为百 MiB 量级）。移交后缓冲的清零点仍是「替换登记 / 清除 / 会话终止」，
     * 与克隆形态逐字相同，所有权契约不变。
     *
     * 本方法**不做分配**（引用赋值 + 旧缓冲就地清零），因此不会像克隆形态那样因内存不足抛出；
     * [bytes] 为 null 时与 [retainPersistedBaseline] 同义（作废旧基线）。
     */
    fun retainPersistedBaselineOwned(bytes: ByteArray?, pathIdentifier: String?) {
        if (bytes === persistedBaseline) {
            persistedBaselineRevisionValue += 1
            return
        }
        val previous = persistedBaseline
        persistedBaseline = bytes
        persistedBaselinePathValue = pathIdentifier
        persistedBaselineRevisionValue += 1
        previous?.fill(0)
    }

    /**
     * `ENG-20261028-18`：持久化基线**登记失败**后的收口——作废旧基线并前移修订。
     *
     * 为什么必须作废而不是保留旧值：调用点在「写盘已确认成功」之后，此刻旧基线对应的是
     * **上一个**持久化版本；留着它，漂移 / 同步合并就会把过期内容当共同底版（正是本条缺陷
     * 登记的「旧基线冒充最新持久化版本」）。作废后下游按「无可信基线」走保守并集语义，
     * 且修订前移会令在途校验 fail-closed。清除本身不分配，故本方法是登记的失败终点。
     */
    fun invalidatePersistedBaselineAfterFailedRetain() {
        clearPersistedBaseline()
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

    fun persistedBaselineDigest(): String? = persistedBaseline?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

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
