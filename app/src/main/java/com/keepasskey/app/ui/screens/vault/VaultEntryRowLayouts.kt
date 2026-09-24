package com.keepasskey.app.ui.screens.vault

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.EntryIconContent
import com.keepasskey.app.ui.components.PasskeyBadge
import com.keepasskey.app.ui.components.TotpMiniGauge
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.theme.LocalSecurityColors
import com.keepasskey.core.otp.OtpEngine

/**
 * 条目行内部布局组件（ISSUE-P3-29：自 `VaultEntryRows.kt` 拆出，同包同可见性，
 * 原 `private` 提升为 `internal` 以支持跨文件调用；纯结构性拆分，无行为变更）。
 */

/**
 * ISSUE-P3-17：搜索结果行的「所属分组」行。
 * 路径已由状态层拼好（[GroupPathPresenter]），本组件只做绘制。
 */
@Composable
internal fun GroupPathLine(
    groupPath: String,
    fontSizeSp: Int,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(GROUP_PATH_ICON_SIZE_DP.dp)
        )
        Spacer(modifier = Modifier.width(GROUP_PATH_ICON_GAP_DP.dp))
        Text(
            text = groupPath,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = fontSizeSp.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 分组路径行前导图标边长（dp） */
private const val GROUP_PATH_ICON_SIZE_DP = 12

/** 分组路径行前导图标与文案间距（dp） */
private const val GROUP_PATH_ICON_GAP_DP = 4

/**
 * 列表行收藏徽标（ISSUE-P3-297 处置③：收藏此前只有详情页顶栏一个渲染点，
 * 列表侧零展示——现于标题旁以星形徽标呈现，与详情页星标同色语义）。
 */
@Composable
internal fun EntryFavoriteBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.Star,
        contentDescription = stringResource(R.string.cd_favorite),
        tint = LocalSecurityColors.current.warning,
        modifier = modifier.size(16.dp)
    )
}

@Composable
internal fun StandardEntryLayout(
    entry: UiVaultEntry,
    icon: BitmapEntryIcon,
    urlText: String,
    isRecycled: Boolean,
    isBatchMode: Boolean,
    isSelected: Boolean,
    showUsername: Boolean,
    showOtp: Boolean,
    showPasskeyBadge: Boolean,
    showUrl: Boolean,
    densitySpec: ListDensitySpec,
    groupPath: String?,
    onCopyPassword: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    /**
     * ISSUE-P3-184：行内验证码徽标被点击——复制当前 TOTP 码。
     * HOTP 条目**不**渲染该入口（复制而不推进会复用计数器），见下方徽标调用点的 `isHotp` 过滤。
     */
    onCopyTotpCode: () -> Unit = {},
    /**
     * ISSUE-P2-89 / ISSUE-P3-158：TOTP 秒级刻度（窄状态，只在 [EntryTotpBadge] 内读取；缺省值供预览）。
     * 下发刻度而非剩余秒数——剩余秒数由徽标按**条目自身周期**现算。
     */
    totpNowSeconds: State<Long> = remember { mutableLongStateOf(TOTP_PREVIEW_NOW_SECONDS) },
    /** ISSUE-P2-89：本周期实时验证码（`entryId → 验证码`，窄状态；缺省值供预览） */
    totpLiveCodes: State<Map<String, String>> = remember { mutableStateOf(emptyMap()) }
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = densitySpec.rowHorizontalPaddingDp.dp,
            vertical = densitySpec.rowVerticalPaddingDp.dp
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBatchMode) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp).padding(end = 4.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        // 通行密钥条目在无自定义图标时以钥匙图标强调（既有视觉语义）
        val (placeholderIcon, iconTint, containerColor) = when {
            entry.isPasskey -> Triple(Icons.Default.Key, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
            else -> Triple(getVaultIcon(entry.iconName), MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        }

        Box(
            modifier = Modifier
                .size(densitySpec.iconContainerSizeDp.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(containerColor),
            contentAlignment = Alignment.Center
        ) {
            EntryIconContent(
                icon = icon,
                tint = iconTint,
                placeholderIcon = placeholderIcon,
                contentSize = densitySpec.iconContentSizeDp.dp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = densitySpec.titleFontSizeSp.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.isPasskey && showPasskeyBadge) {
                    Spacer(modifier = Modifier.width(6.dp))
                    PasskeyBadge()
                }
                // ISSUE-P3-297 处置③：收藏条目在列表行的星形徽标
                if (entry.isFavorite) {
                    Spacer(modifier = Modifier.width(6.dp))
                    EntryFavoriteBadge()
                }
            }

            // ISSUE-P3-17：搜索结果行的所属分组完整路径（非搜索态不下发，恒不展示）
            if (groupPath != null) {
                Spacer(modifier = Modifier.height(2.dp))
                GroupPathLine(groupPath = groupPath, fontSizeSp = densitySpec.secondaryFontSizeSp)
            }

            if (showUsername || (showUrl && urlText.isNotBlank())) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showUsername) {
                        Text(
                            text = if (entry.username.isNotBlank()) entry.username else stringResource(R.string.cardrow_no_username),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = densitySpec.secondaryFontSizeSp.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (showUsername && showUrl && urlText.isNotBlank()) {
                        Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (showUrl && urlText.isNotBlank()) {
                        Text(
                            text = urlText.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = densitySpec.secondaryFontSizeSp.sp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            if (showOtp && entry.totpCode != null) {
                Spacer(modifier = Modifier.height(3.dp))
                // ISSUE-P2-89：徽标自成一格组合作用域——每秒 tick 只失效本组件，
                // 不牵动整行布局与其余列表行（读取 State 的动作在此发生）
                EntryTotpBadge(
                    entryId = entry.id,
                    fallbackCode = entry.totpCode,
                    liveCodes = totpLiveCodes,
                    nowSeconds = totpNowSeconds,
                    periodSeconds = entry.totpPeriod,
                    // ISSUE-P3-184：TOTP 徽标一次点击即复制（此前必须进详情页才能复制）。
                    // HOTP 传 null ⇒ 不挂点击（其码由持久化计数器决定，复制而不推进会复用计数器；
                    // 取码入口只在详情页的「取下一个码」）。
                    onCopyCode = if (entry.isHotp) null else onCopyTotpCode
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        if (!isBatchMode) {
            if (isRecycled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRestore) {
                        Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.btn_restore), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onPurge) {
                        Icon(Icons.Default.DeleteForever, contentDescription = stringResource(R.string.btn_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                // 列表行仅保留核心「复制密码」，降低误触；用户名复制仍在详情页可用
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onCopyPassword()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy_password), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** 毫秒 / 秒换算：窄通道下发的是**秒级刻度**，[OtpEngine] 取值按毫秒时间戳。 */
private const val MILLIS_PER_SECOND = 1000L

/** 条目周期缺失 / 非法（`<= 0`）时的兜底周期——与 [OtpEngine.getRemainingSeconds] 默认值同值。 */
private const val DEFAULT_TOTP_PERIOD_SECONDS = 30

/**
 * ISSUE-P2-89 / ISSUE-P3-158：预览 / 截图测试用的 TOTP 秒级刻度缺省值（生产恒由窄通道传入真实值）。
 *
 * 取 0 而非任意时刻：`30 - 0 % 30 = 30`，使预览与既有截图基线逐像素一致（原缺省值为「剩余 30 秒」）。
 */
internal const val TOTP_PREVIEW_NOW_SECONDS = 0L

/**
 * ISSUE-P2-89 / ISSUE-P3-158：列表行内 TOTP 徽标（验证码 + 迷你倒计时环）。
 *
 * 单独成组件是**性能契约**的一部分：函数体内才读取 [liveCodes] / [nowSeconds]
 * 两个状态对象的值，故每秒 tick 只令本组件重组一次，行内其余部分与其余列表行均不受影响
 * （此前徽标直接内联在 [StandardEntryLayout] 中，秒级状态读取落在整行的作用域上）。
 *
 * 验证码取值 `liveCodes[entryId] ?: fallbackCode`：前者是本周期实时之码（窄通道），
 * 后者是投影层即时计算的兜底值——覆盖「条目刚出现、尚未等到下一拍刷新」的首帧。
 *
 * 剩余秒数与进度环分母一律取**条目自身周期**（ISSUE-P3-158）：此前写死 30 秒，
 * `period != 30` 的条目倒计时相位与环比例都是错的。
 *
 * ISSUE-P3-184：徽标原本只是纯展示，`onCopyCode` 非空时整块徽标变为**可点**——
 * 一次点击即复制当前验证码（此前必须点行进详情、再滚到验证码卡片才能复制）。
 * 点击层加在徽标**内部**，故该小块区域的点击/长按不再冒泡到行（有意：徽标处点击语义是「复制」）。
 * 不额外增加内边距或背景，保持与既有截图基线逐像素一致；可发现性由 `onClickLabel` 承担
 * （TalkBack 会播报「复制动态验证码」这一动作名）。
 */
@Composable
private fun EntryTotpBadge(
    entryId: String,
    fallbackCode: String,
    liveCodes: State<Map<String, String>>,
    nowSeconds: State<Long>,
    periodSeconds: Int,
    /** 非空则可点复制；传 null 保持纯展示（HOTP 用） */
    onCopyCode: (() -> Unit)? = null
) {
    // 与验证码大卡 / 详情页同一色语义：常规=success，紧迫由 TotpMiniGauge 表达
    val totpCodeColor = LocalSecurityColors.current.success
    val period = if (periodSeconds > 0) periodSeconds else DEFAULT_TOTP_PERIOD_SECONDS
    val remainingSeconds = OtpEngine.getRemainingSeconds(
        timestampMillis = nowSeconds.value * MILLIS_PER_SECOND,
        periodSeconds = period
    )
    val copyLabel = stringResource(R.string.cd_copy_totp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onCopyCode != null) {
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = copyLabel, role = Role.Button, onClick = onCopyCode)
        } else {
            Modifier
        }
    ) {
        Text(
            text = liveCodes.value[entryId] ?: fallbackCode,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = totpCodeColor,
                letterSpacing = 1.sp
            )
        )
        Spacer(modifier = Modifier.width(6.dp))
        TotpMiniGauge(remainingSeconds = remainingSeconds, totalSeconds = period)
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "条目标准行 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "条目标准行 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun StandardEntryLayoutPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        StandardEntryLayout(
            entry = com.keepasskey.app.ui.preview.PreviewEntryLogin,
            icon = com.keepasskey.app.ui.preview.PreviewDefaultIcon,
            urlText = "https://example.com",
            isRecycled = false,
            isBatchMode = false,
            isSelected = false,
            showUsername = true,
            showOtp = true,
            showPasskeyBadge = true,
            showUrl = true,
            densitySpec = ListDensityPresenter.specOf(com.keepasskey.app.ui.screens.settings.ListDensity.NORMAL),
            groupPath = null,
            onCopyPassword = {},
            onRestore = {},
            onPurge = {}
        )
    }
}
