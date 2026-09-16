package com.keepasskey.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 主操作（Filled Button）**禁用态**的统一配色与边界（ISSUE-P3-132 ③）。
 *
 * 背景：`ISSUE-P3-130`（§95）曾把「解锁 / 重扫 / 同步主按钮」的禁用态**显式**写成 MD3 的
 * `onSurface @ 12%`。该取值是 MD3 规范默认，但外部 UI 评审指出：按钮落在 `background`
 * 画布上时形同消失。本仓以导出预览图实测复核，**结论成立**——
 * 禁用填充栅格化为 `#DCDDE1`，对 `background #F8F9FC` 仅 **1.28:1**（117 729 px 命中），
 * 形状仅靠色差、无任何边界。
 *
 * 处置：填充改用 `surfaceContainerHighest`（MD3 里明确存在的容器色角色，禁用态可用的
 * 低强调底色），并补一道 **1dp `outline` 描边**（该令牌已同期修正为不透明，
 * 浅色实测 4.26:1）——禁用态仍为低强调（文字保持 `onSurface @ 38%`），
 * 但控件边界不再随画布消失。**不**改变启用态配色，**不**降低任何安全语义。
 */
@Composable
fun disabledPrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
)

/** 与 [disabledPrimaryButtonColors] 配套的禁用态描边（1dp `outline`）。 */
@Composable
fun disabledPrimaryButtonBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

/** MD3 禁用态文字不透明度（`onSurface @ 38%`），与规范一致。 */
private const val DISABLED_CONTENT_ALPHA = 0.38f
