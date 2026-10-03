package com.keepasskey.app.security

import android.content.Context
import com.keepasskey.core.result.KdbxResult
import java.io.File

/**
 * 库文件外部修改漂移的**保存侧单点收口**（`ISSUE-P2-378` + `ISSUE-P3-447` AC②）。
 *
 * 保存路径只需要两件事：保存前问一句「要不要中止」、保存成功后重建基线。
 * 而「按哪条通道取当前元数据」在两通道下不同（本地 `File` 直读属性；SAF 走
 * [VaultFileMetadataProbe] 的真实文档元数据），这套分流口径此前内联在
 * `RealVaultRepository.persistSession` 里，占掉近三十行且与重载 / 合并侧各写一遍。
 * 本类把分流与判据收口为单点，仓库侧只留一行调用。
 *
 * 未装配（纯 JVM 单测 / 未注入协调器）时全部为安全的空操作：
 * [driftedFailure] 恒 null、[captureAfterPersist] 静默——与既有「无协调器即无漂移防护」等价。
 */
internal class VaultFileDriftGuard(
    private val coordinator: VaultFileDriftCoordinator?,
    private val context: Context?
) {

    /**
     * 保存前漂移检测：存在漂移时返回**可直接上浮的失败结果**（并已唤起三选提示），否则 null。
     *
     * @param pathIdentifier 会话当前路径标识
     * @param localFile 本地 `File` 通道（SAF / `content://` 传 null）
     * @param abortMessage 中止原因的用户可见文案（由调用方经 `StringsProvider` 解析）
     */
    fun driftedFailure(
        pathIdentifier: String,
        localFile: File?,
        abortMessage: String
    ): KdbxResult.Failure? {
        if (!isDrifted(pathIdentifier, localFile)) return null
        coordinator?.requestPrompt(pathIdentifier = pathIdentifier)
        return KdbxResult.Failure(
            IllegalStateException("Vault file modified externally"),
            abortMessage
        )
    }

    /**
     * 保存成功后按**真实元数据**重建基线（本地直读文件属性；SAF 取文档 `lastModified` + `size`；
     * 元数据不可读即置空基线，漂移防护降级为「宁可不提示」）。
     */
    fun captureAfterPersist(pathIdentifier: String, localFile: File?) {
        val baseline: VaultFileBaseline? =
            if (localFile != null) VaultFileBaseline.fromFile(localFile)
            else VaultFileMetadataProbe.baselineFor(context, pathIdentifier)
        coordinator?.refreshBaselineAfterPersist(baseline)
    }

    private fun isDrifted(pathIdentifier: String, localFile: File?): Boolean =
        if (localFile != null) {
            coordinator?.checkDrift(pathIdentifier = pathIdentifier, localFile = localFile) == true
        } else {
            coordinator?.checkSafDrift(
                pathIdentifier = pathIdentifier,
                current = VaultFileMetadataProbe.baselineFor(context, pathIdentifier)
            ) == true
        }
}
