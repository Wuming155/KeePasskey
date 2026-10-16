package com.keepasskey.app.data.repository

/**
 * `ISSUE-P1-631`（生命周期竞态验收）：合并提交流程的**确定性阶段门**。
 *
 * 生产恒为 null、零开销；仅设备侧 androidTest 注入（`RealVaultRepository.driftMergePhaseGatesForTesting`），
 * 用 latch / deferred 在受控时点暂停在途提交，验证「宿主销毁后提交仍完成」与「保存失败后
 * 待决状态恢复」——不以概率性 sleep 制造竞态。三个门对应提交的三个可区分阶段：
 *
 * - [beforeReread]：点击已受理、**重读磁盘前**（尚未产生任何 IO）；
 * - [beforeCommit]：重读 + 重合并完成、正式提交临界区之前（与既有 `mergeCommitGate` 同位）；
 * - [commitEntered]：提交临界区已进入（`NonCancellable` 区内、元数据复核前）。
 */
data class DriftMergePhaseGates(
    val beforeReread: (suspend () -> Unit)? = null,
    val beforeCommit: (suspend () -> Unit)? = null,
    val commitEntered: (suspend () -> Unit)? = null
)
