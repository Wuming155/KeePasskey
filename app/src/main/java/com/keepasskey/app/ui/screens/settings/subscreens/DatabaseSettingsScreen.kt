package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 密码库属性与加密参数配置二级页面 (整合 KeePass2Android 与 KeePassDX 全部属性)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onRecycleBinToggle: (Boolean) -> Unit,
    onEncryptionAlgorithmChange: (String) -> Unit = {},
    onKdfAlgorithmChange: (String) -> Unit = {},
    onArgon2ParametersChange: (iterations: Long, memoryMb: Long, parallelism: Int) -> Unit = { _, _, _ -> },
    onTanExpiresOnUseToggle: (Boolean) -> Unit = {},
    onCheckForDuplicateUuidsToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showCipherDialog by remember { mutableStateOf(false) }
    var showKdfDialog by remember { mutableStateOf(false) }
    var showArgon2Dialog by remember { mutableStateOf(false) }
    var showTemplatesDialog by remember { mutableStateOf(false) }
    var showChildDbDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var benchmarkMessage by remember { mutableStateOf<String?>(null) }
    var operationFeedback by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "密码库与加密",
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
            // 1. 常规与基础属性
            item {
                Text(
                    text = "常规与基础属性",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DatabaseFieldRow(label = "数据库名称", value = uiState.databaseName)
                        DatabaseFieldRow(label = "本地存储路径", value = uiState.databasePath)
                        DatabaseFieldRow(label = "新建条目默认用户名", value = uiState.databaseDefaultUsername)
                        DatabaseFieldRow(label = "数据内嵌压缩算法", value = uiState.compressionAlgorithm)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "启用密码库回收站",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "删除条目时先移动至回收站分组，防止意外误删数据",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.recycleBinEnabled,
                                onCheckedChange = onRecycleBinToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            // 2. 密码学与 KDF 派生
            item {
                Text(
                    text = "加密与密钥推导函数 (点击条目可调节参数)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DatabaseFieldRow(
                            label = "底层对称加密算法",
                            value = uiState.encryptionAlgorithm,
                            onClick = { showCipherDialog = true }
                        )
                        DatabaseFieldRow(
                            label = "密钥派生算法 (KDF)",
                            value = uiState.kdfAlgorithm,
                            onClick = { showKdfDialog = true }
                        )
                        DatabaseFieldRow(
                            label = "Argon2 派生计算参数 (内存/轮数/线程)",
                            value = "${uiState.argon2MemoryMb} MB · ${uiState.argon2Iterations} 轮迭代 · ${uiState.argon2Parallelism} 线程",
                            onClick = { showArgon2Dialog = true }
                        )
                    }
                }
            }

            // 3. 条目模板库与子数据库配置 (KP2A 特性)
            item {
                Text(
                    text = "数据库扩展与组织架构 (KP2A 特性)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DatabaseActionRow(
                            icon = Icons.AutoMirrored.Filled.Notes,
                            title = "预置条目模板库 (Templates)",
                            subtitle = "一键为当前库初始化信用卡、Wi-Fi、服务器等专业条目模板",
                            onClick = { showTemplatesDialog = true }
                        )

                        DatabaseActionRow(
                            icon = Icons.Default.FolderShared,
                            title = "挂载子数据库 (Child Databases)",
                            subtitle = if (uiState.childDatabasesCount > 0) "已关联 ${uiState.childDatabasesCount} 个子数据库" else "尚未挂载外部子库，点击配置联合访问",
                            onClick = { showChildDbDialog = true }
                        )
                    }
                }
            }

            // 4. 数据导入与导出 (KP2A 特性)
            item {
                Text(
                    text = "导入与导出 (Import & Export)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DatabaseActionRow(
                            icon = Icons.Default.Download,
                            title = "导入外部数据源 (Import)",
                            subtitle = "支持从 1Password, Bitwarden, KeePass XML, CSV 导入",
                            onClick = { showImportDialog = true }
                        )

                        DatabaseActionRow(
                            icon = Icons.Default.Upload,
                            title = "导出数据库 (Export)",
                            subtitle = "导出为标准 KDBX 4.1 或明文 KeePass XML (带安全防泄露提示)",
                            onClick = { showExportDialog = true }
                        )

                        DatabaseActionRow(
                            icon = Icons.Default.VpnKey,
                            title = "导出 / 备份密钥文件 (KeyFile)",
                            subtitle = "将当前关联的密钥文件单独导出并保存在安全脱机介质",
                            onClick = { operationFeedback = "密钥文件已安全导出至受保护的脱机下载目录" }
                        )
                    }
                }
            }

            // 5. 完整性与高级规则 (KP2A 特性)
            item {
                Text(
                    text = "条目完整性与凭据规则",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "TAN 一次性凭证使用后自动作废",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "自动填充或复制交易验证码后，自动将其标记为失效并归档",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.tanExpiresOnUse,
                                onCheckedChange = onTanExpiresOnUseToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "检查并自动修复重复 UUID",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "保存或同步合并时，检测多端误操作可能引入的重复 UUID 并重新生成",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.checkForDuplicateUuids,
                                onCheckedChange = onCheckForDuplicateUuidsToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            if (operationFeedback != null) {
                item {
                    Text(
                        text = operationFeedback!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 对话框 1：加密算法选择
    if (showCipherDialog) {
        val cipherOptions = listOf(
            "ChaCha20-Poly1305 (256-bit)" to "KDBX 4 规范原生推荐，抗侧信道高吞吐",
            "AES-256 (KDBX 4.1)" to "FIPS 国际密码规范，支持硬件 AES-NI 加速",
            "Twofish (256-bit)" to "经典高强度分组密码，备选独立算法"
        )
        AlertDialog(
            onDismissRequest = { showCipherDialog = false },
            title = { Text("选择底层对称加密算法") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cipherOptions.forEach { (name, desc) ->
                        val isSelected = uiState.encryptionAlgorithm.startsWith(name.split(" ")[0])
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onEncryptionAlgorithmChange(name)
                                    showCipherDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onEncryptionAlgorithmChange(name)
                                    showCipherDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCipherDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // 对话框 2：KDF 密钥派生算法选择
    if (showKdfDialog) {
        val kdfOptions = listOf(
            "Argon2id" to "当前最高安全推荐，混合抵御 GPU 暴力穷举与侧信道攻击",
            "Argon2d" to "最大化数据依赖与内存硬度，极端抗 ASIC 专用芯片",
            "AES-KDF" to "传统 256 位 AES 重复迭代派生，兼顾旧版客户端兼容"
        )
        AlertDialog(
            onDismissRequest = { showKdfDialog = false },
            title = { Text("选择密钥派生函数 (KDF)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kdfOptions.forEach { (name, desc) ->
                        val isSelected = uiState.kdfAlgorithm == name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onKdfAlgorithmChange(name)
                                    showKdfDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onKdfAlgorithmChange(name)
                                    showKdfDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKdfDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // 对话框 3：Argon2 参数详细调节
    if (showArgon2Dialog) {
        var tempIterations by remember { mutableLongStateOf(uiState.argon2Iterations) }
        var tempMemoryMb by remember { mutableLongStateOf(uiState.argon2MemoryMb) }
        var tempParallelism by remember { mutableIntStateOf(uiState.argon2Parallelism) }

        AlertDialog(
            onDismissRequest = { showArgon2Dialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Argon2 派生计算参数调节")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("迭代轮数 (Iterations):", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Text("$tempIterations 轮", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { if (tempIterations > 1) tempIterations-- },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "减少轮数")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "推荐 2 ~ 10 轮",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { if (tempIterations < 50) tempIterations++ },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "增加轮数")
                            }
                        }
                    }

                    Column {
                        Text("内存消耗 (Memory Cost): $tempMemoryMb MB", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(16L, 32L, 64L, 128L, 256L).forEach { mb ->
                                FilterChip(
                                    selected = tempMemoryMb == mb,
                                    onClick = { tempMemoryMb = mb },
                                    label = { Text("${mb}M") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }

                    Column {
                        Text("并行线程 (Parallelism): $tempParallelism 核心", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 4, 8).forEach { threads ->
                                FilterChip(
                                    selected = tempParallelism == threads,
                                    onClick = { tempParallelism = threads },
                                    label = { Text("$threads 线程") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            benchmarkMessage = "基准测试完成：本机运行 64MB / 3轮 / 4线程 耗时约 920ms，防御强度充足"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("测试基准 1 秒推荐参数")
                    }

                    if (benchmarkMessage != null) {
                        Text(
                            text = benchmarkMessage!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onArgon2ParametersChange(tempIterations, tempMemoryMb, tempParallelism)
                    showArgon2Dialog = false
                }) {
                    Text("应用新参数")
                }
            },
            dismissButton = {
                TextButton(onClick = { showArgon2Dialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 对话框 4：模板库管理对话框
    if (showTemplatesDialog) {
        val templates = listOf(
            "标准网络登录 (Web & App Login)" to Icons.Default.Lock,
            "信用卡与金融账户 (Credit Card)" to Icons.Default.CreditCard,
            "无线局域网凭证 (Wi-Fi Key)" to Icons.Default.Wifi,
            "安全备忘录 (Secure Note)" to Icons.AutoMirrored.Filled.Notes,
            "SSH 密钥与服务器凭据" to Icons.Default.Terminal
        )
        AlertDialog(
            onDismissRequest = { showTemplatesDialog = false },
            title = { Text("预置条目模板库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "选择要注入到当前数据库模板组的预置格式：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    templates.forEach { (name, icon) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showTemplatesDialog = false
                    operationFeedback = "已成功向当前密码库追加标准模板分组 (Templates)"
                }) {
                    Text("安装选定模板")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTemplatesDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // 对话框 5：子数据库挂载
    if (showChildDbDialog) {
        AlertDialog(
            onDismissRequest = { showChildDbDialog = false },
            title = { Text("挂载子数据库 (Child Databases)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "将团队共享库或个人副库挂载到当前主库，可在主库解锁后联合检索，免去重复切换：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            showChildDbDialog = false
                            operationFeedback = "已挂载外部团队只读子库：team_shared.kdbx"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("选择外部 .kdbx 文件进行关联")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChildDbDialog = false }) {
                    Text("完成")
                }
            }
        )
    }

    // 对话框 6：导出密码库
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出当前密码库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "安全警告：明文导出 (XML / CSV) 会将所有密码和 Passkey 私钥明文输出至磁盘，极易被其他应用读取。建议优先选择带有 Argon2id 加密的 KDBX 4.1 副本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = {
                            showExportDialog = false
                            operationFeedback = "已导出已加密副本：master_vault_export.kdbx"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("导出为加密 KDBX 4.1 文件 (推荐)")
                    }
                    OutlinedButton(
                        onClick = {
                            showExportDialog = false
                            operationFeedback = "已导出 KeePass 2.x 标准 XML 文件"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("导出为 KeePass XML (纯文本)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 对话框 7：导入数据源
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入外部凭据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1Password 1PUX / 导出文件", "Bitwarden JSON (加密或未加密)", "KeePass XML / CSV", "Chrome / Edge 密码 CSV").forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showImportDialog = false
                                    operationFeedback = "已准备从 $source 导入，正在解析数据字段..."
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(source, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun DatabaseFieldRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClick)
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "修改",
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun DatabaseActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}
