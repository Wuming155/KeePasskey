package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话库 ↔ KDBX4 字节的双向编解码（ISSUE-P3-25 拆分自 `SyncCoordinator`，纯搬运）。
 *
 * 职责边界：只承载「借出凭据 → 序列化 / 解析 / 落盘应用」三步，供同步周期与冲突决策共用；
 * 敏感数据铁律保持原样——凭据一律 clone 借用、finally 显式 `Arrays.fill` 清零，
 * 序列化/解析失败只落调试日志（不向调用方抛裸异常），CPU 密集段固定 `Dispatchers.Default`。
 */
@Singleton
class SyncDatabaseCodec @Inject constructor(
    private val databaseSession: DatabaseSession,
    private val debugLog: DebugLogBuffer
) {

    suspend fun serializeLocalDatabase(db: KdbxDatabase): ByteArray? = withContext(Dispatchers.Default) {
        databaseSession.useCredentials { pwd, key ->
            val pwdClone = pwd?.clone()
            val keyClone = key?.clone()
            try {
                val baos = ByteArrayOutputStream()
                // P1-10：pwdClone 为 null 表示仅密钥文件会话（无主密码分量），直接透传
                KdbxFile.save(baos, db, pwdClone, keyClone)
                baos.toByteArray()
            } catch (e: Exception) {
                // P3-31 整改：序列化失败不再静默吞掉，至少落调试日志保留异常细节
                debugLog.warn(SYNC_LOG_TAG, "本地数据库序列化失败（合并上传中断）: ${e.message}")
                null
            } finally {
                pwdClone?.let { Arrays.fill(it, '0') }
                keyClone?.let { Arrays.fill(it, 0.toByte()) }
            }
        }
    }

    suspend fun parseKdbxBytes(bytes: ByteArray): KdbxDatabase? = withContext(Dispatchers.Default) {
        databaseSession.useCredentials { pwd, key ->
            val pwdClone = pwd?.clone()
            val keyClone = key?.clone()
            try {
                // P1-10：pwdClone 为 null 表示仅密钥文件会话（无主密码分量），直接透传
                KdbxFile.load(ByteArrayInputStream(bytes), pwdClone, keyClone)
            } catch (e: Exception) {
                // P3-31 整改：解析失败不再静默吞掉，至少落调试日志保留异常细节
                debugLog.warn(SYNC_LOG_TAG, "远端数据库字节解析失败: ${e.message}")
                null
            } finally {
                pwdClone?.let { Arrays.fill(it, '0') }
                keyClone?.let { Arrays.fill(it, 0.toByte()) }
            }
        }
    }

    suspend fun loadAndApplyRemoteBytes(remoteBytes: ByteArray): Boolean {
        val remoteDb = parseKdbxBytes(remoteBytes) ?: return false
        databaseSession.updateDatabaseMeta { remoteDb }
        return databaseSession.save() is KdbxResult.Success
    }
}
