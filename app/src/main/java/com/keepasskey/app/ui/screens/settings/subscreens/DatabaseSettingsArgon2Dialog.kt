package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.KdfBenchmarkUiState
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxKdfAcceptance

// 对话框 3：Argon2 参数详细调节
@Composable
internal fun Argon2ParametersDialog(
    uiState: SettingsUiState,
    kdfBenchmarkState: KdfBenchmarkUiState?,
    onRunKdfBenchmark: () -> Unit,
    onApplyParameters: (iterations: Long, memoryMb: Long, parallelism: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var tempIterations by remember { mutableLongStateOf(uiState.argon2Iterations) }
    var tempMemoryMb by remember { mutableLongStateOf(uiState.argon2MemoryMb) }
    var tempParallelism by remember { mutableIntStateOf(uiState.argon2Parallelism) }

    // ISSUE-P1-595：非法组合与读 / 写侧同判据（辅助函数见本文件末尾，非法时禁用「应用」并说明原因）
    val acceptanceRejection: String? = remember(tempIterations, tempMemoryMb, tempParallelism) {
        argon2AcceptanceRejection(tempIterations, tempMemoryMb, tempParallelism)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.dbset_argon2_dialog_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ISSUE-P3-136：说明文案与标题同列，步进器独占右侧（段落组件见同包 *Sections.kt）
                Argon2IterationsStepper(
                    iterations = tempIterations,
                    onDecrement = { tempIterations-- },
                    onIncrement = { tempIterations++ }
                )

                Argon2MemoryChipRow(
                    memoryMb = tempMemoryMb,
                    onMemoryMbChange = { tempMemoryMb = it }
                )

                Argon2ParallelismChipRow(
                    parallelism = tempParallelism,
                    onParallelismChange = { tempParallelism = it }
                )

                // ISSUE-P1-595：非法组合的原因说明（与判据同源，不另抄一份边界文案）
                Argon2AcceptanceReasonText(acceptanceRejection)

                OutlinedButton(
                    onClick = onRunKdfBenchmark,
                    enabled = kdfBenchmarkState?.isRunning != true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.dbset_benchmark_btn))
                }

                // M6 整改：真实基准状态展示——运行中 / 推荐参数（自动填入上方调节项）/ 失败原因
                KdfBenchmarkResultSection(
                    benchmarkState = kdfBenchmarkState,
                    onApplyRecommended = { iterations, memoryMb, parallelism ->
                        tempIterations = iterations
                        memoryMb?.let { tempMemoryMb = it }
                        parallelism?.let { tempParallelism = it }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                // ISSUE-P1-595：非法组合禁用「应用」（原因见对话框内说明）
                enabled = acceptanceRejection == null,
                onClick = {
                    onApplyParameters(tempIterations, tempMemoryMb, tempParallelism)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.dbset_apply_params))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

/**
 * `ISSUE-P1-595`：与读 / 写侧共用同一接受域判据（`KdbxKdfAcceptance`）——
 * 返回 `null` = 可接受；否则为**用户可读的拒绝原因**。非法组合（如 256 MiB × 33~50 轮
 * 超出 `2^33` 字节·轮联合预算）在写入前即被识别，避免产出本应用自身读侧会拒绝的库。
 *
 * 「MiB → 字节」换算溢出同样按非法处置（fail-closed，见 [KdbxKdfAcceptance.memoryBytesFromMbOrNull]），
 * 与 [SettingsPreferencesController.setArgon2Parameters] 的提交前校验同源（默认版本取本仓写出的 V13）。
 */
internal fun argon2AcceptanceRejection(iterations: Long, memoryMb: Long, parallelism: Int): String? {
    val memoryBytes = KdbxKdfAcceptance.memoryBytesFromMbOrNull(memoryMb)
        ?: return "Argon2 内存参数越界（MiB 换算溢出）"
    return KdbxKdfAcceptance.argon2Rejection(
        memoryInBytes = memoryBytes,
        iterations = iterations,
        parallelism = parallelism,
        version = KdfParameters.Argon2.ARGON2_VERSION_13
    )
}

/** `ISSUE-P1-595`：非法组合的原因说明（与判据同源，不另抄一份边界文案）；可接受时不发射节点。 */
@Composable
private fun Argon2AcceptanceReasonText(rejection: String?) {
    if (rejection == null) return
    Text(
        text = rejection,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall
    )
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "Argon2 参数调节对话框 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "Argon2 参数调节对话框 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun Argon2ParametersDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        Argon2ParametersDialog(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState(),
            // 构造一份「基准已完成」的展示态，覆盖推荐参数结果分支
            kdfBenchmarkState = com.keepasskey.app.ui.screens.settings.KdfBenchmarkUiState(
                recommendedIterations = 6L,
                recommendedMemoryMb = 128L,
                recommendedParallelism = 4
            ),
            onRunKdfBenchmark = {},
            onApplyParameters = { _, _, _ -> },
            onDismiss = {}
        )
    }
}
