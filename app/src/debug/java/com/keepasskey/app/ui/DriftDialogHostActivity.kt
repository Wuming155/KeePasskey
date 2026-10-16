package com.keepasskey.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.di.DriftDeviceGraph
import dagger.hilt.android.EntryPointAccessors

/**
 * `ISSUE-P1-631`：设备侧用例的**漂移对话框宿主**（最小 UI 测试基建）。
 *
 * 为什么挂载的是 `ExternalModificationDialogHost`：它正是真机 App 里渲染漂移三选与合并裁决
 * 两个对话框的**同一个生产组合件**（`KeePasskeyApp` → `DriftDialogsHost` → 本件）。本 Activity
 * 只负责提供窗口与把生产单例接进去，不重写任何 UI —— 因此用例点击到的是真实生产按钮、真实文案资源。
 *
 * 为什么落在 `src/debug`（与 `DialogWindowHardeningHostActivity` 同据）：`ActivityScenario.launch`
 * 只能启动**被测应用进程**内的组件；若宿主属测试 APK 包，真机会抛
 * `Intent in process com.keepasskey resolved to different process com.keepasskey.test`。
 * release 变体不含 debug 源集 ⇒ 生产包与其清单一字未改。
 *
 * 提交入口与生产 `SettingsIssueWiring.submitExternalModificationChoice` 同构：经
 * 协调器 `submitChoice` 把「仓库处置 + dismiss」挂到**应用级作用域**（生命周期整改后
 * 生产与测试宿主同一语义，提交不随组合 / 宿主销毁取消）；「稍后处理」与
 * `SettingsIssueWiring.cancelMergeConflict` 逐句同形（在途期间取消由组合件忽略）。
 * 此处不复用 ViewModel 只因它需要整套设置页依赖图，与本项要验收的链路无关。
 */
class DriftDialogHostActivity : ComponentActivity() {

    companion object {
        /**
         * 最近一次处置结果（诊断用；用例在断言失败信息里回显它）。
         * 仅由 debug 源集宿主写入，release 包不含本文件；只读记录，不参与任何成功判定——
         * 用例的成功条件一律是磁盘事实 / 生产协调器状态，不依赖本探针。
         */
        @Volatile
        @JvmStatic
        var lastOutcome: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val coordinator = EntryPointAccessors.fromApplication(
            applicationContext, DriftDeviceGraph::class.java
        ).vaultFileDriftCoordinator()
        val repository = EntryPointAccessors.fromApplication(
            applicationContext, AutofillDeviceGraph::class.java
        ).vaultRepository()

        setContent {
            val pending by coordinator.pending.collectAsStateWithLifecycle()
            val mergeConflict by coordinator.mergeConflict.collectAsStateWithLifecycle()
            val submissionInProgress by coordinator.choiceSubmissionInProgress.collectAsStateWithLifecycle()
            ExternalModificationDialogHost(
                pending = pending,
                mergeConflict = mergeConflict,
                submissionInProgress = submissionInProgress,
                onChoice = { choice ->
                    coordinator.submitChoice {
                        val result = repository.applyExternalModificationChoice(choice)
                        // 设备侧用例诊断用：记录本机实际拿到的处置结果（连同失败原因文本）。
                        lastOutcome = "$choice -> " + when (result) {
                            is com.keepasskey.core.result.KdbxResult.Success -> "Success"
                            is com.keepasskey.core.result.KdbxResult.Failure ->
                                "Failure(${result.error.javaClass.simpleName}: ${result.error.message})"
                        }
                        coordinator.dismiss()
                        result
                    }
                },
                onMergeConflictCancel = {
                    val pathIdentifier = coordinator.lastPromptedPathIdentifier()
                    coordinator.clearMergeConflictPrompt()
                    if (pathIdentifier != null) coordinator.requestPrompt(pathIdentifier)
                }
            )
        }
    }
}
