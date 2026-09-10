package com.keepasskey.app.data.childdb

import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子库凭据的**进程内独立通道**（设计要点 3：凭据隔离）。
 *
 * ## 与根库会话的隔离
 *
 * 子库主密码 / 密钥文件**绝不进入** `DatabaseSession.passwordCache` / `keyFileCache`：
 * 根库会话缓存的是根库凭据，混用会让「换根库密码」与「子库解锁」互相污染，
 * 也会让根库凭据的清理时机无法覆盖子库。本类提供第二条独立通道与独立清零路径：
 *
 * - 槽位以 [ChildDatabaseMount.credentialRefId] 为键，与根库凭据无任何共享结构；
 * - [clearAll] 在「根库锁定 / 关闭」时由 [ChildDatabaseSessionManager.onSessionLocked] 调用
 *   （与 `SyncCacheEvictor` 同为 `SessionLockObserver`，复用同一熔断触发点），
 *   使子库凭据的生命周期不超过根库会话；
 * - 凭据被拒（错误密码/密钥文件）时由 [ChildReadOnlySession] 立即 [clear]，不保留错误凭据。
 *
 * ## 铁律实现
 *
 * - 只承 [CharArray] / [ByteArray]，**没有任何 String 通道**；
 * - [store] 只入克隆副本（调用方数组归调用方处置，本类不擦除借用数组）；
 * - [useCredentials] 交给闭包的是克隆副本，**闭包返回后（含抛异常路径）本类自动清零**，
 *   比 `DatabaseSession.useCredentials`（要求调用方自行清零）给出更强保证；
 * - 全程无日志、无 `toString()`、不落盘——进程内内存是唯一的凭据载体。
 */
@Singleton
class ChildDatabaseCredentialStore @Inject constructor() {

    /**
     * 单个凭据槽位。字段可变是为了「先擦旧值再放新值」，避免中途留下两份明文。
     */
    private class CredentialSlot {
        var passwordChars: CharArray? = null
        var keyFileData: ByteArray? = null

        /** 显式清零并释放（幂等，可重复调用） */
        fun wipe() {
            passwordChars?.fill(ZERO_CHAR)
            passwordChars = null
            keyFileData?.fill(ZERO_BYTE)
            keyFileData = null
        }

        fun isEmpty(): Boolean = passwordChars == null && keyFileData == null
    }

    private val lock = Any()

    private val slots = HashMap<String, CredentialSlot>()

    /**
     * 写入（或替换）某挂载的凭据，**克隆语义**：只保存传入数组的副本。
     *
     * 两个数组皆为 null（或空）时视为「清除该槽位」，不留空壳槽位。
     */
    fun store(refId: String, passwordChars: CharArray?, keyFileData: ByteArray?) {
        val password = passwordChars?.takeIf { it.isNotEmpty() }?.clone()
        val keyFile = keyFileData?.takeIf { it.isNotEmpty() }?.clone()
        if (password == null && keyFile == null) {
            clear(refId)
            return
        }
        val slot = CredentialSlot().apply {
            this.passwordChars = password
            this.keyFileData = keyFile
        }
        synchronized(lock) {
            slots.put(refId, slot)?.wipe()
        }
    }

    /**
     * 在凭据副本上执行 [block]，**闭包返回后自动清零副本**（含抛异常路径）。
     *
     * 不持有内部锁跨越挂起点：仅取副本时短暂同步，密钥派生 / 解密在锁外进行，
     * 避免 `Dispatchers.Default` 上的长任务占住监视器。
     *
     * 槽位不存在或为空时以 `(null, null)` 调用 [block]，由调用方判定「凭据缺失」
     * ——本类不替调用方决定语义，也不凭空造出空凭据去解密。
     */
    suspend fun <T> useCredentials(refId: String, block: suspend (CharArray?, ByteArray?) -> T): T {
        val snapshot = snapshotOf(refId)
        val password = snapshot.first
        val keyFile = snapshot.second
        return try {
            block(password, keyFile)
        } finally {
            password?.fill(ZERO_CHAR)
            keyFile?.fill(ZERO_BYTE)
        }
    }

    /** 取某槽位的凭据克隆副本（短临界区，不跨越挂起点）；无槽位时返回空副本对 */
    private fun snapshotOf(refId: String): Pair<CharArray?, ByteArray?> = synchronized(lock) {
        val slot = slots[refId]
        if (slot == null) {
            EMPTY_CREDENTIALS
        } else {
            Pair(slot.passwordChars?.clone(), slot.keyFileData?.clone())
        }
    }

    /** 该挂载是否存在可用凭据（不读取、不返回任何密钥材料） */
    fun hasCredentials(refId: String): Boolean = synchronized(lock) {
        slots[refId]?.isEmpty() == false
    }

    /** 清零并移除单个槽位；不存在时为幂等无操作 */
    fun clear(refId: String) {
        synchronized(lock) {
            slots.remove(refId)?.wipe()
        }
    }

    /** 清零全部槽位（根库锁定 / 关闭时的统一入口） */
    fun clearAll() {
        synchronized(lock) {
            slots.values.forEach { it.wipe() }
            slots.clear()
        }
    }

    /** 存活槽位数量（仅用于单测与诊断计数，不含任何密钥材料） */
    @VisibleForTesting
    internal fun trackedSlotCount(): Int = synchronized(lock) { slots.size }

    private companion object {
        /** 字符擦除填充值（`Arrays.fill(chars, '0')` 的既有约定值） */
        const val ZERO_CHAR: Char = '0'

        /** 字节擦除填充值 */
        const val ZERO_BYTE: Byte = 0

        /** 「无凭据」空副本对（不可变占位，内含 null，无密钥材料） */
        val EMPTY_CREDENTIALS: Pair<CharArray?, ByteArray?> = Pair(null, null)
    }
}
