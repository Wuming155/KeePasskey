package com.keepasskey.app.ui.screens.settings

import android.app.ActivityManager
import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.crypto.kdf.KdfBenchmark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * KDF 设备自适应基准编排（ISSUE-P3-29：自 `SettingsViewModel.kt` 拆出，纯结构性拆分，行为零变更）。
 *
 * M6 整改：真实调用 [KdfBenchmark] 实测 Argon2 单轮耗时，以设备应用堆上限为内存约束外推推荐参数。
 */
internal class SettingsKdfBenchmarkController(
    private val appContext: Context?,
    private val strings: StringsProvider,
    private val scope: CoroutineScope
) {

    private val kdfBenchmarkFlow = MutableStateFlow(KdfBenchmarkUiState())

    /** KDF 基准实时状态（运行中 / 推荐参数 / 失败原因） */
    val state: StateFlow<KdfBenchmarkUiState> = kdfBenchmarkFlow

    /**
     * 运行真实 KDF 基准测试（Dispatchers.Default，不阻塞主线程）：
     * 以设备应用堆上限为内存约束，实测 Argon2 单轮耗时后按 1s 目标外推推荐参数。
     */
    fun run() {
        if (kdfBenchmarkFlow.value.isRunning) return
        scope.launch(Dispatchers.Default) {
            kdfBenchmarkFlow.value = KdfBenchmarkUiState(isRunning = true)
            try {
                val recommendation = KdfBenchmark.benchmarkArgon2(
                    availableMemoryBytes = deviceAvailableMemoryBytes()
                )
                kdfBenchmarkFlow.value = KdfBenchmarkUiState(
                    isRunning = false,
                    recommendedIterations = recommendation.iterations,
                    recommendedMemoryMb = recommendation.memoryBytes / (1024L * 1024L),
                    recommendedParallelism = recommendation.parallelism
                )
            } catch (t: Throwable) {
                kdfBenchmarkFlow.value = KdfBenchmarkUiState(
                    isRunning = false,
                    errorMessage = t.message ?: strings.get(R.string.kdf_benchmark_failed)
                )
            }
        }
    }

    /** Argon2 在 Java 堆分配内存矩阵，应用堆上限（memoryClass）即实际可用内存约束 */
    private fun deviceAvailableMemoryBytes(): Long {
        val activityManager = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val heapMb = activityManager?.memoryClass ?: DEFAULT_HEAP_MB
        return heapMb * 1024L * 1024L
    }

    private companion object {
        /** ActivityManager 不可得时的兜底应用堆上限（MiB） */
        const val DEFAULT_HEAP_MB = 128
    }
}
