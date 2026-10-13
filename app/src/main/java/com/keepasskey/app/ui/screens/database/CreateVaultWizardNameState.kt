package com.keepasskey.app.ui.screens.database

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.ui.components.MasterPasswordPolicy
import com.keepasskey.core.files.NewVaultNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 向导表单状态（§280）：名称 / 密码对 / 密钥文件 / 预设 / 存储位置。
 * 主密码以 [CharArray] 承载；离场由 [CreateVaultWizardDialog] 擦除。
 */
internal class CreateVaultWizardState {
    var vaultName by mutableStateOf(NewVaultNames.DEFAULT_NAME)
    var nameError by mutableStateOf<Int?>(null)
    var checkingName by mutableStateOf(true)
    var suggestedName by mutableStateOf<String?>(null)
    var passwordChars by mutableStateOf(CharArray(0))
    var confirmChars by mutableStateOf(CharArray(0))
    var passwordVisible by mutableStateOf(false)
    var useKeyFile by mutableStateOf(false)
    var keyFileChoice by mutableStateOf(KeyFileSourceChoice.GENERATE)
    var selectedKeyFilePath by mutableStateOf("")
    var selectedKeyFileName by mutableStateOf("")
    // ISSUE-P2-85：预设改为类型化枚举——芯片与落盘共用 `CreateVaultPreset` 单一真相源
    var selectedPreset by mutableStateOf(CreateVaultPreset.DEFAULT)
    // ISSUE-P2-229：新建库的落地位置三选一；选「自选位置」时必须已挑定文档
    var storageLocation by mutableStateOf(VaultStorageLocation.INTERNAL)
    var selectedVaultUri by mutableStateOf("")
    var selectedVaultFileName by mutableStateOf("")

    val isLocationValid get() = storageLocation != VaultStorageLocation.EXTERNAL || selectedVaultUri.isNotBlank()
    val isKeyFileValid get() = !useKeyFile || keyFileChoice == KeyFileSourceChoice.GENERATE ||
        selectedKeyFilePath.isNotBlank()
    // ISSUE-P2-288 AC①：主口令长度下限硬阻断（单一判据 MasterPasswordPolicy，与改密共用）
    val isFormValid get() = vaultName.isNotBlank() && nameError == null && !checkingName &&
        passwordChars.size >= MasterPasswordPolicy.MIN_LENGTH &&
        passwordChars.contentEquals(confirmChars) && isKeyFileValid && isLocationValid

    fun wipePasswords() {
        passwordChars.fill('0')
        confirmChars.fill('0')
    }
}

@Composable
internal fun ObserveNewVaultName(state: CreateVaultWizardState) {
    val context = LocalContext.current
    LaunchedEffect(state.vaultName, state.storageLocation) {
        state.checkingName = true
        val checked = withContext(Dispatchers.IO) {
            try {
                val normalized = NewVaultNames.normalize(state.vaultName)
                val collides = state.storageLocation == VaultStorageLocation.INTERNAL &&
                    NewVaultNames.occupied(NewVaultNames.target(context.filesDir, normalized))
                if (collides) R.string.err_create_collision to NewVaultNames.suggest(context.filesDir, normalized)
                else null to null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                R.string.err_create_invalid_name to null
            }
        }
        state.nameError = checked.first
        state.suggestedName = checked.second
        state.checkingName = false
    }
    LaunchedEffect(Unit) {
        val suggestion = withContext(Dispatchers.IO) {
            try { NewVaultNames.suggest(context.filesDir) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                NewVaultNames.DEFAULT_NAME
            }
        }
        if (state.vaultName == NewVaultNames.DEFAULT_NAME) state.vaultName = suggestion
    }
}
