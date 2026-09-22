package com.keepasskey.app.ui.screens.authenticator

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.security.ApplyObscuredTouchFilter
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.MonospaceTotpStyle
import com.keepasskey.core.otp.OtpEngine

/**
 * 独立双重认证验证码 (TOTP) 集中管理页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatorScreen(
    onEntryClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AuthenticatorViewModel = hiltViewModel()
) {
    // 遮挡触摸过滤（ISSUE-P2-09 / P3-12）
    ApplyObscuredTouchFilter()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ISSUE-P3-182：两条窄通道**有意不 collect 成值**——保留 State 对象，令读取动作落在
    // 卡片自身的组合作用域内（见下方 items 内注释），秒级 tick 只失效可见卡片。
    val nowSeconds = viewModel.nowSeconds.collectAsStateWithLifecycle()
    val liveCodes = viewModel.liveCodes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    uiState.userMessage?.let { message ->
        val text = message.resolveText()
        LaunchedEffect(message, text) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearUserMessage()
        }
    }

    // P0 整改：不再在 Composable 内直连 ClipboardManager（该路径缺失定时擦除，
    // TOTP 验证码会永久滞留剪贴板）；统一交给 ViewModel → ClipboardSecurityManager。
    val copyCode: (String) -> Unit = viewModel::copyTotpCode

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.cd_search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (uiState.searchQuery.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.auth_search_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                BasicTextField(
                                    value = uiState.searchQuery,
                                    onValueChange = viewModel::onSearchQueryChange,
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.onSearchQueryChange("") },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.cd_clear_search),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        // ISSUE-P3-261 AC⑧：列表内容层动效与主题 MotionScheme 同族（见 `items` 内 `animateItem`）
        val itemFadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        val itemPlacementSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // TOTP 账号卡片列表
            items(uiState.items, key = { it.entryId }) { item ->
                // 本行内才读取两条窄通道（State 读取注册在**卡片**的组合作用域上）：
                // 每秒 tick 只令可见卡片重组，`uiState` 与其 items 在周期内零重建。
                val code = if (item.isHotp) {
                    // HOTP 之码由用户显式推进计数器决定（推进后重新投影即得新码），
                    // 不在时间通道内——通道里的值恒为推进前的旧码（ISSUE-P3-182）
                    item.codeRaw
                } else {
                    liveCodes.value[item.entryId] ?: item.codeRaw
                }
                TotpLargeCard(
                    item = item,
                    remainingSeconds = OtpEngine.getRemainingSeconds(
                        timestampMillis = nowSeconds.value * MILLIS_PER_SECOND,
                        periodSeconds = item.periodSeconds
                    ),
                    code = code,
                    onClick = { onEntryClick(item.entryId) },
                    // 复制「当前所见之码」：窄通道已出新周期之码时，必须复制新码而非投影兜底码
                    onCopy = { code?.let(copyCode) },
                    // ISSUE-P3-49：HOTP 取码需推进计数器（复制当前码会重复使用同一计数器）
                    onAdvanceHotp = { viewModel.advanceHotpAndCopy(item.entryId) },
                    // ISSUE-P3-261 AC⑧：TOTP 卡片的增删 / 筛选后重排走同族动效
                    modifier = Modifier.animateItem(
                        fadeInSpec = itemFadeSpec,
                        placementSpec = itemPlacementSpec,
                        fadeOutSpec = itemFadeSpec
                    )
                )
            }

            // 空状态
            if (uiState.items.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.QrCode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (uiState.searchQuery.isNotBlank()) {
                                    stringResource(R.string.auth_empty_search_result)
                                } else {
                                    stringResource(R.string.auth_empty_title)
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.auth_empty_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/** 毫秒 / 秒换算：窄通道下发的是**秒级刻度**，[OtpEngine] 取值按毫秒时间戳 */
private const val MILLIS_PER_SECOND = 1000L

@Composable
private fun TotpLargeCard(
    item: TotpCardItem,
    /**
     * 剩余秒数（ISSUE-P3-182）：由调用方按窄通道刻度 + 条目自身周期现算后传入——
     * 本组件不再从整页状态里读它，故整页 `items` 不随秒级节拍重建。
     */
    remainingSeconds: Int,
    /**
     * 当前应展示 / 复制的码（可空：种子缺失 / 解析失败时不可复制）：
     * TOTP 取窄通道实时码、缺失时回落投影之码；HOTP 恒取投影之码（见调用方注释）。
     */
    code: String?,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    // ISSUE-P3-261 AC⑧：`modifier` 必须排在**第一个可选参数**位（lint `ModifierParameter`）
    modifier: Modifier = Modifier,
    // ISSUE-P3-49：HOTP 取码（推进计数器并复制）
    onAdvanceHotp: () -> Unit = {}
) {
    // HOTP 无时间步长：不作紧迫着色、不显示倒计时环
    // 与 TotpMiniGauge 同一色语义：常规=success，紧迫（≤5s）=danger，避免跨页蓝/绿混用
    val isUrgent = !item.isHotp && remainingSeconds <= 5
    val securityColors = com.keepasskey.app.ui.theme.LocalSecurityColors.current
    val gaugeColor by animateColorAsState(
        targetValue = if (isUrgent) securityColors.danger else securityColors.success,
        label = "gaugeColor"
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 头部：服务图标与名称
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getVaultIcon(item.iconName),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.account.isNotBlank()) {
                            Text(
                                text = item.account,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 微型环形倒计时器（HOTP 无时间步长，不呈现）
                if (!item.isHotp) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            // ISSUE-P3-158：分母取条目自身周期（此前写死 30，period != 30 时环比例错误）
                            progress = { remainingSeconds / item.periodSeconds.coerceAtLeast(1).toFloat() },
                            modifier = Modifier.size(28.dp),
                            color = gaugeColor,
                            strokeWidth = 3.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Text(
                            text = "$remainingSeconds",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = gaugeColor,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // 中部：大号分段动态码 + 独立复制按钮
            // ISSUE-P3-49：HOTP 的动作是「取下一个码」（推进计数器并复制），TOTP 为「复制当前码」
            // 防误触：整行不再作为点击热区，仅右侧复制胶囊按钮可点（48dp 触控）
            val actionable = item.isHotp || code != null
            val onAction: () -> Unit = { if (item.isHotp) onAdvanceHotp() else onCopy() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTotpCode(code),
                    style = MonospaceTotpStyle.copy(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        color = if (actionable) gaugeColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                Surface(
                    color = if (actionable) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CapsuleShape,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(enabled = actionable) { onAction() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (item.isHotp) Icons.Default.Autorenew else Icons.Default.ContentCopy,
                            contentDescription = stringResource(
                                if (item.isHotp) R.string.cd_hotp_advance else R.string.cd_copy_totp
                            ),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (item.isHotp) R.string.detail_hotp_advance else R.string.btn_copy
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "验证码卡片 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "验证码卡片 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun TotpLargeCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        TotpLargeCard(
            item = com.keepasskey.app.ui.screens.authenticator.TotpCardItem(
                entryId = "preview-totp-1",
                title = "预览站点",
                account = "demo@example.com",
                codeRaw = "123456",
                iconName = "key",
                url = "https://example.com",
                isHotp = false
            ),
            // 预览固定取「剩余 18 秒」：与改动前的预览 / 截图基线同值（生产由窄通道刻度现算）
            remainingSeconds = 18,
            code = "123456",
            onClick = {},
            onCopy = {},
            onAdvanceHotp = {}
        )
    }
}
