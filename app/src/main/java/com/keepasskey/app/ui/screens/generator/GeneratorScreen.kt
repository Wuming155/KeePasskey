package com.keepasskey.app.ui.screens.generator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

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
    val onCopy: (String) -> Unit = viewModel::copyGeneratedPassword

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
                GeneratorDisplayCard(
                    password = uiState.currentPassword,
                    strengthLabel = uiState.strengthLabel,
                    entropyBits = uiState.entropyBits,
                    onRegenerate = viewModel::regenerate,
                    onCopy = { onCopy(uiState.currentPassword) }
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
                    HistoryPasswordRow(
                        password = historyItem,
                        onSelect = { viewModel.selectHistoryPassword(historyItem) },
                        onCopy = { onCopy(historyItem) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GeneratorDisplayCard(
    password: String,
    strengthLabel: UiMessage,
    entropyBits: Int,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit
) {
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    val animatedRotation by animateFloatAsState(
        targetValue = rotationAngle,
        animationSpec = tween(durationMillis = 350),
        label = "rotate"
    )
    val haptic = LocalHapticFeedback.current

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CapsuleShape
                ) {
                    Text(
                        text = strengthLabel.resolveText(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                IconButton(onClick = {
                    // 重新生成：ContextClick 轻震对应「新值产生」时刻，与旋转动画同步
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    rotationAngle += 360f
                    onRegenerate()
                }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_regenerate),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.rotate(animatedRotation)
                    )
                }
            }

            // 大字号密码展示区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = password,
                    style = MonospacePasswordStyle.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            PasswordStrengthBar(entropyBits = entropyBits, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CapsuleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.cd_copy_password), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RandomModeOptions(
    uiState: GeneratorUiState,
    viewModel: GeneratorViewModel
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.gen_password_length, uiState.randomLength),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Slider(
                value = uiState.randomLength.toFloat(),
                onValueChange = { viewModel.setRandomLength(it.toInt()) },
                valueRange = 6f..64f,
                steps = 57,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )

            OptionSwitchRow(title = stringResource(R.string.gen_opt_upper), checked = uiState.useUpper, onCheckedChange = viewModel::setUseUpper)
            OptionSwitchRow(title = stringResource(R.string.gen_opt_lower), checked = uiState.useLower, onCheckedChange = viewModel::setUseLower)
            OptionSwitchRow(title = stringResource(R.string.gen_opt_digits), checked = uiState.useDigits, onCheckedChange = viewModel::setUseDigits)
            OptionSwitchRow(title = stringResource(R.string.gen_opt_symbols), checked = uiState.useSymbols, onCheckedChange = viewModel::setUseSymbols)
            OptionSwitchRow(
                title = stringResource(R.string.gen_opt_exclude_ambiguous),
                subtitle = stringResource(R.string.gen_opt_exclude_ambiguous_sub),
                checked = uiState.excludeAmbiguous,
                onCheckedChange = viewModel::setExcludeAmbiguous
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassphraseModeOptions(
    uiState: GeneratorUiState,
    viewModel: GeneratorViewModel
) {
    val spaceSeparator = stringResource(R.string.gen_separator_space)
    val separators = listOf("-", "_", spaceSeparator, ".", "/")

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.gen_word_count, uiState.wordCount),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Slider(
                value = uiState.wordCount.toFloat(),
                onValueChange = { viewModel.setWordCount(it.toInt()) },
                valueRange = 3f..8f,
                steps = 4,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )

            Text(
                text = stringResource(R.string.gen_separator_label),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                separators.forEach { sep ->
                    val actualSep = if (sep == spaceSeparator) " " else sep
                    val isSelected = uiState.separator == actualSep
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setSeparator(actualSep) },
                        label = { Text(sep) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            OptionSwitchRow(
                title = stringResource(R.string.gen_opt_capitalize),
                subtitle = stringResource(R.string.gen_opt_capitalize_sub),
                checked = uiState.capitalizeWords,
                onCheckedChange = viewModel::setCapitalizeWords
            )

            OptionSwitchRow(
                title = stringResource(R.string.gen_opt_append_number),
                subtitle = stringResource(R.string.gen_opt_append_number_sub),
                checked = uiState.includeNumberInPassphrase,
                onCheckedChange = viewModel::setIncludeNumberInPassphrase
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaskModeOptions(
    uiState: GeneratorUiState,
    viewModel: GeneratorViewModel
) {
    val presets: List<Pair<Int, String>> = listOf(
        Pair(R.string.gen_mask_preset_pin, "dddddd"),
        Pair(R.string.gen_mask_preset_serial, "xxxx-xxxx-xxxx-xxxx"),
        Pair(R.string.gen_mask_preset_upper_digits, "uuuu-dddd-uuuu-dddd"),
        Pair(R.string.gen_mask_preset_mixed, "uull-ddss-uull")
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.gen_mask_rules),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = uiState.maskPattern,
                onValueChange = viewModel::setMaskPattern,
                label = { Text(stringResource(R.string.gen_mask_format)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.gen_mask_presets_title),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { (labelRes, pattern) ->
                    val isSelected = uiState.maskPattern == pattern
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setMaskPattern(pattern) },
                        label = { Text(stringResource(labelRes)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HistoryPasswordRow(
    password: String,
    onSelect: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = password,
                style = MonospacePasswordStyle.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_password),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
