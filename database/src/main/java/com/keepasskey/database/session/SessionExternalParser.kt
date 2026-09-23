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
        try {
            KdbxResult.Success(
                KdbxFile.load(
                    ByteArrayInputStream(bytes),
                    pwdClone,
                    keyClone,
                    binaryStore
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            KdbxResult.Failure(t)
        } finally {
            pwdClone?.let { Arrays.fill(it, '0') }
            keyClone?.let { Arrays.fill(it, 0.toByte()) }
        }
    }
}
