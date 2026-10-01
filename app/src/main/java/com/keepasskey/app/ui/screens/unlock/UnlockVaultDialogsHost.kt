package com.keepasskey.app.ui.screens.unlock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.ui.screens.database.CreateVaultWizardDialog
import com.keepasskey.app.ui.screens.database.DatabasePickerViewModel
import com.keepasskey.app.ui.screens.database.KeyFileDeliveryState
import com.keepasskey.app.ui.screens.database.KeyFileOneTimeSaveDialog
import com.keepasskey.app.ui.screens.database.OpenExistingVaultDialog

/**
 * 解锁页空状态两枚入口的**就地对话框宿主**（用户裁决 2026-10-01：弹框直接在首页弹出）。
 *
 * 原口径（ISSUE-P2-424 AC① / ISSUE-P3-427）是导航到库管理页并自动展开对应对话框，
 * 实测投诉：弹框背后就是该页自带的「新建密码库 / 导入已有库」入口，与来源页形成
 * 嵌套重复。现改为不经库管理页，解锁页就地承载对话框——对话框组件与业务动作
 * **完全复用库管理页同一套实现**（[DatabasePickerViewModel] + 同包对话框组件），
 * 本宿主只做状态投影与回调接线，零业务逻辑。
 *
 * 建库 / 导入成功后对话框由 ViewModel 内部关闭；登记生效后 UnlockViewModel 对
 * `getDatabases()` 的既有观察会把空状态切换为解锁表单，无需任何导航。
 */
@Composable
internal fun UnlockVaultDialogsHost(
    pickerViewModel: DatabasePickerViewModel = hiltViewModel()
) {
    val pickerUiState by pickerViewModel.uiState.collectAsStateWithLifecycle()
    // ISSUE-P3-21：生成型密钥文件的一次性交付（复合密钥第二因子，丢失即无法解锁）
    val keyFileDelivery by pickerViewModel.keyFileDelivery.collectAsStateWithLifecycle()
    // ISSUE-P2-424 AC③：打开对话框的云账号预填包（对话框消费后即弃持）
    val openVaultPrefill by pickerViewModel.openVaultPrefill.collectAsStateWithLifecycle()

    // 生成型密钥文件的 SAF 另存（与库管理页同一契约：只上行 Uri，写盘由 ViewModel 完成）
    val keyFileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(pickerViewModel::saveGeneratedKeyFileTo) }

    // 新建密码库向导（含云端直建位置）
    if (pickerUiState.showCreateDialog) {
        CreateVaultWizardDialog(
            onDismiss = pickerViewModel::closeCreateDialog,
            onConfirm = pickerViewModel::createDatabase,
            onWeakPasswordConfirmed = pickerViewModel::noteWeakMasterPasswordConfirmed,
            isBusy = pickerUiState.isLoading,
            cloudSnapshot = pickerUiState.cloudSnapshot
        )
    }

    // 打开已有 KDBX 文件对话框（本地 / WebDAV / S3 三来源 + 云账号预填与来源预选）
    if (pickerUiState.showOpenSourceDialog) {
        OpenExistingVaultDialog(
            onDismiss = pickerViewModel::closeOpenSourceDialog,
            onConfirm = pickerViewModel::importDatabaseFromSource,
            browseState = pickerViewModel.remoteBrowseState.collectAsState().value,
            onBrowseWebDav = pickerViewModel::browseRemoteWebDav,
            onBrowseS3 = pickerViewModel::browseRemoteS3,
            onDismissBrowse = pickerViewModel::dismissRemoteBrowse,
            prefill = openVaultPrefill,
            onPrefillConsumed = pickerViewModel::consumeOpenVaultPrefill,
            onSourceSelected = pickerViewModel::noteOpenVaultSource
        )
    }

    // 生成型密钥文件的一次性保存提示——必须当场交付
    (keyFileDelivery as? KeyFileDeliveryState.PendingSave)?.let { pending ->
        KeyFileOneTimeSaveDialog(
            suggestedFileName = pending.suggestedFileName,
            onSaveClick = { keyFileSaveLauncher.launch(pending.suggestedFileName) },
            onSkipClick = pickerViewModel::dismissKeyFileDelivery
        )
    }
}
