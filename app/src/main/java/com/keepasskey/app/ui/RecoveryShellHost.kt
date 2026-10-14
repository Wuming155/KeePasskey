package com.keepasskey.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.keepasskey.app.R
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.security.LockOutcome
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.recovery.RecoveryViewModel
import com.keepasskey.app.ui.screens.recovery.VaultRecoveryDialogHost

/**
 * `ISSUE-P1-592`：恢复面外壳挂点（自 `KeePasskeyApp` 拆出，控母文件行数与函数长度）。
 *
 * 两件事：① 冷启动发现未保全的恢复副本后呈现处置对话框（`VaultRecoveryDialogHost`）；
 * ② 订阅锁定结果流——连「无法保全」告警都未能持久化时，当前界面必须**当场**明示数据丢失
 * （方案步骤 1：不承诺进程死亡后仍有告警记录）。
 */
@Composable
internal fun RecoveryShellHost(autoLockManager: AutoLockManager?) {
    val recoveryViewModel: RecoveryViewModel = hiltViewModel()
    VaultRecoveryDialogHost(viewModel = recoveryViewModel)

    LaunchedEffect(autoLockManager) {
        autoLockManager?.lockOutcomes?.collect { outcome ->
            if (outcome is LockOutcome.PreservationFailed && !outcome.alertPersisted) {
                AppSnackbarChannel.trySend(
                    AppSnackbarEvent(
                        message = UiMessage(R.string.recovery_immediate_loss_message, isError = true)
                    )
                )
            }
        }
    }
}
