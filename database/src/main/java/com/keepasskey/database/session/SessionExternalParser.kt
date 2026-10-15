package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Arrays

/**
 * 以会话同一附件存储解析外来 KDBX 字节（§280 自 [DatabaseSession.parseExternalDatabase] 拆出）。
 *
 * 语义边界见原 KDoc（ISSUE-P2-67）：不取会话互斥锁；凭据克隆 finally 清零；
 * 成功后 spill key 随解析得到的 `KdbxDatabase` / 池条目移交给调用方，丢弃前必须
 * `clearSensitiveData()`，其存活池判定会按 key 释放不再使用的 spill。
 */
internal suspend fun parseExternalKdbxBytes(
    bytes: ByteArray,
    useCredentials: (block: (CharArray?, ByteArray?) -> KdbxResult<KdbxDatabase>) -> KdbxResult<KdbxDatabase>,
    binaryStore: com.keepasskey.core.security.BinaryStore?
): KdbxResult<KdbxDatabase> = withContext(Dispatchers.Default) {
    useCredentials { pwd, key ->
        val pwdClone = pwd?.clone()
        val keyClone = key?.clone()
        val spillTransaction = KdbxFile.beginAttachmentSpillTransaction(binaryStore)
        try {
            val database = spillTransaction.load(ByteArrayInputStream(bytes), pwdClone, keyClone)
            spillTransaction.commit()
            KdbxResult.Success(database)
        } catch (cancellation: CancellationException) {
            try {
                spillTransaction.purge()
            } catch (cleanupFailure: Throwable) {
                cancellation.addSuppressed(cleanupFailure)
            }
            throw cancellation
        } catch (t: Throwable) {
            try {
                spillTransaction.purge()
            } catch (cleanupFailure: Throwable) {
                t.addSuppressed(cleanupFailure)
            }
            KdbxResult.Failure(t)
        } finally {
            pwdClone?.let { Arrays.fill(it, '0') }
            keyClone?.let { Arrays.fill(it, 0.toByte()) }
        }
    }
}
