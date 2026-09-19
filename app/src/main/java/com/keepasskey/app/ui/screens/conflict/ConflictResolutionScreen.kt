package com.keepasskey.app.ui.screens.conflict

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                onMergeClick = viewModel::applyMerge
            )
        }
    ) { innerPadding ->
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
                ConflictWarningCard(conflictCount = uiState.entries.size)
            }

            // 快捷一键批量选择
            item {
                ConflictBulkChoiceRow(
                    onSelectAllLocal = { viewModel.selectAll(FieldChoice.LOCAL) },
                    onSelectAllRemote = { viewModel.selectAll(FieldChoice.REMOTE) }
                )
            }

            // 冲突条目列表
            items(uiState.entries, key = { it.id }) { entry ->
                ConflictedEntryCard(
                    entry = entry,
                    onFieldChoiceChange = { fieldKey, choice ->
                        viewModel.selectFieldChoice(entry.id, fieldKey, choice)
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ConflictedEntryCard(
    entry: ConflictedEntryItem,
    onFieldChoiceChange: (String, FieldChoice) -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = entry.groupPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            entry.fields.forEach { field ->
                FieldDiffRow(
                    field = field,
                    onChoiceSelected = { choice -> onFieldChoiceChange(field.fieldKey, choice) }
                )
            }
        }
    }
}

@Composable
private fun FieldDiffRow(
    field: ConflictedField,
    onChoiceSelected: (FieldChoice) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = field.fieldName,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )

        // 本地选项
        ConflictFieldChoiceRow(
            choice = FieldChoice.LOCAL,
            labelRes = R.string.conflict_local_version,
            labelColor = MaterialTheme.colorScheme.primary,
            value = field.localValue,
            selectedChoice = field.selectedChoice,
            isSensitive = field.isSensitive,
            onChoiceSelected = onChoiceSelected
        )

        // 云端选项
        ConflictFieldChoiceRow(
            choice = FieldChoice.REMOTE,
            labelRes = R.string.conflict_remote_version,
            labelColor = MaterialTheme.colorScheme.secondary,
            value = field.remoteValue,
            selectedChoice = field.selectedChoice,
            isSensitive = field.isSensitive,
            onChoiceSelected = onChoiceSelected
        )
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "冲突条目对比卡片 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "冲突条目对比卡片 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ConflictedEntryCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        ConflictedEntryCard(
            entry = com.keepasskey.app.ui.screens.conflict.ConflictedEntryItem(
                id = "preview-conflict-1",
                title = "预览冲突条目",
                groupPath = "预览根目录 / 网站登录",
                fields = listOf(
                    com.keepasskey.app.ui.screens.conflict.ConflictedField(
                        fieldKey = "Title",
                        fieldName = "标题",
                        localValue = "预览标题（本地）",
                        remoteValue = "预览标题（云端）"
                    ),
                    com.keepasskey.app.ui.screens.conflict.ConflictedField(
                        fieldKey = "Password",
                        fieldName = "密码",
                        localValue = "预览掩码值（本地）",
                        remoteValue = "预览掩码值（云端）",
                        selectedChoice = com.keepasskey.app.ui.screens.conflict.FieldChoice.REMOTE,
                        isSensitive = true
                    )
                )
            ),
            onFieldChoiceChange = { _, _ -> }
        )
    }
}
