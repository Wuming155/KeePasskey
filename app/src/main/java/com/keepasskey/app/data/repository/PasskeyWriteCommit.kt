package com.keepasskey.app.data.repository

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult

/**
 * `ISSUE-P1-590`：通行密钥写入口的**落盘交付边界**与**分型失败**。
 *
 * 自 [PasskeyEntryCoordinator] 拆出以维持其规模档（tier2 棘轮只紧不松）；语义与调用契约见该类的
 * 「持久化交付契约」KDoc——本对象只提供三块可复用的机械动作：[commit]（只有落盘成功才交付）、
 * [missingEntry] / [readOnly]（与落盘失败分型表达的失败构造）。
 */
internal object PasskeyWriteCommit {

    /**
     * 落盘交付边界：条目已在会话内上线（`DIRTY`），但**只有** [persistSession] 成功才向上交付该条目；
     * 落盘失败原样上浮 [KdbxResult.Failure]（含漂移中止 / 只读 / 磁盘满），失败时条目仍留在内存树中
     * 供后续重试——绝不把「内存已改」当成「已持久化」回传调用方。
     */
    suspend fun commit(
        entry: KdbxEntry,
        what: String,
        debugLog: DebugLogBuffer,
        persistSession: suspend () -> KdbxResult<Unit>
    ): KdbxResult<KdbxEntry> = when (val saved = persistSession()) {
        is KdbxResult.Success -> KdbxResult.Success(entry)
        is KdbxResult.Failure -> {
            debugLog.warn(TAG, "$what 已改内存但落盘失败，未回传成功: ${saved.error.javaClass.simpleName}")
            saved
        }
    }

    /** 未命中条目 / id 非法（本次**无任何写入**）。 */
    fun missingEntry(): KdbxResult.Failure = KdbxResult.Failure(
        NoSuchElementException("通行密钥目标条目不存在"), code = KdbxError.PASSKEY_ENTRY_NOT_FOUND
    )

    /** 只读会话（写入口 fail-closed，本次无写入）。 */
    fun readOnly(): KdbxResult.Failure = KdbxResult.Failure(
        IllegalStateException("会话只读"), code = KdbxError.SAVE_READ_ONLY
    )

    private const val TAG = "RealVaultRepository"
}
