package com.keepasskey.app.ui.screens.conflict

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.components.BentoCard

/**
 * 云端同步冲突可视化对比与三方合并页面 (参考 KP2A Conflict Resolver)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionScreen(
    onBackClick: () -> Unit,
    onResolveSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConflictResolutionViewModel = hiltViewModel()
) {
    // 遮挡触摸过滤（ISSUE-P2-09 / P3-12）
    ApplyObscuredTouchFilter()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // ISSUE-P3-360 AC①：批量选择 / 合并推送一点即生效 ⇒ 先挂确认（正文说明覆盖范围），确认后才执行
    var pendingConfirm by remember { mutableStateOf<ConflictConfirmAction?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ConflictResolutionEvent.ResolveSuccess -> onResolveSuccess()
            }
        }
    }

    uiState.userMessage?.let { message ->
        val text = message.resolveText()
        LaunchedEffect(message, text) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearUserMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.conflict_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            ConflictResolutionBottomBar(
                isResolving = uiState.isResolving,
                onLaterClick = onBackClick,
                onMergeClick = { pendingConfirm = ConflictConfirmAction.MERGE }
            )
        }
    ) { innerPadding ->
        ConflictResolutionBody(
            innerPadding = innerPadding,
            uiState = uiState,
            viewModel = viewModel,
            onBulkChoice = { pendingConfirm = it }
        )
    }

    // ISSUE-P3-360 AC①：确认后才真正执行批量选择 / 合并推送
    pendingConfirm?.let { action ->
        ConflictActionConfirmDialog(
            action = action,
            conflictCount = uiState.entries.size + uiState.groups.size + (uiState.meta?.fields?.size ?: 0),
            onConfirm = {
                pendingConfirm = null
                when (action) {
                    ConflictConfirmAction.ALL_LOCAL -> viewModel.selectAll(FieldChoice.LOCAL)
                    ConflictConfirmAction.ALL_REMOTE -> viewModel.selectAll(FieldChoice.REMOTE)
                    ConflictConfirmAction.MERGE -> viewModel.applyMerge()
                }
            },
            onDismiss = { pendingConfirm = null }
        )
    }
}

/**
 * 冲突列表主体（自根函数下沉：根函数承载状态编排与 `ApplyObscuredTouchFilter` 首语句锚点，
 * 本函数只做纯呈现编排，令两处均处于函数规模档内）。
 */
@Composable
private fun ConflictResolutionBody(
    innerPadding: PaddingValues,
    uiState: ConflictResolutionUiState,
    viewModel: ConflictResolutionViewModel,
    onBulkChoice: (ConflictConfirmAction) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        // 警示说明卡片
        item {
            ConflictWarningCard(
                conflictCount = uiState.entries.size,
                groupCount = uiState.groups.size,
                metaCount = uiState.meta?.fields?.size ?: 0
            )
        }

        // ISSUE-P3-360 AC①：两侧修改时间 + 「较新」标注（此前 UI 无消费点）
        item {
            ConflictModifiedTimesRow(
                localTime = uiState.localModifiedTime,
                remoteTime = uiState.remoteModifiedTime,
                newerSide = uiState.newerSide
            )
        }

        // 快捷一键批量选择（ISSUE-P3-360 AC①：先弹确认说明覆盖范围）
        item {
            ConflictBulkChoiceRow(
                onSelectAllLocal = { onBulkChoice(ConflictConfirmAction.ALL_LOCAL) },
                onSelectAllRemote = { onBulkChoice(ConflictConfirmAction.ALL_REMOTE) }
            )
        }

        // ISSUE-P2-659：库级 Meta 冲突（库名 / 描述 / 默认用户名 / 颜色 / 历史上限 / 库级自定义数据）。
        // 标量没有「双方保留」语义，只提供逐字段的本地 / 云端选择。
        uiState.meta?.let { meta ->
            item {
                Text(
                    text = stringResource(R.string.conflict_meta_section_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            item {
                ConflictedMetaCard(
                    meta = meta,
                    onFieldChoiceChange = { fieldKey, choice ->
                        viewModel.selectMetaFieldChoice(fieldKey, choice)
                    }
                )
            }
        }

        // 冲突条目列表
        items(uiState.entries, key = { it.id }) { entry ->
            ConflictedEntryCard(
                entry = entry,
                onFieldChoiceChange = { fieldKey, choice ->
                    viewModel.selectFieldChoice(entry.id, fieldKey, choice)
                },
                onModeChange = { mode -> viewModel.selectEntryMode(entry.id, mode) }
            )
        }

        // ISSUE-P2-660：分组冲突（同字段双侧异值）。同步面此前不读 groupConflicts，
        // 这类差异被 KdbxGroupFieldMerge 按 LWW 静默定案 —— 页面必须显式呈现并允许逐组裁决。
        if (uiState.groups.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.conflict_group_section_title, uiState.groups.size),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            items(uiState.groups, key = { "group:${it.id}" }) { group ->
                ConflictedGroupCard(
                    group = group,
                    onModeChange = { mode -> viewModel.selectGroupMode(group.id, mode) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}
