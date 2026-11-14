package com.keepasskey.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.theme.KeePasskeyTheme

/**
 * 设备侧宿主：**撤销动作抛错**时的全局 Snackbar 行为（当前问题清单 P1「UI 协程出口未兜底」）。
 *
 * 挂载的是生产组合件：`AppGlobalSnackbarHost` + 生产 `AppSnackbarChannel`，只把事件生产者换成
 * 一个「撤销回调必抛错」的注入点。判据（见 `SnackbarUndoFailureDeviceTest`）：
 * 点撤销后进程不死、页面仍在，并以 `undo_failed` 明说「内容未恢复」。
 *
 * 为什么落在 `src/debug`：`ActivityScenario.launch` 只能启动被测应用进程内的组件，release 变体
 * 不含 debug 源集 ⇒ 生产包一字未改（与 `VaultGroupFeedbackDeviceHostActivity` 同口径）。
 */
class SnackbarUndoFailureDeviceHostActivity : ComponentActivity() {

    companion object {
        /** 宿主页固定文案：撤销失败提示出现后它仍须可见（页面未被异常带走）。 */
        const val HOST_MARKER = "undo-host-marker"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeePasskeyTheme {
                Scaffold(snackbarHost = { AppGlobalSnackbarHost() }) { padding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(HOST_MARKER)
                    }
                }
                LaunchedEffect(Unit) {
                    AppSnackbarChannel.trySend(
                        AppSnackbarEvent(
                            message = UiMessage(R.string.vault_entry_deleted, undoable = true),
                            // 注入点：撤销（恢复落库）在宿主作用域执行，失败不得逃逸成进程崩溃
                            onUndo = { throw IllegalStateException("device injection: undo failure") }
                        )
                    )
                }
            }
        }
    }
}
