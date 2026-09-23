package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                debugLog.warn(SYNC_LOG_TAG, "本地数据库序列化失败（合并上传中断）: ${e.javaClass.simpleName}")
                null
            } finally {
                pwdClone?.let { Arrays.fill(it, '0') }
                keyClone?.let { Arrays.fill(it, 0.toByte()) }
            }
        }
    }

    /**
     * 解析外来 KDBX 字节（同步远端 / 缓存快照 / 合并底版）。
     *
     * ISSUE-P2-67：解析**收口到会话层**（[DatabaseSession.parseExternalDatabase]），
     * 由构造关系保证使用**与主会话同一个附件存储**——此前此处直接 `KdbxFile.load(...)`
     * 未传 `binaryStore`，远端库里超过落盘阈值的附件无论多大都内联进堆（`InnerHeader` 池
     * 持有明文）且从不零化。
     *
     * 失败仍只落调试日志（不向调用方抛裸异常）；凭据克隆与清零由会话层承担。
     */
    suspend fun parseKdbxBytes(bytes: ByteArray): KdbxDatabase? =
        when (val result = databaseSession.parseExternalDatabase(bytes)) {
            is KdbxResult.Success -> result.data
            is KdbxResult.Failure -> {
                debugLog.warn(SYNC_LOG_TAG, "远端数据库字节解析失败: ${result.error.javaClass.simpleName}")
                null
            }
        }

    /**
     * 解析远端字节并采用为会话库。
     *
     * ISSUE-P2-278：[expectedSessionSnapshot] 非 null 时走「校验-采用」原子落库
     * （[DatabaseSession.adoptDatabaseIfUnchanged]）——远端接管是**整树替换**，若同步窗口内
     * 会话已被 UI 写路径替换（用户编辑并保存），仍静默覆盖将使该编辑从内存与文件同时消失；
     * 此时如实返回 [ApplyRemoteResult.SESSION_DIVERGED]，由调用方中止本周期。
     * null（用户显式「以云端为准」策略，覆盖语义已在设置页声明）保持既有直采行为。
     */
    suspend fun loadAndApplyRemoteBytes(
        remoteBytes: ByteArray,
        expectedSessionSnapshot: KdbxDatabase? = null
    ): ApplyRemoteResult {
        val remoteDb = parseKdbxBytes(remoteBytes) ?: return ApplyRemoteResult.PARSE_FAILED
        // 注意：解析产物在此**采用为会话库**，其所有权随之下移给会话的生命周期管理，
        // 故此处**不得**调用 clearSensitiveData()（会连带擦掉活动库的内容——含二进制池）。
        // 被替换下线的旧库由 updateDatabaseMeta / adoptDatabaseIfUnchanged 在同一收口点
        // 按身份集合判定擦除树与池（`ISSUE-P3-258`）。
        if (expectedSessionSnapshot != null) {
            if (!databaseSession.adoptDatabaseIfUnchanged(expectedSessionSnapshot, remoteDb)) {
                // 未采用：远端解析树失去持有者，必须显式擦除（不得留给 GC）
                remoteDb.clearSensitiveData()
                return ApplyRemoteResult.SESSION_DIVERGED
            }
        } else {
            databaseSession.updateDatabaseMeta { remoteDb }
        }
        return if (databaseSession.save() is KdbxResult.Success) {
            ApplyRemoteResult.APPLIED
        } else {
            ApplyRemoteResult.SAVE_FAILED
        }
    }

    /** [loadAndApplyRemoteBytes] 的终态（调用方据此前馈用户可理解的错误文案）。 */
    enum class ApplyRemoteResult {
        APPLIED,
        PARSE_FAILED,
        SAVE_FAILED,

        /** ISSUE-P2-278：采用前校验发现会话树在同步窗口内已被本地编辑替换，本轮如实中止。 */
        SESSION_DIVERGED
    }
}
