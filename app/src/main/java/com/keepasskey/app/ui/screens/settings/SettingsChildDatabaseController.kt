package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.childdb.ChildDatabaseLimits
import com.keepasskey.app.data.childdb.ChildDatabaseMountState
import com.keepasskey.app.data.childdb.ChildDatabaseSessionManager
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 子库挂载 / 解锁 / 卸载的编排器（ISSUE-P3-29：自 `SettingsViewModel.kt` 拆出，纯结构性拆分）。
 *
 * ISSUE-P3-20：核心层 [ChildDatabaseSessionManager] 已完成，本类是其 UI 侧唯一入口。
 * 原实现逐字迁移，行为零变更；敏感语义（凭据借用副本用毕清零、SAF 持久化授权留痕、
 * 密钥文件读取失败即中止而非静默降级）原样保留。
 */
internal class SettingsChildDatabaseController(
    private val manager: ChildDatabaseSessionManager?,
    private val keyFileAccess: KeyFileAccess?,
    private val debugLogBuffer: DebugLogBuffer,
    private val scope: CoroutineScope,
    private val stateSubscribeTimeoutMillis: Long
) {

    /** 子库操作反馈（挂载/解锁/卸载的成功与失败文案），由对话框消费后清除 */
    private val feedbackFlow = MutableStateFlow<ChildDatabaseFeedback?>(null)

    /**
     * 已挂载子库计数（[SettingsUiState.childDatabasesCount] 的**真实数据源**）。
     *
     * 语义 = **已挂载**（注册表长度，含未解锁者），非「已解锁数」；控制器缺失时恒为 0
     * —— 回落而非谎报（不注入控制器时 UI 也同时被禁用，两者自洽）。
     */
    val countFlow: Flow<Int> = manager?.mountedCount ?: MutableStateFlow(0)

    /**
     * 子库挂载面板状态：核心层挂载记录 + 运行时状态 → 展示态（无凭据、无条目明文）。
     *
     * 控制器缺失时如实下发 `available = false`（UI 整体禁用），不呈现任何假入口。
     */
    val state: StateFlow<ChildDatabaseUiState> = manager?.let { m ->
        combine(
            m.mounts,
            m.mountStates,
            feedbackFlow
        ) { mounts, states, feedback ->
            ChildDatabaseUiState(
                available = true,
                mounts = mounts.map { mount ->
                    // 未纳入状态表一律回落 Closed（与 ChildDatabaseSessionManager.stateOf 同一口径）
                    val state = states[mount.id] ?: ChildDatabaseMountState.Closed
                    ChildDatabaseMountUiState(
                        mountId = mount.id,
                        alias = mount.alias,
                        status = ChildDatabaseStatusText.of(state),
                        canRetryWithCredentials = ChildDatabaseStatusText.allowsCredentialRetry(state)
                    )
                },
                feedback = feedback
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stateSubscribeTimeoutMillis),
            initialValue = ChildDatabaseUiState(available = true)
        )
    } ?: MutableStateFlow(ChildDatabaseUiState(available = false))

    /**
     * 挂载并首次打开一个子库（核心层原子语义：只有真实解密成功才登记挂载）。
     *
     * [passwordChars] / [keyFileUri] 为**借用语义**：密码数组在本次回调返回后即由输入组件擦除，
     * 故本层先落自有副本并在用毕清零；密钥文件字节经 [keyFileAccess] 读取，同样用毕清零。
     * `content://` 来源在 SAF 选择后**立即申请持久化读授权**，否则进程重启后核心层只能如实报
     * `SOURCE_UNAVAILABLE`（授权申请失败不阻断本次挂载，但必须留痕，不静默）。
     */
    fun mount(
        alias: String,
        sourceUri: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) {
        val password = passwordChars.copyOf()
        scope.launch {
            var keyFileBytes: ByteArray? = null
            try {
                val sessionManager = managerOrReport() ?: return@launch
                if (sourceUri.startsWith(ChildDatabaseLimits.CONTENT_SCHEME)) {
                    persistSourcePermission(sourceUri)
                }
                keyFileBytes = readKeyFileBytes(keyFileUri)
                if (keyFileUri != null && keyFileBytes == null) return@launch
                when (val result = sessionManager.mount(alias, sourceUri, password, keyFileBytes)) {
                    is KdbxResult.Success ->
                        feedbackFlow.value = childDatabaseMountedFeedback()

                    is KdbxResult.Failure ->
                        feedbackFlow.value = childDatabaseFailureFeedback(result.error)
                }
            } finally {
                // 借用副本用毕即清零（无论成功、失败还是提前返回）
                password.fill(ZERO_CHAR)
                keyFileBytes?.fill(ZERO_BYTE)
            }
        }
    }

    /**
     * 为已挂载子库**重新提供凭据**解锁。
     *
     * 覆盖三类真实场景：进程重启 / 根库锁定后凭据已被清零（须重新输入，属有意的安全语义）、
     * 凭据被拒后重试、来源恢复后重试。挂载记录始终保留（非敏感配置不因解密失败丢失）。
     */
    fun unlock(
        mountId: String,
        passwordChars: CharArray,
        keyFileUri: String?
    ) {
        val password = passwordChars.copyOf()
        scope.launch {
            var keyFileBytes: ByteArray? = null
            try {
                val sessionManager = managerOrReport() ?: return@launch
                keyFileBytes = readKeyFileBytes(keyFileUri)
                if (keyFileUri != null && keyFileBytes == null) return@launch
                when (val result = sessionManager.open(mountId, password, keyFileBytes)) {
                    // 成功：清空反馈，条目数由状态文案（Opened 快照）如实呈现
                    is KdbxResult.Success -> feedbackFlow.value = null

                    is KdbxResult.Failure ->
                        feedbackFlow.value = childDatabaseFailureFeedback(result.error)
                }
            } finally {
                password.fill(ZERO_CHAR)
                keyFileBytes?.fill(ZERO_BYTE)
            }
        }
    }

    /** 卸载子库：终止会话、清零其凭据并摘除登记（**不删除**来源文件，文案已如实说明） */
    fun unmount(mountId: String) {
        scope.launch {
            val sessionManager = managerOrReport() ?: return@launch
            when (val result = sessionManager.unmount(mountId)) {
                is KdbxResult.Success -> feedbackFlow.value = null
                is KdbxResult.Failure ->
                    feedbackFlow.value = childDatabaseFailureFeedback(result.error)
            }
        }
    }

    /** 清除子库操作反馈（对话框关闭或用户已读） */
    fun dismissFeedback() {
        feedbackFlow.value = null
    }

    /** 取核心层控制器；缺失（仅单测 / 异常装配）时上浮统一失败反馈并返回 null */
    private fun managerOrReport(): ChildDatabaseSessionManager? {
        val sessionManager = manager
        if (sessionManager == null) {
            feedbackFlow.value = ChildDatabaseFeedback(
                UiMessage(R.string.dbset_child_db_err_unknown),
                isError = true
            )
        }
        return sessionManager
    }

    /**
     * 申请来源的持久化读授权（SAF 选择后立即执行）。
     *
     * 失败**不阻断**本次挂载（本次会话仍可读，核心层已读到字节），但必须留痕：
     * 授权失效只会在进程重启后才暴露为 `SOURCE_UNAVAILABLE`，静默会让该现象无法追溯。
     * 日志不含 Uri（避免来源定位信息外泄）。
     */
    private suspend fun persistSourcePermission(sourceUri: String) {
        val access = keyFileAccess
        if (access == null) {
            debugLogBuffer.warn(TAG, "子库来源持久化读授权通道缺失：进程重启后需重新选择来源")
            return
        }
        if (!access.persistReadPermission(sourceUri)) {
            debugLogBuffer.warn(TAG, "子库来源提供方不支持持久化读授权：仅本次会话可读")
        }
    }

    /**
     * 读取可选密钥文件字节：未选择返回 null；读取失败（含通道缺失）上浮反馈并返回 null，
     * 调用方据此**中止**本次挂载 —— 绝不静默降级为「仅主密码」，那只会得到误导性的「凭据被拒」。
     */
    private suspend fun readKeyFileBytes(keyFileUri: String?): ByteArray? {
        val requested = keyFileUri?.takeIf { it.isNotBlank() } ?: return null
        val access = keyFileAccess
        if (access == null) {
            debugLogBuffer.warn(TAG, "子库密钥文件读取通道缺失，拒绝以缺失密钥文件继续")
            feedbackFlow.value = keyFileFeedback()
            return null
        }
        return when (val result = access.read(requested)) {
            is KeyFileReadResult.Success -> result.bytes

            else -> {
                // 仅留痕分型名（Empty / TooLarge / Unreadable），不外传 Uri 与异常 message
                debugLogBuffer.warn(TAG, "子库密钥文件不可用: ${result.javaClass.simpleName}")
                feedbackFlow.value = keyFileFeedback()
                null
            }
        }
    }

    /** 密钥文件不可用（未选/读不到/空文件/超限）的统一反馈：复用解锁特性既有文案 */
    private fun keyFileFeedback(): ChildDatabaseFeedback =
        ChildDatabaseFeedback(UiMessage(R.string.unlock_keyfile_read_failed), isError = true)

    private companion object {
        const val TAG = "SettingsChildDatabase"

        /** 敏感序列擦除填充值（项目既有约定：`CharArray` 填 `'0'`、`ByteArray` 填 `0`） */
        const val ZERO_CHAR: Char = '0'
        const val ZERO_BYTE: Byte = 0
    }
}
