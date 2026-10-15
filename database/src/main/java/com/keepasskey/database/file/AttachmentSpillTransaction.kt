package com.keepasskey.database.file

import com.keepasskey.core.security.BinaryStore
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap

/** 仅供已通过 KdbxDatabase 池存活判定的 BinaryItem 释放其事务移交 spill key。 */
internal interface AttachmentSpillKeyOwner {
    /** Identity of the underlying store namespace (not wrapper identity). */
    val spillStoreIdentity: Any

    fun releaseOwnedSpillKey(key: String)
}

/**
 * 单次 KDBX 解析的附件 spill 所有权事务。
 *
 * 每次 load 只允许一次。解析失败会立即回收本次成功创建的 key；成功后调用方必须在
 * 明确接管数据库附件时调用 [commit]，或在消费方不再引用附件时调用 [purge]。
 * 清理只按本事务记录的 key 操作，绝不对共享 [BinaryStore] 调用全局 [BinaryStore.clear]。
 */
class AttachmentSpillTransaction internal constructor(
    private val delegate: BinaryStore?
) {
    private val lock = Any()
    private val recordingStore = delegate?.let {
        RecordingBinaryStore(it, ::registerPendingCleanup, ::forgetPendingCleanupWhenClear)
    }
    private var loadStarted = false
    private var state = State.OPEN

    /**
     * 用本事务的记录器解析一次数据库。普通异常及取消均触发按次回收后原样抛出。
     *
     * [inputStream]、[passwordChars] 与 [keyFileData] 均为借用：本事务不关闭输入流、不保留或清零
     * 凭据数组；调用方分别负责流的关闭及凭据数组的擦除。
     */
    fun load(
        inputStream: InputStream,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        totalBytes: Long? = null,
        onProgress: ((Float?) -> Unit)? = null
    ): KdbxDatabase {
        synchronized(lock) {
            check(state == State.OPEN && !loadStarted) { "附件解析事务已使用或已结束" }
            loadStarted = true
            state = State.LOADING
        }
        return try {
            KdbxFile.load(
                inputStream = inputStream,
                passwordChars = passwordChars,
                keyFileData = keyFileData,
                binaryStore = recordingStore ?: delegate,
                totalBytes = totalBytes,
                onProgress = onProgress
            ).also {
                synchronized(lock) {
                    check(state == State.LOADING) { "附件解析事务状态已改变" }
                    state = State.LOADED
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (state == State.LOADING) state = State.OPEN
            }
            try {
                purge()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    /**
     * 成功接管数据库附件后显式转移责任。
     *
     * key 所有权留在解析产生的 [InnerHeader.BinaryItem] 上；[KdbxDatabase.clearBinaryPool]
     * 按存活池身份/key 判定后释放不再使用的 key。事务此后只重试明确失败的删除，不会批量删除活动附件。
     */
    fun commit() {
        synchronized(lock) {
            check(state == State.LOADED) { "只有解析成功的附件事务可以移交所有权" }
            recordingStore?.commitOwnership()
            state = State.TRANSFERRED
        }
    }

    /**
     * 删除本事务实际创建的 spill key。删除后验证 key 已不可读；失败 key 保留并可重试。
     * 幂等：成功清理后的重复调用不再访问 delegate。
     */
    fun purge() {
        synchronized(lock) {
            when (state) {
                State.PURGED -> return
                State.TRANSFERRED -> error("附件所有权已移交，不能由解析事务回收")
                State.LOADING -> error("不能在解析仍进行时回收附件事务")
                State.OPEN, State.LOADED, State.PENDING -> Unit
            }
            try {
                recordingStore?.purgeOwnedKeys()
                state = State.PURGED
                synchronized(pendingTransactions) { pendingTransactions.remove(this) }
            } catch (failure: Throwable) {
                state = State.PENDING
                synchronized(pendingTransactions) { pendingTransactions.add(this) }
                throw failure
            }
        }
    }

    private fun retryPendingKeys() {
        val currentState = synchronized(lock) { state }
        if (currentState == State.TRANSFERRED) {
            try {
                recordingStore?.retryPendingKeys()
                forgetPendingCleanupWhenClear()
            } catch (failure: Throwable) {
                synchronized(pendingTransactions) { pendingTransactions.add(this) }
                throw failure
            }
        } else {
            purge()
        }
    }

    private fun registerPendingCleanup() {
        synchronized(pendingTransactions) { pendingTransactions.add(this) }
    }

    private fun forgetPendingCleanupWhenClear() {
        if (recordingStore?.pendingKeyCount() == 0) {
            synchronized(pendingTransactions) { pendingTransactions.remove(this) }
        }
    }

    private enum class State { OPEN, LOADING, LOADED, TRANSFERRED, PURGED, PENDING }

    /** BinaryStore 委托，仅记录本解析成功创建的 key；读写与缓存策略均保持原样。 */
    private class RecordingBinaryStore(
        private val delegate: BinaryStore,
        private val onPendingCleanup: () -> Unit,
        private val onCleanupSettled: () -> Unit
    ) : BinaryStore by delegate, AttachmentSpillKeyOwner {
        private val lock = Any()
        private val ownedKeys = LinkedHashSet<String>()
        private val pendingKeys = LinkedHashSet<String>()
        private var ownershipTransferred = false

        override val spillStoreIdentity: Any
            get() = delegate

        override fun store(bytes: ByteArray): String = synchronized(lock) {
            check(!ownershipTransferred) { "附件 spill 所有权已移交" }
            delegate.store(bytes).also(ownedKeys::add)
        }

        override fun storeFromStream(input: InputStream, size: Long): String = synchronized(lock) {
            check(!ownershipTransferred) { "附件 spill 所有权已移交" }
            // delegate 必须保证失败写入清理未交付 key 对应的临时/已提交数据。
            delegate.storeFromStream(input, size).also(ownedKeys::add)
        }

        fun commitOwnership() = synchronized(lock) {
            ownershipTransferred = true
        }

        override fun releaseOwnedSpillKey(key: String) {
            val failure = synchronized(lock) {
                if (key !in ownedKeys) return
                deleteOwnedKey(key)
            }
            if (failure != null) onPendingCleanup() else onCleanupSettled()
        }

        fun purgeOwnedKeys() = synchronized(lock) {
            check(!ownershipTransferred) { "附件 spill 所有权已移交" }
            val failures = ownedKeys.toList().mapNotNull(::deleteOwnedKey)
            if (failures.isNotEmpty()) {
                val error = IllegalStateException("Failed to purge ${failures.size} attachment spill item(s)")
                failures.forEach(error::addSuppressed)
                throw error
            }
        }

        fun retryPendingKeys() = synchronized(lock) {
            val failures = pendingKeys.toList().mapNotNull(::deleteOwnedKey)
            if (failures.isNotEmpty()) {
                val error = IllegalStateException("Failed to retry ${failures.size} attachment spill item(s)")
                failures.forEach(error::addSuppressed)
                throw error
            }
        }

        fun pendingKeyCount(): Int = synchronized(lock) { pendingKeys.size }

        /** Return a failure while retaining that opaque key in both ownership and retry sets. */
        private fun deleteOwnedKey(key: String): Throwable? = try {
            delegate.delete(key)
            if (delegate.sizeOf(key) != 0L) {
                throw IllegalStateException("spill key deletion was not confirmed")
            }
            ownedKeys.remove(key)
            pendingKeys.remove(key)
            null
        } catch (failure: Throwable) {
            pendingKeys.add(key)
            failure
        }
    }

    companion object {
        private val pendingTransactions = Collections.newSetFromMap(
            IdentityHashMap<AttachmentSpillTransaction, Boolean>()
        )

        /**
         * Retry previously failed per-key deletions. The result is a non-sensitive count of keys
         * still pending; a future session-lock/global cache sweep remains the process-death fallback.
         */
        @JvmStatic
        fun retryPendingCleanup(): Int {
            val pending = synchronized(pendingTransactions) { pendingTransactions.toList() }
            pending.forEach { transaction ->
                try {
                    transaction.retryPendingKeys()
                } catch (_: Throwable) {
                    // The transaction retains failed opaque keys and remains in the registry.
                }
            }
            val remaining = synchronized(pendingTransactions) { pendingTransactions.toList() }
            return remaining.sumOf { it.recordingStore?.pendingKeyCount() ?: 0 }
        }
    }
}
