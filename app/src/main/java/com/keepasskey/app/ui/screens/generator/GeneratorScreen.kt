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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.core.security.ProtectedString

/**
 * 生成器页面的**动作端口**（依赖倒置）：把「界面需要的动作」与「谁来实现」解耦。
 *
 * 生产实现是 [GeneratorViewModel]（见 [GeneratorScreen]），预览实现是空实现
 * （见文件末尾的 `GeneratorContentPreview`）——预览因此不再需要构造持有剪贴板 /
 * 数据库会话依赖的真实 ViewModel。接口成员与 `GeneratorViewModel` 既有公开动作一一对应，
 * 不新增、不删减任何业务语义。
 */
interface GeneratorActions {
    fun setMode(mode: GeneratorMode)
    fun regenerate()
    fun copyGeneratedPassword(secret: ProtectedString)
    fun selectHistoryPassword(secret: ProtectedString)
    fun setRandomLength(length: Int)
    fun setUseUpper(enabled: Boolean)
    fun setUseLower(enabled: Boolean)
    fun setUseDigits(enabled: Boolean)
    fun setUseSymbols(enabled: Boolean)
    fun setExcludeAmbiguous(enabled: Boolean)
    fun setWordCount(count: Int)
    fun setSeparator(separator: String)
    fun setCapitalizeWords(enabled: Boolean)
    fun setIncludeNumberInPassphrase(enabled: Boolean)
    fun setMaskPattern(pattern: String)
}

/** 把 [GeneratorViewModel] 适配为 [GeneratorActions]（生产路径唯一实现） */
private fun GeneratorViewModel.asActions(): GeneratorActions = object : GeneratorActions {
    override fun setMode(mode: GeneratorMode) = this@asActions.setMode(mode)
    override fun regenerate() = this@asActions.regenerate()
    override fun copyGeneratedPassword(secret: ProtectedString) = this@asActions.copyGeneratedPassword(secret)
    override fun selectHistoryPassword(secret: ProtectedString) = this@asActions.selectHistoryPassword(secret)
    override fun setRandomLength(length: Int) = this@asActions.setRandomLength(length)
    override fun setUseUpper(enabled: Boolean) = this@asActions.setUseUpper(enabled)
    override fun setUseLower(enabled: Boolean) = this@asActions.setUseLower(enabled)
    override fun setUseDigits(enabled: Boolean) = this@asActions.setUseDigits(enabled)
    override fun setUseSymbols(enabled: Boolean) = this@asActions.setUseSymbols(enabled)
    override fun setExcludeAmbiguous(enabled: Boolean) = this@asActions.setExcludeAmbiguous(enabled)
    override fun setWordCount(count: Int) = this@asActions.setWordCount(count)
    override fun setSeparator(separator: String) = this@asActions.setSeparator(separator)
    override fun setCapitalizeWords(enabled: Boolean) = this@asActions.setCapitalizeWords(enabled)
    override fun setIncludeNumberInPassphrase(enabled: Boolean) =
        this@asActions.setIncludeNumberInPassphrase(enabled)

    override fun setMaskPattern(pattern: String) = this@asActions.setMaskPattern(pattern)
}

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

    GeneratorContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        // 适配器每次重组新建为轻量无状态对象，不持有额外引用，无生命周期负担
        actions = viewModel.asActions(),
        modifier = modifier
    )
}

/**
 * 生成器页面**无状态渲染本体**：只依据 [GeneratorUiState] 与 [actions] 绘制。
 *
 * 拆分理由（纯结构性，行为逐字等价）：有状态入口 [GeneratorScreen] 依赖 `hiltViewModel()`，
 * 在 IDE 预览面板中无法独立渲染；抽出本函数后，预览可传入构造好的状态与空实现 [GeneratorActions]
 * 覆盖随机 / 口令短语 / 掩码三种模式与历史记录的渲染分支。生产路径（含 `userMessage` 的
 * 一次性 Snackbar 消费）完全不变，仍由 [GeneratorScreen] 承担。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneratorContent(
    uiState: GeneratorUiState,
    snackbarHostState: SnackbarHostState,
    actions: GeneratorActions,
    modifier: Modifier = Modifier
) {
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
                // Material3 已将 TabRow 弃用（官方替代 PrimaryTabRow / SecondaryTabRow）。
                // 容器色 / 内容色沿用原显式指定；唯一外观差异是选中指示器由下划线变为
                // 药丸式高亮——该视觉效果**未经真机/模拟器验证**（本仓当前无渲染侧证据）。
                PrimaryTabRow(
                    selectedTabIndex = uiState.mode.ordinal,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(CapsuleShape)
                ) {
                    GeneratorMode.entries.forEach { mode ->
                        val isSelected = uiState.mode == mode
                        Tab(
                            selected = isSelected,
                            onClick = { actions.setMode(mode) },
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
                    onRegenerate = actions::regenerate,
                    onCopy = { actions.copyGeneratedPassword(uiState.currentPassword) }
                )
            }

            // 3. 对应模式的参数配置
            item {
                when (uiState.mode) {
                    GeneratorMode.RANDOM -> RandomModeOptions(uiState = uiState, actions = actions)
                    GeneratorMode.PASSPHRASE -> PassphraseModeOptions(uiState = uiState, actions = actions)
                    GeneratorMode.MASK -> MaskModeOptions(uiState = uiState, actions = actions)
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
                        onSelect = { actions.selectHistoryPassword(historyItem) },
                        onCopy = { actions.copyGeneratedPassword(historyItem) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 预览用空实现动作端口：不触碰剪贴板 / 数据库会话，只让界面可渲染
private object NoOpGeneratorActions : GeneratorActions {
    override fun setMode(mode: GeneratorMode) = Unit
    override fun regenerate() = Unit
    override fun copyGeneratedPassword(secret: ProtectedString) = Unit
    override fun selectHistoryPassword(secret: ProtectedString) = Unit
    override fun setRandomLength(length: Int) = Unit
    override fun setUseUpper(enabled: Boolean) = Unit
    override fun setUseLower(enabled: Boolean) = Unit
    override fun setUseDigits(enabled: Boolean) = Unit
    override fun setUseSymbols(enabled: Boolean) = Unit
    override fun setExcludeAmbiguous(enabled: Boolean) = Unit
    override fun setWordCount(count: Int) = Unit
    override fun setSeparator(separator: String) = Unit
    override fun setCapitalizeWords(enabled: Boolean) = Unit
    override fun setIncludeNumberInPassphrase(enabled: Boolean) = Unit
    override fun setMaskPattern(pattern: String) = Unit
}

@Preview(name = "密码生成器 - 浅色", showBackground = true)
@Preview(name = "密码生成器 - 深色", showBackground = true, uiMode = 0x20 /* UI_MODE_NIGHT_YES */)
@Composable
private fun GeneratorContentPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        GeneratorContent(
            uiState = GeneratorUiState(
                // 预览专用假口令：仅用于界面排版展示，非任何真实生成的密码
                currentPassword = ProtectedString("预览-示例-口令-A1b2C3d4"),
                entropyBits = 96,
                history = listOf(
                    ProtectedString("预览-历史-口令-1"),
                    ProtectedString("预览-历史-口令-2")
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            actions = NoOpGeneratorActions
        )
    }
}
