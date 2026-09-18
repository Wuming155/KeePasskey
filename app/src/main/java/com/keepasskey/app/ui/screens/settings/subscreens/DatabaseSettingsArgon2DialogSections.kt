package com.keepasskey.app.ui.screens.settings.subscreens

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.KdfBenchmarkUiState

/**
 * `Argon2ParametersDialog` 的段落组件（ISSUE-P3-188 第 3 目：§183 自该对话框拆出，**纯结构性改动**）。
 *
 * 口径同 §156 / §159 / §175 / §178 / §179：窄参数、**不自持状态**——三个临时参数
 * （`tempIterations` / `tempMemoryMb` / `tempParallelism`）与「应用参数」的落地仍全在对话框本体，
 * 本文件只搬绘制与「何时可点」的判定。
 * [KdfBenchmarkResultSection] 收的是**只读快照** `KdfBenchmarkUiState?`（非 ViewModel，且整段只用它的字段），
 * 回填动作经回调交回本体完成——`LaunchedEffect` 的键与进出组合时机与原实现逐字一致。
 */

/** ISSUE-P3-136：迭代轮数合法域（沿用原实现的 1..50，仅把魔法数字提到一处） */
private const val ITERATIONS_MIN = 1L
private const val ITERATIONS_MAX = 50L

/** 内存档位（MiB）与并行度档位候选：与 `SettingsViewModel` 的可选域一致 */
private val MEMORY_MB_OPTIONS = listOf(16L, 32L, 64L, 128L, 256L)
private val PARALLELISM_OPTIONS = listOf(1, 2, 4, 8)

/**
 * 迭代轮数行：说明文案与标题同列，步进器（− / 数值 / +）独占右侧。
 *
 * ISSUE-P3-136：原先把说明挤在 − 与 + 之间，操作动线被大段小字拦断。
 * 触及 1 / 50 边界时置灰：否则按钮仍可点却无任何反馈。
 */
@Composable
internal fun Argon2IterationsStepper(
    iterations: Long,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
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
        IconButton(
            onClick = onDecrement,
            enabled = iterations > ITERATIONS_MIN,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.dbset_cd_decrease_rounds))
        }
        Text(
            text = stringResource(R.string.dbset_argon2_rounds_value, iterations),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        )
        IconButton(
            onClick = onIncrement,
            enabled = iterations < ITERATIONS_MAX,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.dbset_cd_increase_rounds))
        }
    }
}

/** 内存档位芯片行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Argon2MemoryChipRow(
    memoryMb: Long,
    onMemoryMbChange: (Long) -> Unit
) {
    Column {
        Text(stringResource(R.string.dbset_argon2_memory_label, memoryMb), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MEMORY_MB_OPTIONS.forEach { mb ->
                FilterChip(
                    selected = memoryMb == mb,
                    onClick = { onMemoryMbChange(mb) },
                    label = { Text(stringResource(R.string.dbset_argon2_memory_chip, mb)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
    }
}

/** 并行度档位芯片行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Argon2ParallelismChipRow(
    parallelism: Int,
    onParallelismChange: (Int) -> Unit
) {
    Column {
        Text(stringResource(R.string.dbset_argon2_parallelism_label, parallelism), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PARALLELISM_OPTIONS.forEach { threads ->
                FilterChip(
                    selected = parallelism == threads,
                    onClick = { onParallelismChange(threads) },
                    label = { Text(stringResource(R.string.dbset_argon2_threads_chip, threads)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
    }
}

/**
 * M6 整改：真实基准状态展示——运行中 / 推荐参数（自动填入上方调节项）/ 失败原因。
 *
 * 推荐参数的回填走 [onApplyRecommended]，**临时参数仍归本体所有**；
 * `LaunchedEffect` 以 `recommendedIterations` 为键、且只在非 null 时组合，与原实现一致。
 */
@Composable
internal fun KdfBenchmarkResultSection(
    benchmarkState: KdfBenchmarkUiState?,
    onApplyRecommended: (iterations: Long, memoryMb: Long?, parallelism: Int?) -> Unit
) {
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
                onApplyRecommended(
                    recommendedIterations,
                    benchmarkState.recommendedMemoryMb,
                    benchmarkState.recommendedParallelism
                )
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
