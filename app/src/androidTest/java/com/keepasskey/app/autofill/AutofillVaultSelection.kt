package com.keepasskey.app.autofill

import android.content.Context
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ISSUE-P3-610：设备侧自动填充用例的**活动库前提**编排（instrumented 用例专用）。
 *
 * 背景（DYN-04）：播种合成库 ≠ 把它选为**活动库**——库目录解析优先保留会话 / 已保存的活动选择。
 * 设备上已有「不同主密码的活动库」时，用例仍输入播种库主密码，会打开错库并误报「未到达选择器」。
 *
 * 本类用**生产 API**（`VaultRepository.getDatabases` / `VaultRepository.selectDatabase`，即用户
 * 「选择数据库」的接线）建立并**断言**活动库前提，而不是只改偏好——后者不足以证明实际会话已切换。
 * 会话与路径断言（解锁后）经 `DatabaseSession` 读取。
 *
 * 时序约束（关键）：[RealVaultRepository] 首次构造时扫描 `filesDir` 得出库列表；本类构造即
 * 注入仓库 ⇒ **必须在播种之后**再构造本类，否则扫描不到刚写入的合成库。
 */
internal class AutofillVaultSelection(private val context: Context) {

    private val graph: AutofillDeviceGraph =
        EntryPointAccessors.fromApplication(context.applicationContext, AutofillDeviceGraph::class.java)

    private val activeIdStore = ActiveDatabaseIdStore(context)

    /** 操作前的原活动选择快照（收尾恢复用）。 */
    data class Snapshot(val storedId: String?, val resolvedId: String?)

    /** 目录解析出的活动库（生产口径：`isActive` 标定）。 */
    fun activeInfo(): VaultDatabaseInfo? = awaitList().firstOrNull { it.isActive }

    fun activeId(): String? = activeInfo()?.id

    /** 经生产选择接口登记并选择 [vaultId]；返回操作前选择快照。 */
    fun select(vaultId: String): Snapshot {
        // activeInfo 内部已等到库列表落定；否则 selectDatabase 找不到目标（静默空操作）。
        val snapshot = Snapshot(activeIdStore.load(), activeInfo()?.id)
        runBlocking { graph.vaultRepository().selectDatabase(vaultId) }
        return snapshot
    }

    /** 等库列表落定（扫描在协程里，构造期可能仍为空）；超时按空表返回，由调用方断言失败。 */
    private fun awaitList(timeoutMs: Long = LIST_WAIT_MS): List<VaultDatabaseInfo> = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            graph.vaultRepository().getDatabases().first { it.isNotEmpty() }
        } ?: emptyList()
    }

    /** 恢复原活动选择；原选择为空（无活动库）时清除选择。 */
    fun restore(snapshot: Snapshot) {
        if (snapshot.resolvedId != null) {
            runBlocking { graph.vaultRepository().selectDatabase(snapshot.resolvedId!!) }
        } else {
            activeIdStore.save(snapshot.storedId)
        }
    }

    /** 解锁后实际会话的活动路径标识（非敏感库身份）；未打开返回 null。 */
    fun sessionPathIdentifier(): String? = graph.databaseSession().currentPathIdentifier

    /** 解锁后实际会话的根分组 UUID（核对打开的确是播种库而非同名干扰库）。 */
    fun sessionRootUuid(): String? =
        graph.databaseSession().databaseFlow.value?.rootGroup?.id?.toString()

    /** 解锁后实际会话的条目数（核对合成内容）。 */
    fun sessionEntryCount(): Int? =
        graph.databaseSession().databaseFlow.value?.rootGroup?.allEntries()?.size

    private companion object {
        /** 库列表扫描落定等待上限（毫秒）。 */
        const val LIST_WAIT_MS = 5_000L
    }
}
