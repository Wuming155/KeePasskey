package com.keepasskey.app.security

import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.session.DatabaseSession
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ISSUE-P2-378：已打开库文件的外部修改基线持有者（会话级单例）。
 *
 * 打开成功后由 [VaultLifecycleCoordinator] 留存 [VaultFileBaseline]；
 * 保存与回前台两时点由调用方经 [VaultFileDriftPolicy.shouldAbortSave] 比对。
 * 会话锁定 / 关闭时自动清空——锁定后基线失去意义，且不得跨库复用。
 *
 * SAF / content:// 通道：调用方用 DocumentFile 元数据构造基线；本地 File 直接
 * [VaultFileBaseline.fromFile]。元数据不可读时基线为 null ⇒ 策略层「宁可不提示」
 * 而非误报（见 VaultFileDriftPolicy KDoc）。
 */
@Singleton
class VaultFileBaselineHolder @Inject constructor(
    private val databaseSession: DatabaseSession
) {

    @Volatile
    private var baseline: VaultFileBaseline? = null

    private val observer = SessionLockObserver { clear() }

    fun register() {
        databaseSession.addLockObserver(observer)
    }

    fun unregister() {
        databaseSession.removeLockObserver(observer)
    }

    /**
     * 打开成功后留存基线；[file] 为 null 时（SAF / content:// 通道）**不落基线**。
     *
     * 2026-10-02 真机实测修复：此前此处以 `fromMetadata(path, 0, 0)` 占位——假基线与
     * 保存时的 null current 相遇按策略恒判漂移，SAF 库**每一次保存**都被
     * `ext_mod_save_aborted` 中止（fail-closed 误报面）。按 [VaultFileDriftPolicy]
     * 既有口径「元数据不可读 ⇒ 基线为 null ⇒ 宁可不提示」处理；真实 SAF 元数据
     * （DocumentFile lastModified/size）管道另行立项接入后恢复漂移防护。
     */
    fun capture(pathIdentifier: String, file: File?) {
        baseline = file?.let { VaultFileBaseline.fromFile(it) }
    }

    /** 锁定 / 关闭 / 换库后清空 */
    fun clear() {
        baseline = null
    }

    fun current(): VaultFileBaseline? = baseline

    /** 本地 File 通道的当前元数据；无文件返回 null */
    fun currentFromLocalFile(file: File?): VaultFileBaseline? =
        file?.let { VaultFileBaseline.fromFile(it) }
}
