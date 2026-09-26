package com.keepasskey.app.ui.screens.edit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑页口令强度的评估调度（`ISSUE-P3-337` 第 4 片（下）自 [EntryEditViewModel] 纯结构性搬移，
 * 行为逐字不变；搬移动机与读数见条目留痕——闸门 `count_line_tiers` 的 `tier1(>500)` 恒 0 硬线）。
 *
 * ISSUE-P2-286 AC①：编辑页强度条的真实熵（crypto 内核 `guessesLog10`，与详情页同一实现
 * `PasswordEntropyEstimator`——三屏收敛单一真相源，替代已退役的「长度 × 4.5」启发式）。
 * CPU 热路径下沉 `Dispatchers.Default`（§3 规则 2）；评估副本用毕即擦，明文不进状态流；
 * [seq] 保证快速连续输入下只采纳最后一次评估（防乱序回写）。
 */
internal class EntryEditEntropyRefresh(
    private val scope: CoroutineScope,
    private val update: ((EntryEditUiState) -> EntryEditUiState) -> Unit
) {
    private var seq = 0L

    fun refresh(password: CharArray) {
        val request = ++seq
        val evalCopy = password.copyOf()
        scope.launch {
            val bits = withContext(Dispatchers.Default) {
                try {
                    com.keepasskey.app.ui.screens.detail.PasswordEntropyEstimator.estimateBits(evalCopy)
                } finally {
                    evalCopy.fill('0')
                }
            }
            update { state -> if (request == seq) state.copy(passwordEntropyBits = bits) else state }
        }
    }
}
