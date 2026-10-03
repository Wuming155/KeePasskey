package com.keepasskey.app.security

import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.session.DatabaseSession
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ISSUE-P2-378：已打开库文件的外部修改基线持有者（会话级单例）。
 *
 * 打开 / 新建 / 保存成功后由调用方留存 [VaultFileBaseline]；
 * 保存前由调用方经 [VaultFileDriftPolicy.shouldAbortSave] 比对。
 * 会话锁定 / 关闭时自动清空——锁定后基线失去意义，且不得跨库复用。
 *
 * 基线的构造一律走 [`VaultFileMetadataProbe.baselineFor`][VaultFileMetadataProbe]：
 * 本地 `File` 直读属性，SAF / `content://` 取真实文档元数据（`ISSUE-P3-447` AC②）。
 * 元数据不可读时基线为 null ⇒ 策略层「宁可不提示」而非误报（见 VaultFileDriftPolicy KDoc）。
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
     * 留存由调用方构造的基线（打开 / 新建 / 保存成功三时点）。
     *
     * `ISSUE-P3-447` AC② 起参数由 `File?` 改为**已构造好的 [VaultFileBaseline]?**：
     * 本地 `File` 与 SAF 的元数据来源不同（`fromFile` vs `VaultFileMetadataProbe`），
     * 把「取哪一路元数据」的知识留在调用侧，本持有者只管留存与清空。
     *
     * `null` = 元数据当前不可读（如 SAF 提供方不暴露 `last_modified`）——按
     * [VaultFileDriftPolicy] 既有口径「宁可不提示」处理，不得落占位假值
     * （2026-10-02 教训：`fromMetadata(path, 0, 0)` 假基线与保存时的 null 现值相遇，
     * 会让 SAF 库每一次保存都被 `ext_mod_save_aborted` 中止）。
     */
    fun capture(baseline: VaultFileBaseline?) {
        this.baseline = baseline
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
