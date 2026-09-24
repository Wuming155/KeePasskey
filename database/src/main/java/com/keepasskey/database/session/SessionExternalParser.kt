package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Arrays

/**
 * 以会话同一附件存储解析外来 KDBX 字节（§280 自 [DatabaseSession.parseExternalDatabase] 拆出）。
 *
 * 语义边界见原 KDoc（ISSUE-P2-67）：不取会话互斥锁；凭据克隆 finally 清零；
 * 返回值所有权归调用方，丢弃前必须 `clearSensitiveData()`。
 */
internal suspend fun parseExternalKdbxBytes(
    bytes: ByteArray,
    useCredentials: (block: (CharArray?, ByteArray?) -> KdbxResult<KdbxDatabase>) -> KdbxResult<KdbxDatabase>,
    binaryStore: com.keepasskey.core.security.BinaryStore?
): KdbxResult<KdbxDatabase> = withContext(Dispatchers.Default) {
    useCredentials { pwd, key ->
        val pwdClone = pwd?.clone()
        val keyClone = key?.clone()
        // ISSUE-P3-311 项 1：解析期落盘键记录——失败时回滚本次新落盘的附件，
        // 不再滞留 cacheDir（明文附件残留窗口此前只能靠锁库归零）
        val recorder = binaryStore?.let { SpillRecordingBinaryStore(it) }
        try {
            KdbxResult.Success(
                KdbxFile.load(
                    ByteArrayInputStream(bytes),
                    pwdClone,
                    keyClone,
                    recorder ?: binaryStore
                )
            )
        } catch (cancellation: CancellationException) {
            recorder?.purge()
            throw cancellation
        } catch (t: Throwable) {
            recorder?.purge()
            KdbxResult.Failure(t)
        } finally {
            pwdClone?.let { Arrays.fill(it, '0') }
            keyClone?.let { Arrays.fill(it, 0.toByte()) }
        }
    }
}

/**
 * 解析期落盘键记录器（ISSUE-P3-311 项 1）。
 *
 * 记录本次解析**新落盘**的附件 key（store / storeFromStream）；解析失败时 [purge] 逐一删除，
 * 使「失败解析的附件明文」不再滞留 cacheDir。成功解析时记录自然作废（键归解析产物所有，
 * 后续由 `KdbxDatabase.clearBinaryPool` 的池所有权机制管理，ISSUE-P3-258）。
 * 读路径与 clear 透传委托；记录表仅本解析实例可见，并发解析互不干扰（键为随机 UUID）。
 */
internal class SpillRecordingBinaryStore(
    private val delegate: com.keepasskey.core.security.BinaryStore
) : com.keepasskey.core.security.BinaryStore by delegate {

    private val recorded = mutableListOf<String>()

    override fun store(bytes: ByteArray): String =
        delegate.store(bytes).also { recorded.add(it) }

    override fun storeFromStream(input: InputStream, size: Long): String =
        delegate.storeFromStream(input, size).also { recorded.add(it) }

    /** 失败路径回滚：删除本次解析新落盘的全部条目（幂等；单键删除失败不掩盖原异常） */
    fun purge() {
        recorded.forEach { runCatching { delegate.delete(it) } }
        recorded.clear()
    }
}
