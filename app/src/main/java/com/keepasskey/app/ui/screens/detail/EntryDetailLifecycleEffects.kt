package com.keepasskey.app.ui.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 详情页生命周期副作用编排（§211 自 [EntryDetailScreen] 下沉，逐字搬动、零行为变更；
 * §280 规模门禁同批再迁出至本文件，`private` 放宽为 `internal` 供同包调用）：
 * 进入即绑定 entryId、销毁即擦除 ViewModel 明文（M1）、删除成功一次性回退导航（ISSUE-P3-48）、
 * 进入时刷新进阶显示偏好快照（ISSUE-P3-17）。状态所有权仍在 [EntryDetailScreen]（三态 SAF 不迁）。
 */
@Composable
internal fun EntryDetailLifecycleEffects(
    entryId: String?,
    viewModel: EntryDetailViewModel,
    onEntryDeleted: () -> Unit
) {
    LaunchedEffect(entryId) {
        viewModel.setEntryId(entryId)
    }

    // M1 整改：离开详情页（返回导航 / 目的地销毁）时擦除 ViewModel 内按需解密的全部明文
    DisposableEffect(entryId) {
        onDispose { viewModel.onScreenDisposed() }
    }

    // ISSUE-P3-48：删除成功后返回列表——条目已移入回收站（或已在站内被彻底删除），
    // 详情页不再有对应实体，停留会呈现「条目不存在」，故一次性回退导航。
    val entryDeleted by viewModel.entryDeleted.collectAsStateWithLifecycle()
    LaunchedEffect(entryDeleted) {
        if (entryDeleted) onEntryDeleted()
    }

    // ISSUE-P3-17：进入详情页时刷新进阶显示偏好快照（遮掩默认值 / 所属分组开关）
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }
}
