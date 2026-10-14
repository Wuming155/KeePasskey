package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.KdbxErrorTexts
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 库级设置写盘的 UI 状态（`ISSUE-P2-602`）：正在保存 + 一次性反馈。
 *
 * 反馈语义与 `MasterKeyChangeTaskState.feedback` 同源（[UiMessage] + Snackbar 一次性消费）。
 */
internal data class DatabaseConfigWriteState(
    /** 有库级写入正在**真实落盘**（并发写入按计数维护，见 [SettingsDatabaseWriteState]）。 */
    val isSaving: Boolean = false,
    /** 一次性反馈（成功 / 失败 / 参数无效）；null = 无可呈现反馈。 */
    val feedback: UiMessage? = null
)

/**
 * `ISSUE-P2-602`：库级设置写盘的**过程态与一次性反馈**载体。
 *
 * ## 缺陷原形
 *
 * `SettingsPreferencesController` 的 `setEncryptionAlgorithm` / `setKdfAlgorithm` /
 * `setArgon2Parameters` / `setRecycleBinEnabled` 与 `SettingsDatabaseMetaController.setDatabaseMeta`
 * 此前只 `scope.launch { vaultRepository.commitDatabaseMeta { … } }` 而**忽略返回的 `KdbxResult`**：
 * 磁盘满 / 只读 / SAF 写回失败时界面已显示新值而磁盘仍旧，锁定后更改消失，用户无从得知
 * 安全设置**没生效**（同「假闭环」族）。本类把这三类反馈（正在保存 / 失败 / 成功）收口到单点。
 *
 * ## 与「已应用文件配置」的关系（单一真相源，不得混淆）
 *
 * 本类**不持有**任何配置值——回显仍由 `databaseSession.databaseFlow` 的头映射通道下发
 * （`SettingsPreferencesController.databaseConfigState` 为其投影）。失败时不落盘 ⇒ 该通道
 * 不重发 ⇒ 回显保持**磁盘真值**，绝不做「界面已应用」的伪装；用户可重新进入对应对话框
 * 再应用一次（重试入口天然保留）。
 *
 * ## 失败分型（方案 §3.6）
 *
 * **外部漂移**（[KdbxError.DRIFT_SAVE_ABORTED]）由既有三选提示（`VaultFileDriftCoordinator`
 * 写前已 `requestPrompt`）承载，本类**不**叠加一次性反馈；其余写失败按**错误码**映射资源文案
 * （[KdbxErrorTexts]，下层的只读 / 无写通道 / 凭据丢失等各自成文），**绝不**拼接异常 `message`。
 */
internal class SettingsDatabaseWriteState {

    /**
     * 并发写入计数（而非布尔量）：`isSaving` 是「是否有任一写盘在进行」，布尔量会被**先完成的那次**
     * 提前熄灭，导致仍在落盘的写盘期间界面误判为已完成。`AtomicInteger` 保证 `begin/end` 交错安全
     * （`scope.launch` 的调度线程不固定）。
     */
    private val activeSaves = AtomicInteger(0)

    private val _state = MutableStateFlow(DatabaseConfigWriteState())
    val state: StateFlow<DatabaseConfigWriteState> = _state.asStateFlow()

    /**
     * 包住一次真实落盘：进入置「正在保存」、退出**必**释放（[block] 抛异常亦不残留 busy）。
     *
     * 取消语义（方案 §3.5「页面退出不取消已经进入关键提交的事务」）：**进入本段即不可取消**——
     * `withContext(NonCancellable)` 保证用户退出设置页（`viewModelScope` 随导航销毁）不会把
     * 整库重序列化 / Argon2 重派生写盘截断在半途；作用域**在进入本段之前**取消则 `launch` 体
     * 一次都不进入，不留半截状态。写入确实完成后的结果仍如实落到状态流——若页面已销毁则该流
     * 无观察者，**不产生可见的迟到 UI 更新**（反馈由 Snackbar 一次性消费）。
     */
    suspend fun trackSave(block: suspend () -> KdbxResult<Unit>) {
        activeSaves.incrementAndGet()
        _state.update { it.copy(isSaving = true) }
        val result = try {
            withContext(NonCancellable) { block() }
        } finally {
            _state.update { it.copy(isSaving = activeSaves.decrementAndGet() > 0) }
        }
        publish(result)
    }

    /**
     * 提交前参数无效（Argon2 超出读 / 写 / UI 共用的接受域）：不落盘、不改会话，
     * 如实回执。UI 侧对话框已据此禁用「应用」，本入口只为**绕过 UI 的程序化调用**兜底。
     */
    fun rejectInvalidKdfParameters() {
        _state.update {
            it.copy(feedback = UiMessage(R.string.dbset_kdf_params_rejected, isError = true))
        }
    }

    /** 回执经 Snackbar 展示后清除（一次性消息语义，同 `SettingsMasterKeyChangeController.clearFeedback`）。 */
    fun clearFeedback() {
        _state.update { it.copy(feedback = null) }
    }

    private fun publish(result: KdbxResult<Unit>) {
        val feedback = when (result) {
            is KdbxResult.Success -> UiMessage(R.string.dbset_save_applied)
            is KdbxResult.Failure -> libraryWriteFailureFeedback(result)
        }
        _state.update { it.copy(feedback = feedback) }
    }
}

/**
 * `ISSUE-P2-602`：库级写失败 → 用户可见文案（**纯错误码映射**，不拼接异常 `message`）。
 *
 * 外部漂移返回 `null`：该失败已由 `VaultFileDriftCoordinator` 的三选提示（放弃 / 重载 / 合并）
 * 承载，再叠一条一次性反馈只会与三选对话框重复。其余写失败（只读 / 无写通道 / 无活动库 /
 * 凭据丢失 / 通用写失败等）各自映射到 `KdbxErrorTexts` 的专用资源，用户据此分辨处置方向。
 */
internal fun libraryWriteFailureFeedback(failure: KdbxResult.Failure): UiMessage? =
    when (failure.code) {
        KdbxError.DRIFT_SAVE_ABORTED -> null
        else -> UiMessage(KdbxErrorTexts.resIdFor(failure.code), isError = true)
    }
