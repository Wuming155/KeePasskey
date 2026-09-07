package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import kotlinx.coroutines.launch

/**
 * 两步验证与 TOTP 高级规范映射设置页 (对应 KeePass2Android TrayTOTP 插件兼容设置)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TotpSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onUpdateTotpFieldMapping: (seedField: String, settingsField: String, stepSeconds: Int, digits: Int) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val totpMappingSavedMsg = stringResource(R.string.totp_mapping_saved)

    var seedField by remember(uiState.totpSeedFieldName) { mutableStateOf(uiState.totpSeedFieldName) }
    var settingsField by remember(uiState.totpSettingsFieldName) { mutableStateOf(uiState.totpSettingsFieldName) }
    var stepSeconds by remember(uiState.defaultTotpStepSeconds) { mutableIntStateOf(uiState.defaultTotpStepSeconds) }
    var digits by remember(uiState.defaultTotpDigits) { mutableIntStateOf(uiState.defaultTotpDigits) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.set_totp_entry_title),
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
            // 1. 快速插件兼容方案
            item {
                Text(
                    text = stringResource(R.string.totp_section_presets),
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
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.totp_presets_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = seedField == "TOTP Seed" && settingsField == "TOTP Settings",
                                onClick = {
                                    seedField = "TOTP Seed"
                                    settingsField = "TOTP Settings"
                                },
                                label = { Text(stringResource(R.string.totp_preset_traytotp)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )

                            FilterChip(
                                selected = seedField == "otp" && settingsField == "otp_settings",
                                onClick = {
                                    seedField = "otp"
                                    settingsField = "otp_settings"
                                },
                                label = { Text(stringResource(R.string.totp_preset_keeotp)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }
            }

            // 2. 自定义字段名映射
            item {
                Text(
                    text = stringResource(R.string.totp_section_fields),
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
                        OutlinedTextField(
                            value = seedField,
                            onValueChange = { seedField = it },
                            label = { Text(stringResource(R.string.totp_seed_label)) },
                            placeholder = { Text("TOTP Seed") },
                            leadingIcon = { Icon(Icons.Default.Password, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = settingsField,
                            onValueChange = { settingsField = it },
                            label = { Text(stringResource(R.string.totp_settings_label)) },
                            placeholder = { Text("TOTP Settings") },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 3. 算法参数预设
            item {
                Text(
                    text = stringResource(R.string.totp_section_defaults),
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
                        Column {
                            Text(
                                text = stringResource(R.string.totp_step_label),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15 to R.string.totp_step_15, 30 to R.string.totp_step_30, 60 to R.string.totp_step_60).forEach { (sec, label) ->
                                    FilterChip(
                                        selected = stepSeconds == sec,
                                        onClick = { stepSeconds = sec },
                                        label = { Text(stringResource(label)) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    )
                                }
                            }
                        }

                        Column {
                            Text(
                                text = stringResource(R.string.totp_digits_label),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(6 to R.string.totp_digits_6, 8 to R.string.totp_digits_8).forEach { (d, label) ->
                                    FilterChip(
                                        selected = digits == d,
                                        onClick = { digits = d },
                                        label = { Text(stringResource(label)) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                onUpdateTotpFieldMapping(seedField, settingsField, stepSeconds, digits)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(totpMappingSavedMsg)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.totp_save_btn))
                        }
                    }
                }
            }

            // 4. 说明卡片
            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.totp_info_desc),
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
}
