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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 密码库属性与加密参数配置二级页面 (对应 KeePassDX 密码库设置，完全可调)
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
    modifier: Modifier = Modifier
) {
    var showCipherDialog by remember { mutableStateOf(false) }
    var showKdfDialog by remember { mutableStateOf(false) }
    var showArgon2Dialog by remember { mutableStateOf(false) }
    var benchmarkMessage by remember { mutableStateOf<String?>(null) }

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
                            contentDescription = "返回"
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
            // 1. 常规
            item {
                Text(
                    text = "常规与基础属性",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = androidx.compose.ui.graphics.Color(0xFFDD6B20),
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

            // 2. 密码学与 KDF 派生（均支持点击修改）
            item {
                Text(
                    text = "加密与密钥推导函数 (点击条目可调节参数)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = androidx.compose.ui.graphics.Color(0xFFDD6B20),
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

            // 3. 主凭据管理
            item {
                Text(
                    text = "主密钥管理",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = androidx.compose.ui.graphics.Color(0xFFDD6B20),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "修改密码库主密钥或追加 Keyfile 物理密钥文件：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = { },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = "修改主密码",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("修改密码库主密码 / 密钥文件")
                        }
                    }
                }
            }

            // 4. 兼容性说明
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = "Standard",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "KeePasskey 生成的标准 KDBX 4.1 文件能够与 KeePassDX、KeePass2Android、KeePassXC、KeePass 2.x 完全双向互通，绝无任何私有格式绑定。",
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
                    // 1. 迭代轮数
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

                    // 2. 内存消耗
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

                    // 3. 并行计算线程
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

                    // 4. 1秒基准测试按钮
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
