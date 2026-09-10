package com.keepasskey.app.ui.screens.settings.subscreens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 导出文件名时间戳格式（不含日期分隔符更兼容远端文件名约束） */
private val EXPORT_FILE_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * 系统诊断与调试日志二级设置页 (对应 KeePass2Android 调试日志系统)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onDebugLogToggle: (Boolean) -> Unit,
    onVerboseSyncLogToggle: (Boolean) -> Unit,
    onRefreshLogs: () -> Unit = {},
    onClearLogs: () -> Unit = {},
    onExportLogs: (Uri) -> Unit = {},
    exportFeedback: UiMessage? = null,
    onClearExportFeedback: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val logRefreshedMsg = stringResource(R.string.debug_log_refreshed)
    val logClearedMsg = stringResource(R.string.debug_log_cleared)
    var showExportConfirmDialog by remember { mutableStateOf(false) }

    // 真实进程内调试日志快照（SyncCoordinator / Unlock 等运行时事件），不再使用硬编码演示数据
    val logLines = uiState.debugLogLines

    // 断点整改：SAF 另存为——选择目标后交给 ViewModel 真实写盘（此前仅弹假 Snackbar）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) onExportLogs(uri)
    }

    // 断点整改：导出结果（成功/失败）经 ViewModel 反馈流回到本页 Snackbar
    exportFeedback?.let { message ->
        val text = message.resolveText()
        LaunchedEffect(message, text) {
            snackbarHostState.showSnackbar(text)
            onClearExportFeedback()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.debug_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 日志记录总开关
            item {
                Text(
                    text = stringResource(R.string.debug_section_options),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DebugSwitchRow(
                            icon = Icons.Default.BugReport,
                            title = stringResource(R.string.debug_log_title),
                            // ISSUE-P3-03 (43f)：总开关——关闭后普通诊断事件与详细日志均不记录
                            // （导出审计留痕不受影响，走独立审计通道）
                            subtitle = stringResource(R.string.debug_log_sub),
                            checked = uiState.debugLogEnabled,
                            onCheckedChange = onDebugLogToggle
                        )

                        DebugSwitchRow(
                            icon = Icons.Default.CloudSync,
                            title = stringResource(R.string.debug_verbose_title),
                            // ISSUE-P3-03 (43f)：详细模式是叠加层——需总开关（诊断日志）开启才可见
                            subtitle = stringResource(R.string.debug_verbose_sub) +
                                stringResource(R.string.debug_verbose_depends),
                            checked = uiState.verboseSyncLog,
                            onCheckedChange = onVerboseSyncLogToggle
                        )
                    }
                }
            }

            // 2. 实时日志预览控制台
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.debug_preview_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    IconButton(
                        onClick = {
                            onRefreshLogs()
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(logRefreshedMsg)
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.debug_cd_refresh), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (logLines.isEmpty()) {
                            Text(
                                text = stringResource(R.string.debug_log_empty),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF94A3B8)
                            )
                        }
                        logLines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                ),
                                color = when {
                                    line.contains("[ERROR]") -> Color(0xFFF87171)
                                    line.contains("[DEBUG]") -> Color(0xFF38BDF8)
                                    line.contains("[INFO]") -> Color(0xFF4ADE80)
                                    line.contains("[WARN]") -> Color(0xFFFBBF24)
                                    else -> Color(0xFFE2E8F0)
                                }
                            )
                        }
                    }
                }
            }

            // 3. 操作操作卡片：导出 / 清除
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { showExportConfirmDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.debug_export_btn))
                        }

                        OutlinedButton(
                            onClick = {
                                onClearLogs()
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(logClearedMsg)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.debug_clear_btn))
                        }
                    }
                }
            }

            // 4. 隐私安全承诺
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.debug_privacy_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showExportConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExportConfirmDialog = false },
            title = { Text(stringResource(R.string.debug_export_dialog_title)) },
            text = {
                Text(
                    text = stringResource(R.string.debug_export_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(onClick = {
                    showExportConfirmDialog = false
                    // 断点整改：呼起真实 SAF 另存为，导出经 ViewModel 脱敏写盘
                    val suggestedName =
                        "keepasskey-debug-log-" + LocalDateTime.now().format(EXPORT_FILE_TS_FORMAT) + ".txt"
                    exportLauncher.launch(suggestedName)
                }, enabled = logLines.isNotEmpty()) {
                    Text(stringResource(R.string.debug_export_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirmDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun DebugSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
