package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.security.VaultFileDriftGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import java.io.File

/**
 * `ISSUE-P1-601`：**库级写入意图的窄接口**——全部修改活动库的用户操作（设置页算法 / KDF /
 * Argon2 / 回收站、库 Meta 编辑等）一律经此提交**变更意图**（[transform]），调用方
 * （设置控制器）**不得**再直接调用 `DatabaseSession.updateDatabaseMeta + save`。
 *
 * 边界内语义（`RealVaultRepository` 与 `persistSession` 同一出口）：
 * ① 提交前做**外部修改漂移检查**（fail-closed）——命中即拒绝且**不应用** [transform]，
 *    保留用户意图供重载 / 合并后重建候选，绝不把设置更改当成覆盖授权；
 * ② 应用候选变换后落盘（内部再次校验文件检查点 + 持挂锁）；
 * ③ 仅在落盘成功时刷新文件基线；失败不刷新基线。
 *
 * [transform] 必须为**纯函数式拷贝**（`db.copy(...)`），不得原地改动传入树。
 * 默认实现仅提供安全失败（测试替身若不建模库写入可沿用），生产实现 `RealVaultRepository` 已覆盖。
 */
interface VaultWritePort {

    suspend fun commitDatabaseMeta(
        transform: (KdbxDatabase) -> KdbxDatabase
    ): KdbxResult<Unit> = KdbxResult.Failure(
        UnsupportedOperationException("实现方未支持库级元数据提交"),
        userText = null
    )
}

/**
 * `ISSUE-P1-601`：**全部库级写入口的统一提交边界**（单一收口点）。
 *
 * 此前漂移保护只覆盖 `RealVaultRepository.persistSession`（条目 / 分组等编辑路径），而设置
 * 页算法 / KDF / Argon2 / 回收站、库 Meta 编辑与换密 / 改绑等入口**各自直连**
 * `DatabaseSession.updateDatabaseMeta + save` 或 `changeCredentials`，绕过漂移检查——解锁后
 * 库文件被其它客户端修改，随后在本应用内更改设置或主凭据会把外部新版本**无提示覆盖**。
 *
 * 本类把「库身份 / 会话修订（漂移检查）→ 候选变换 → 落盘 → 基线刷新」收口为单点：
 * - [abortOnDrift]：写前外部修改漂移检查（fail-closed），命中即返回可上浮的失败并唤起三选提示；
 * - [persist]：全库保存单一出口（挂锁成对 + 成功后刷新文件基线）；
 * - [commitMeta]：库级元数据候选提交（**先漂移检查 → 再应用变换 → 再落盘**，漂移时不应用意图）。
 *
 * 「先检查后变换」的次序是有意的：漂移命中时用户意图**不落进内存树**，避免被后续
 * 重载 / 合并误当成「本地已改」参与冲突解决；未命中时 [persist] 会**再次**校验文件检查点
 * （防 TOCTOU，见 [VaultFileDriftGuard]）。
 *
 * 未装配漂移协调器（纯 JVM 单测 / 未注入）时 [abortOnDrift] 恒 null——与既有「无协调器即
 * 无漂移防护」等价。
 */
internal class VaultWriteBoundary(
    private val databaseSession: DatabaseSession,
    private val driftGuard: VaultFileDriftGuard,
    private val strings: StringsProvider,
    private val autoLockGuard: AutoLockSessionGuard,
    /** 保存成功后置空 TOTP 派生缓存等会话级派生读缓存（默认空实现供纯 JVM 单测）。 */
    private val afterPersist: () -> Unit = {},
    /** 保存失败时的脱敏日志回调（默认空实现；生产注入 DebugLogBuffer）。 */
    private val logPersistFailure: (KdbxResult.Failure) -> Unit = {}
) {

    /**
     * 写前外部修改漂移检查（`ISSUE-P2-378` / `ISSUE-P3-447`）。命中即返回可直接上浮的失败
     * （并已唤起 UI 三选提示），否则 null。全库保存、库 Meta 提交、换密 / 改绑**同源**调用本方法。
     */
    fun abortOnDrift(): KdbxResult.Failure? {
        return abortOnDrift(databaseSession.currentPathIdentifier, databaseSession.currentFile)
    }

    /** Evaluate drift against the request's captured target, even if another vault becomes active later. */
    private fun abortOnDrift(pathIdentifier: String?, localFile: File?): KdbxResult.Failure? =
        driftGuard.driftedFailure(
            pathIdentifier.orEmpty(),
            localFile,
            strings.get(R.string.ext_mod_save_aborted)
        )

    /**
     * 会话落盘的**唯一出口**——save() 失败必须原样向上传播，禁止磁盘写失败被静默吞掉导致
     * UI 谎报保存成功、锁库后修改永久丢失。
     *
     * `ISSUE-P3-366` AC②：整库保存（KDF + 重序列化）期间挂锁，try/finally 保证 begin/end 成对
     * （漏配对会令挂锁永不恢复）；挂起期间的锁定触发延迟至保存结束补执行。
     */
    suspend fun persist(): KdbxResult<Unit> {
        abortOnDrift()?.let { return it }
        autoLockGuard.beginLongTask()
        try {
            val result = databaseSession.save()
            afterPersist()
            when (result) {
                is KdbxResult.Success -> driftGuard.captureAfterPersist(
                    databaseSession.currentPathIdentifier.orEmpty(),
                    databaseSession.currentFile
                )
                is KdbxResult.Failure -> logPersistFailure(result)
            }
            return result
        } finally {
            autoLockGuard.endLongTask()
        }
    }

    /**
     * 库级元数据候选提交：漂移检查（不命中才应用）→ 应用 [transform] → 经 [persist] 落盘。
     *
     * [transform] 必须为纯函数式拷贝（`db.copy(...)`），不得原地改动传入树。
     */
    suspend fun commitMeta(transform: (KdbxDatabase) -> KdbxDatabase): KdbxResult<Unit> {
        val snapshot = databaseSession.writeIntents.captureSnapshot() ?: return KdbxResult.Failure(
            IllegalStateException("活动数据库为空"), code = KdbxError.SAVE_NO_DATABASE
        )
        fun switched(): KdbxResult.Failure = KdbxResult.Failure(
            IllegalStateException("提交期间活动数据库已切换"),
            code = KdbxError.SAVE_SESSION_CHANGED
        )
        fun snapshotStillActive(): Boolean =
            databaseSession.databaseFlow.value === snapshot.database &&
                databaseSession.currentPathIdentifier == snapshot.pathIdentifier &&
                databaseSession.currentFile == snapshot.file

        if (!snapshotStillActive()) return switched()
        abortOnDrift(snapshot.pathIdentifier, snapshot.file)?.let { return it }
        autoLockGuard.beginLongTask()
        try {
            if (!snapshotStillActive()) return switched()
            // 第二次写前检查与 `persist()` 的普通保存路径同口径；命中漂移时不构造或发布候选。
            abortOnDrift(snapshot.pathIdentifier, snapshot.file)?.let { return it }
            if (!snapshotStillActive()) return switched()
            val result = databaseSession.writeIntents.commitDatabaseMeta(snapshot.database, transform)
            when (result) {
                is KdbxResult.Success -> {
                    afterPersist()
                    driftGuard.captureAfterPersist(
                        snapshot.pathIdentifier.orEmpty(),
                        snapshot.file
                    )
                }
                is KdbxResult.Failure -> logPersistFailure(result)
            }
            return result
        } finally {
            autoLockGuard.endLongTask()
        }
    }
}
