package com.keepasskey.app.ui.screens.database

import android.content.Context
import com.keepasskey.app.sync.SyncCredentialsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「打开已有库」对话框的**凭据预填消费面**（`ISSUE-P2-424` AC③）。
 *
 * 原第二消费面——新建向导「云端」位置的可用性快照（`ISSUE-P3-425`）——已随「云端直建」
 * 整条退役（§396 用户裁决：新建只有本地两档），本类只剩预填。
 *
 * 自 `DatabasePickerViewModel` 拆出（§391 行数分档闸门，**纯结构性改动**）：本类只做
 * 「读凭据 → 造载荷」，不持有对话框可见性（那仍是 VM 的 UI 态），也不参与放行 / 拒绝决策。
 *
 * ## 凭据数组的所有权口径（任何路径恰好擦一次）
 *
 * - **预填包**：交付后数组所有权移交对话框表单态（其离场 `wipeSensitive` 负责擦除），本类只
 *   **弃持不擦**——此处再 `fill` 会把表单正在显示的凭据擦成全零（`ISSUE-P2-403` 的
 *   「擦零指纹」正是这么来的）；
 * - **未消费即关窗**（[cancelAndWipe]）与**装载途中关窗**（装载协程的 `isActive` 守卫）：
 *   由本类就地擦除，杜绝凭据滞留在已不可见的载荷里。
 */
internal class DatabasePickerCloudAccountController(
    /** 云同步凭据仓库；`null` 仅供纯 JVM 单测构造（此时预填恒空） */
    private val store: SyncCredentialsStore?,
    /** 仅用于「上次来源」这一项纯 UI 偏好的读写；`null` 同上 */
    private val appContext: Context?,
    /** Keystore 解封为阻塞操作，落 IO（限定符见 `di/PickerIoDispatcher.kt`）；单测注入 Unconfined */
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope
) {

    private val prefillFlow = MutableStateFlow<OpenVaultPrefill?>(null)
    val prefill: StateFlow<OpenVaultPrefill?> = prefillFlow.asStateFlow()

    /** 预填装载协程句柄：关窗须取消在途装载，否则解封完成的凭据会在关窗后回填滞留 */
    private var loadJob: Job? = null

    /**
     * 打开对话框时装载预填包（上次来源 + 已配置云账号）。
     * 装载失败（store 未注入 / 配置读不到）→ 只带来源预选的空预填，对话框退回手输。
     */
    fun startPrefillLoad() {
        loadJob?.cancel()
        loadJob = scope.launch(ioDispatcher) {
            val pack = buildPrefill()
            // 装载途中窗已关（无挂起点，取消不中断阻塞调用）：包未交付即废弃，凭据就地擦除
            if (!isActive) {
                pack.wipe()
                return@launch
            }
            prefillFlow.value = pack
        }
    }

    /** 关窗：先取消在途装载，再擦已交付未消费的预填凭据（已消费路径 flow 恒 null，本函数幂等） */
    fun cancelAndWipe() {
        loadJob?.cancel()
        prefillFlow.value?.wipe()
        prefillFlow.value = null
    }

    /** 对话框已把预填包写入表单快照态（接管数组擦除责任）后回调：本类只弃持不擦 */
    fun consumePrefill() {
        prefillFlow.value = null
    }

    /** 来源 Chip 被用户切换：留痕为下次打开的预选来源（AC③「预选记忆」） */
    fun noteSource(source: OpenVaultSourceType) {
        appContext?.getSharedPreferences(PICKER_UI_PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_LAST_OPEN_SOURCE, source.name)?.apply()
    }

    /** 组装预填包：上次来源 + 按 Provider 读取已配置账号（解封数组所有权随载荷移交） */
    private fun buildPrefill(): OpenVaultPrefill {
        val store = store
        var webdav: OpenVaultWebDavPrefill? = null
        var s3: OpenVaultS3Prefill? = null
        if (store != null) {
            when (store.loadProvider()) {
                com.keepasskey.app.ui.screens.settings.CloudSyncProvider.WEBDAV ->
                    store.loadWebDavConfig()?.let { creds ->
                        webdav = OpenVaultWebDavPrefill(creds.url, creds.username, creds.password, creds.remotePath)
                    }
                com.keepasskey.app.ui.screens.settings.CloudSyncProvider.S3_COMPATIBLE ->
                    store.loadS3Config()?.let { creds ->
                        s3 = OpenVaultS3Prefill(
                            creds.endpoint, creds.bucket, creds.region,
                            creds.accessKey, creds.secretKey, creds.objectKey, creds.usePathStyle
                        )
                    }
            }
        }
        return OpenVaultPrefill(readLastSource(), webdav, s3)
    }

    private fun readLastSource(): OpenVaultSourceType =
        appContext?.getSharedPreferences(PICKER_UI_PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_LAST_OPEN_SOURCE, null)
            ?.let { name -> runCatching { OpenVaultSourceType.valueOf(name) }.getOrNull() }
            ?: OpenVaultSourceType.LOCAL

    private companion object {
        /** ISSUE-P2-424：本页纯 UI 偏好的独立 prefs（刻意不与凭据/目录登记同文件——`clear()` 语义互不牵连） */
        const val PICKER_UI_PREFS = "db_picker_ui_prefs"

        /** 「打开已有库」上次使用的来源（预选记忆） */
        const val KEY_LAST_OPEN_SOURCE = "open_vault_last_source"
    }
}
