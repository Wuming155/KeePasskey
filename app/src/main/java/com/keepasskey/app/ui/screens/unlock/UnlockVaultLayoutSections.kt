package com.keepasskey.app.ui.screens.unlock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 解锁页「整合分组卡片」布局段落（§436，2026-10-03 用户提交的 HTML 原型
 * `unlock-redesign` 落地——方案 B：数据库行 + 主密码行整合进同一张分组卡）。
 *
 * 本文件只承接**布局重组**，零业务逻辑：全部回调与状态语义与改动前一致，
 * `UnlockUiState` / `UnlockViewModel` 未动。取色一律走 MaterialTheme 语义令牌
 * （动态取色 / OLED 盘自动跟随），不复刻原型 HTML 的固定深绿色值。
 *
 * 结构拆分原因：`UnlockContentSections.kt` 已在 tier2（400~500）棘轮预算内，
 * 迁出 `UnlockStandardUnlockContent` / `UnlockPasswordSupportingText` 后该文件降档，
 * 本文件新组件全部远低于 400 行（`tools/doc/count_line_tiers.py` 口径）。
 */

/**
 * 分组卡首行：当前数据库概要（icon 块 + 名称 / 状态 + 「切换」尾标）。
 *
 * 两个手势入口都指向同一目标（与 §186 前的旧版概要卡一致）：
 * 整行点击 [onOpen]、尾部「切换」胶囊点击 [onSwitchTap]。
 * 原型的等宽字体文件名以现有 [com.keepasskey.app.ui.theme.MonospacePasswordStyle] 语义近似——
 * 库名按 `titleSmall` 渲染，不额外引入新字体资产。
 */
@Composable
internal fun UnlockDatabaseRow(
    name: String,
    status: String,
    onOpen: () -> Unit,
    onSwitchTap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = stringResource(R.string.cd_active_database),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 尾部「切换」胶囊（独立手势入口，触控目标 ≥ 32dp 高）
        Row(
            modifier = Modifier
                .clip(CapsuleShape)
                .clickable(onClick = onSwitchTap)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.unlock_switch_short),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/**
 * 分组卡容器：数据库行 + 内部分隔线 + 主密码行，同一张卡（§436 原型核心形态）。
 *
 * 内容槽为普通 `@Composable () -> Unit`（非 `BoxScope`），两个槽都由调用方以单节点
 * `Column` 承载，无 Box 同层兄弟叠放风险（`check_box_slot_children.py` 口径）。
 */
@Composable
internal fun UnlockVaultGroupCard(
    databaseRow: @Composable () -> Unit,
    passwordRow: @Composable () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            databaseRow()
            HorizontalDivider(
                modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            passwordRow()
        }
    }
}

/**
 * 主密码输入框的 supporting 槽位（§280 规模门禁同批自 [UnlockStandardUnlockContent] 逐字迁出，
 * 结构性拆分：渲染语义零变化）。
 *
 * ISSUE-P2-355 AC②：锁定期倒计时由 throttleLockoutRemainingMs 状态直驱、每秒刷新——
 * 一次性快照会随用户输入（onPasswordChangeSecure 清提示）消失，直驱行冲不掉；
 * 下方 errorMessage 的锁定快照与此行同源，锁定期内不重复渲染。
 *
 * ISSUE-P3-457（**单节点契约**）：本函数是 `OutlinedTextField.supportingText` 槽的内容，而该槽在
 * Material3 侧落在 `Box(Modifier.layoutId(SupportingId))` 内部（`TextFieldImpl.kt` 的
 * `TextFieldLayout` / `CutoutTextFieldLayout` 两处同形）——**Box 的同层兄弟互相叠放**，
 * 而非上下流动；且外层只 `heightIn(min = MinSupportingTextLineHeight).wrapContentHeight()`，
 * 量到的是**最高子节点**而非子节点之和。⇒ 本槽只能发射**一个**节点：此前四行 Text 平铺直出，
 * 任意两行同时成立即压在同一行上（真机实证：生物识别失败文案「生物识别验证未通过或已取消」
 * 与「已自动载入记住的密钥文件: usr.dat」叠成一行乱码；「主密码错误…」+「剩余 N 次尝试」
 * 同样命中——后者是开启了失败节流时**每次输错都会出现**的常态组合）。
 * 此槽的叠放风险由 `tools/doc/check_box_slot_children.py` 机检（含框架槽与「多发射助手函数」）。
 */
@Composable
internal fun UnlockPasswordSupportingText(uiState: UnlockUiState) {
    // ISSUE-P3-457：Column 是**契约**而非排版偏好——它把四行文案从「Box 同层兄弟」变成
    // 「Column 顺序子节点」，逐行上下分离；单行成立时渲染结果与平铺直出逐像素等价。
    Column {
        val lockoutMs = uiState.throttleLockoutRemainingMs
        val countdownShown = lockoutMs > 0L
        if (countdownShown) {
            Text(
                text = lockoutUiMessage(lockoutMs).resolveText(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        uiState.errorMessage?.let { message ->
            if (!(countdownShown && message.isLockoutCountdown())) {
                Text(
                    text = message.resolveText(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        // ISSUE-P2-355 AC②：失败提示附「剩余 N 次尝试」（节流关闭 / 锁定态 / 已输入时为 null 不呈现）
        if (uiState.errorMessage != null) {
            uiState.throttleAttemptsRemaining?.let { remaining ->
                Text(
                    text = stringResource(R.string.unlock_attempts_remaining, remaining),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        uiState.infoMessage?.let { message ->
            Text(
                text = message.resolveText(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * `ISSUE-P3-472`：「主密码 supported 槽是否有内容」的判据，与 [UnlockPasswordSupportingText]
 * 的四条渲染分支**逐条对齐**（该函数里的每一行都由这里列出的三个状态之一驱动）。
 *
 * 用途：调用方据此决定**要不要挂** `supportingText` 槽——M3 会为**空槽**也预留固有高度
 * （≈20dp，`TextFieldImpl.kt` 的 `supportingIntrinsicHeight` 与槽内是否有文案无关），
 * 空挂会把行高从 56dp 抬到 76dp。改 [UnlockPasswordSupportingText] 的分支时**必须同批改本判据**，
 * 否则会出现「文案在场却没预留空间（被裁）」或「空槽白占高度」。
 */
internal fun hasUnlockPasswordSupportingText(uiState: UnlockUiState): Boolean =
    uiState.throttleLockoutRemainingMs > 0L ||
        uiState.errorMessage != null ||
        uiState.infoMessage != null

/**
 * 完整主密码解锁区（§436 重排为原型布局：分组卡〔数据库行 + 扁平主密码行〕→
 * 高级认证凭证折叠卡〔密钥文件 / 只读开关〕→ 底部主操作）。
 *
 * [databaseRow] 槽由调用方（`UnlockContent`）传入：分组卡首行需要「切换库」导航回调，
 * 该回调的接线锚点须留在 `UnlockScreen.kt`（`UnlockEmptyEntryWiringTest` 判据）。
 * [advancedExpanded] / [onToggleAdvancedExpanded] 状态外提（ISSUE-P3-340 口径：折叠态必须可被预览覆盖）。
 */
@Composable
internal fun UnlockStandardUnlockContent(
    uiState: UnlockUiState,
    databaseRow: @Composable () -> Unit,
    onPasswordChange: (CharArray) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSelectKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit,
    onToggleReadOnly: () -> Unit,
    onUnlock: () -> Unit,
    onSwitchMode: (UnlockMode) -> Unit,
    advancedExpanded: Boolean,
    onToggleAdvancedExpanded: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 分组卡：数据库行 + 内嵌扁平主密码行（同一张卡）
        UnlockVaultGroupCard(
            databaseRow = databaseRow,
            passwordRow = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    // 完整主密码输入框（SecurePasswordField：显示 String 仅存活于组件内部，
                    // CharArray 直达 ViewModel；embeddedFlat = 无边框行形态）
                    // ISSUE-P3-472（真机确认的缺陷，取代 §436 走查回执① 的 48dp 硬高度上限）：
                    // M3 的 `supportingIntrinsicHeight` 会把 supportingText 槽的**固有高度**
                    //（≈20dp，与槽内有没有文案无关）连同上下内边距（无标签各 16dp）**无条件**从正文
                    // 可用高度里扣掉；`.height(48.dp)` 硬压时 48−32−20 ≤ 0 ⇒ 正文区被压成 **0 高度**，
                    // 占位提示与用户键入的内容都画不出来（真机 + `TextFieldImpl.kt` 逐行核对）。
                    // `SecurePasswordField` 用的是 `OutlinedTextField` 的 **String 重载**，无
                    // `contentPadding` 参数 ⇒ 无法靠收窄内边距腾空间（编译期验证）⇒ **必须**解除硬上限。
                    // 同批把 supporting 槽改为**有文案才挂**：否则空槽也白占 20dp，行高会堆到 76dp；
                    // 条件挂载后常态行高 = M3 自然高 56dp（原 48dp，+8dp），有提示时按需变高。
                    SecurePasswordField(
                        label = null,
                        placeholder = stringResource(R.string.unlock_master_password_hint),
                        onPasswordChanged = onPasswordChange,
                        isError = uiState.errorMessage != null,
                        supportingText = if (hasUnlockPasswordSupportingText(uiState)) {
                            { UnlockPasswordSupportingText(uiState) }
                        } else {
                            null
                        },
                        isPasswordVisible = uiState.isPasswordVisible,
                        onToggleVisibility = onTogglePasswordVisibility,
                        onDone = onUnlock,
                        // ISSUE-P1-04：失败/锁定后令牌递增，驱动输入框擦除显示态，与 VM 主密码清零同步
                        wipeToken = uiState.clearPasswordFieldToken,
                        leadingIcon = Icons.Default.Lock,
                        embeddedFlat = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ISSUE-P3-445 AC①：密钥文件高困惑点提示与密钥文件 / 只读开关一并收入
        // 「高级认证凭证」折叠卡（默认展开，功能可见性与改版前一致）
        UnlockAdvancedAuthCard(
            hasKeyFile = uiState.hasKeyFile,
            keyFileName = uiState.keyFileName,
            keyFileSourcePath = uiState.keyFileSourcePath,
            onSelectKeyFile = onSelectKeyFile,
            onClearKeyFile = onClearKeyFile,
            openReadOnly = uiState.openReadOnly,
            onToggleReadOnly = onToggleReadOnly,
            expanded = advancedExpanded,
            onToggleExpand = onToggleAdvancedExpanded
        )

        Spacer(modifier = Modifier.height(18.dp))

        // 解锁主操作按钮：无密码且无密钥文件时禁用（密钥文件单独解锁时允许空密码）
        UnlockSubmitButton(
            enabled = !uiState.isLoading && (uiState.hasPassword || uiState.hasKeyFile),
            isLoading = uiState.isLoading,
            onUnlock = onUnlock
        )

        // ISSUE-P3-368 AC②：主密码路径的打开进度（确定段 0..1 / 不确定段跑马灯）
        UnlockLoadProgressSection(uiState)

        if (uiState.isQuickUnlockAvailable) {
            UnlockSwitchToQuickEntry(onSwitchToQuickUnlock = { onSwitchMode(UnlockMode.QUICK_UNLOCK) })
        }
    }
}
