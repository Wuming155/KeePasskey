package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.sync.CloudVaultImportRequest
import com.keepasskey.app.sync.CloudVaultImportResult
import com.keepasskey.app.sync.CloudVaultImporter
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult

/**
 * 「从来源打开已有库」的登记流程（`ISSUE-P2-399` / `ISSUE-P2-424`）。
 *
 * 自 `DatabasePickerViewModel` 拆出（§391 行数分档闸门，**纯结构性改动、行为逐字不变**）：
 * 本地与云端两条路径**共用同一个登记出口** [importLocal]——云端先由 [CloudVaultImporter] 把远端库
 * 下载到本地（下载成功才落凭据），再按**本地文件路径**登记，与「本地打开 → 同步配置补全」的
 * 手工链路完全同构。
 *
 * 弱工作因子提示的如实口径（`ISSUE-P3-248`）**仍留在** VM 的 `importDatabaseFromSource` KDoc：
 * 那条路径随导入立即退栈，本页不存在可用的提示通道（`WeakKdfNoticeHonestyGuardTest` 锁住该 KDoc）。
 *
 * @param publish 反馈消息出口（VM 的 `publishPickerMessage`，保持消息通道单一）
 * @param onOpened 登记成功后的收口：关对话框 + 「已打开」提示 + 发出选中事件（参数为库文件名）。
 *   **suspend** 以保证与旧实现同一调用链（选中事件宿主会立即 `popBackStack`，不得改为一轮新的派发）
 */
internal class DatabasePickerOpenVaultImporter(
    private val vaultRepository: VaultRepository,
    private val cloudVaultImporter: CloudVaultImporter?,
    private val publish: (UiMessage) -> Unit,
    private val onOpened: suspend (fileName: String) -> Unit
) {

    /** 本地库：登记（`content://` 持久化授权 / 文件路径）并置为活动库（原实现原样保留） */
    suspend fun importLocal(
        submission: OpenVaultSubmission.Local,
        syncType: String = OpenVaultSourceType.LOCAL.label
    ) {
        val result = vaultRepository.importExternalDatabase(
            submission.name,
            submission.path,
            syncType = syncType
        )
        if (result is KdbxResult.Success) {
            val fileName = if (submission.name.endsWith(".kdbx", ignoreCase = true)) {
                submission.name
            } else {
                "${submission.name}.kdbx"
            }
            onOpened(fileName)
        } else {
            publish(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
        }
    }

    /**
     * 云端库（ISSUE-P2-399）：先下载后登记。凭据 `CharArray` 擦除责任在 `CloudVaultImporter`；
     * 成功后以本地文件路径走 [importLocal] 同一出口，`syncType` 保持云端标签
     * （卡片云徽章与解锁页「云端库」状态照常呈现）。
     */
    suspend fun importCloud(request: CloudVaultImportRequest) {
        val importer = cloudVaultImporter
        if (importer == null) {
            publish(
                UiMessage(R.string.op_failed, listOf("CloudVaultImporter unavailable"), isError = true)
            )
            return
        }
        when (val result = importer.import(request)) {
            is CloudVaultImportResult.Success -> {
                val local = java.io.File(result.localPath)
                val syncType = when (request) {
                    is CloudVaultImportRequest.WebDav -> OpenVaultSourceType.WEBDAV.label
                    is CloudVaultImportRequest.S3 -> OpenVaultSourceType.S3_COMPATIBLE.label
                }
                importLocal(
                    OpenVaultSubmission.Local(name = local.nameWithoutExtension, path = result.localPath),
                    syncType = syncType
                )
            }
            is CloudVaultImportResult.Failure -> publish(result.message)
        }
    }
}
