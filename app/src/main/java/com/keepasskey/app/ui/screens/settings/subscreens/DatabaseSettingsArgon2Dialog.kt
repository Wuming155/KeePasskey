package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.KdfBenchmarkUiState
import com.keepasskey.app.ui.screens.settings.SettingsUiState

// 对话框 3：Argon2 参数详细调节
@OptIn(ExperimentalLayoutApi::class)
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
                // ISSUE-P3-136：说明文案与标题同列，步进器（− / 数值 / +）独占右侧——
                // 原先把说明挤在 − 与 + 之间，操作动线被大段小字拦断
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dbset_argon2_iterations_label),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.dbset_argon2_rounds_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // 触及 1 / 50 边界时置灰：否则按钮仍可点却无任何反馈
                    IconButton(
                        onClick = { tempIterations-- },
                        enabled = tempIterations > ITERATIONS_MIN,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.dbset_cd_decrease_rounds))
                    }
                    Text(
                        text = stringResource(R.string.dbset_argon2_rounds_value, tempIterations),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    IconButton(
                        onClick = { tempIterations++ },
                        enabled = tempIterations < ITERATIONS_MAX,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dbset_cd_increase_rounds))
                    }
                }

                Column {
                    Text(stringResource(R.string.dbset_argon2_memory_label, tempMemoryMb), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(16L, 32L, 64L, 128L, 256L).forEach { mb ->
                            FilterChip(
                                selected = tempMemoryMb == mb,
                                onClick = { tempMemoryMb = mb },
                                label = { Text(stringResource(R.string.dbset_argon2_memory_chip, mb)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                Column {
                    Text(stringResource(R.string.dbset_argon2_parallelism_label, tempParallelism), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 4, 8).forEach { threads ->
                            FilterChip(
                                selected = tempParallelism == threads,
                                onClick = { tempParallelism = threads },
                                label = { Text(stringResource(R.string.dbset_argon2_threads_chip, threads)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

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
                val benchmarkState = kdfBenchmarkState
                if (benchmarkState != null) {
                    if (benchmarkState.isRunning) {
                        Text(
                            text = stringResource(R.string.dbset_benchmark_running),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    benchmarkState.recommendedIterations?.let { recommendedIterations ->
                        LaunchedEffect(recommendedIterations) {
                            tempIterations = recommendedIterations
                            benchmarkState.recommendedMemoryMb?.let { tempMemoryMb = it }
                            benchmarkState.recommendedParallelism?.let { tempParallelism = it }
                        }
                        Text(
                            text = stringResource(
                                R.string.dbset_benchmark_result,
                                recommendedIterations,
                                benchmarkState.recommendedMemoryMb ?: 0L,
                                benchmarkState.recommendedParallelism ?: 0
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    benchmarkState.errorMessage?.let { error ->
                        Text(
                            text = stringResource(R.string.dbset_benchmark_failed, error),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApplyParameters(tempIterations, tempMemoryMb, tempParallelism)
                onDismiss()
            }) {
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

/** ISSUE-P3-136：迭代轮数合法域（沿用原实现的 1..50，仅把魔法数字提到一处） */
private const val ITERATIONS_MIN = 1L
private const val ITERATIONS_MAX = 50L
