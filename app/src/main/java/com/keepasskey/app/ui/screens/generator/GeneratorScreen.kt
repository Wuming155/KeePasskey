package com.keepasskey.app.ui.screens.generator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 独立全功能密码生成器页面 (支持随机/Diceware短语/掩码三模式)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen(
    modifier: Modifier = Modifier,
    viewModel: GeneratorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    uiState.userMessage?.let { message ->
        val text = message.resolveText()
        LaunchedEffect(message, text) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearUserMessage()
        }
    }

    // P0 整改：不再在 Composable 内直连 ClipboardManager（该路径缺失定时擦除，
    // 生成的明文密码会永久滞留剪贴板）；统一交给 ViewModel → ClipboardSecurityManager。
    // ISSUE-P2-12：状态持有 ProtectedString，UI 仅在渲染瞬间 readString() 物化明文
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_generator),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 1. 模式切换 Tab
            item {
                TabRow(
                    selectedTabIndex = uiState.mode.ordinal,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(CapsuleShape)
                ) {
                    GeneratorMode.entries.forEach { mode ->
                        val isSelected = uiState.mode == mode
                        Tab(
                            selected = isSelected,
                            onClick = { viewModel.setMode(mode) },
                            text = {
                                Text(
                                    text = stringResource(mode.labelRes),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        )
                    }
                }
            }

            // 2. 主展示卡片：生成的密码与快捷操作
            item {
                // ISSUE-P2-16：Compose Text 只接受 String，UI 渲染边界无法完全避免物化明文；
                // 以 remember 绑定受控容器实例——仅在生成结果变化时物化一次，且收敛于该最小作用域
                val displayPassword = remember(uiState.currentPassword) {
                    uiState.currentPassword.readString()
                }
                GeneratorDisplayCard(
                    password = displayPassword,
                    strengthLabel = uiState.strengthLabel,
                    entropyBits = uiState.entropyBits,
                    onRegenerate = viewModel::regenerate,
                    onCopy = { viewModel.copyGeneratedPassword(uiState.currentPassword) }
                )
            }

            // 3. 对应模式的参数配置
            item {
                when (uiState.mode) {
                    GeneratorMode.RANDOM -> RandomModeOptions(uiState = uiState, viewModel = viewModel)
                    GeneratorMode.PASSPHRASE -> PassphraseModeOptions(uiState = uiState, viewModel = viewModel)
                    GeneratorMode.MASK -> MaskModeOptions(uiState = uiState, viewModel = viewModel)
                }
            }

            // 4. 历史记录 (Recent Generations)
            if (uiState.history.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.gen_history_title, uiState.history.size),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                items(uiState.history) { historyItem ->
                    // ISSUE-P2-16：同上，历史行渲染边界的 String 物化按条目实例 remember，避免重复物化
                    val displayHistory = remember(historyItem) { historyItem.readString() }
                    HistoryPasswordRow(
                        password = displayHistory,
                        onSelect = { viewModel.selectHistoryPassword(historyItem) },
                        onCopy = { viewModel.copyGeneratedPassword(historyItem) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
