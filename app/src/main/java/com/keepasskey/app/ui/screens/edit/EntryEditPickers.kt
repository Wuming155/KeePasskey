package com.keepasskey.app.ui.screens.edit

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.CustomIconItem
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 编辑页三个系统选择器（SAF 附件 / TOTP 扫码 / 相册图标）与已解码图标池的聚合句柄。
 *
 * ISSUE-P3-31 批次 C：由 `EntryEditScreen.kt`（原 712 行）按**纯结构性拆分**搬出。
 */
internal data class EntryEditPickers(
    /** 已解码的库内自定义图标池（PNG → ImageBitmap，解码在 Default 调度器完成）。 */
    val decodedCustomIcons: List<CustomIconItem>,
    /** 断点1：拉起 SAF 任意文件选择，选中后随编辑会话提交附件。 */
    val pickAttachment: () -> Unit,
    /** 断点5：打开受保护扫码对话框（ISSUE-P3-319），结果以 CharArray 上行回填 TOTP 种子。 */
    val scanTotpQr: () -> Unit,
    /** TASK-15：拉起系统相册 Photo Picker，选图降采样为 ≤128px PNG 后上传。 */
    val pickCustomIcon: () -> Unit
)

/**
 * `ISSUE-P3-295` AC②：单附件添加上界——**与解析侧预算同一数字**
 * （[com.keepasskey.database.xml.AttachmentSizeLimits.MAX_ATTACHMENT_BYTES]）。
 */
private val MAX_ATTACHMENT_BYTES: Long =
    com.keepasskey.database.xml.AttachmentSizeLimits.MAX_ATTACHMENT_BYTES

/** 超限提示：数值经参数注入，避免在文案里再写一份上限。
 *  ISSUE-P2-311：上界值（64 MiB − 1）按 1 MiB **向上取整**显示为 64——floor 会显示 63，
 *  与「附件不超过 64 MiB 即可添加」的实际语义相悖。 */
private fun attachmentTooLargeMessage(): UiMessage =
    UiMessage(
        R.string.edit_attachment_too_large,
        listOf((MAX_ATTACHMENT_BYTES + 1024L * 1024L - 1) / (1024L * 1024L))
    )

/**
 * 装配编辑页的三个选择器。
 *
 * 语义与拆分前逐条一致：
 * - 附件读取跑 `Dispatchers.IO`，空文件与异常均如实提示（不静默丢弃）；
 * - 扫码结果（框架边界 String）**即刻**转 `CharArray` 走安全桥接上行（TASK-10）；
 * - 相册图片先经 [decodeAndScaleToPng] 降采样，解码失败如实提示；
 * - 扫码提示文案（原 ScanOptions prompt）随 ISSUE-P3-319 迁移至对话框标题文案，此处不再解析。
 *
 * ISSUE-P3-305：原 105 行单函数按「三个选择器各自成器」拆为下方三个私有工厂
 * （[rememberAttachmentPicker] / [rememberDecodedCustomIcons] / [rememberCustomIconPicker]），
 * 装配序列、`remember`/`LaunchedEffect` 的调用顺序与各回调体逐行搬运，行为零变更。
 *
 * ISSUE-P3-319：TOTP 扫码由 zxing `ScanContract` 拉起独立 CaptureActivity 改为**置位
 * 对话框状态**、渲染本文件内的 [TotpScanDialog]（CameraX 取景 + zxing:core 解码，
 * 加固面等效迁移见该组件 KDoc）；解码结果同样即刻转 CharArray 走
 * `onTotpSecretChangeSecure` 上行。
 */
@Composable
internal fun rememberEntryEditPickers(
    viewModel: EntryEditViewModel,
    scope: CoroutineScope
): EntryEditPickers {
    val attachmentPicker = rememberAttachmentPicker(viewModel, scope)

    var showTotpScanDialog by remember { mutableStateOf(false) }
    // PD-47：扫码对话框 FLAG_SECURE 跟随设置页「禁止截屏与录屏」开关
    val flagSecureEnabled by viewModel.flagSecureEnabled.collectAsStateWithLifecycle()

    val decodedCustomIcons = rememberDecodedCustomIcons(viewModel)
    val photoPicker = rememberCustomIconPicker(viewModel, scope)

    if (showTotpScanDialog) {
        TotpScanDialog(
            flagSecureEnabled = flagSecureEnabled,
            onDecoded = viewModel::onTotpSecretChangeSecure,
            onDismiss = { showTotpScanDialog = false }
        )
    }

    return EntryEditPickers(
        decodedCustomIcons = decodedCustomIcons,
        pickAttachment = { attachmentPicker.launch("*/*") },
        scanTotpQr = { showTotpScanDialog = true },
        pickCustomIcon = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    )
}

/** SAF 附件选择器（ISSUE-P3-295 AC②：先按申报长度拦一次，再整份读入；异常与空文件如实提示）。 */
@Composable
private fun rememberAttachmentPicker(
    viewModel: EntryEditViewModel,
    scope: CoroutineScope
): ManagedActivityResultLauncher<String, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    // ISSUE-P3-295 AC②：单附件尺寸上界——**先按 ContentResolver 申报长度拦一次**，
                    // 再把整份字节读进内存；顺序反过来就等于「读爆内存之后才判超限」。
                    // 上界与解析侧预算同源（`AttachmentSizeLimits.MAX_ATTACHMENT_BYTES`），禁两套数字。
                    val declaredLength = runCatching {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                    }.getOrNull()
                    if (declaredLength != null && declaredLength > MAX_ATTACHMENT_BYTES) {
                        withContext(Dispatchers.Main) { viewModel.showMessage(attachmentTooLargeMessage()) }
                        return@launch
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            viewModel.showMessage(UiMessage(R.string.edit_attachment_empty))
                        }
                        return@launch
                    }
                    // 兜底：申报长度不可得（provider 返回 UNKNOWN_LENGTH）或流实际更长时二次判定
                    if (bytes.size > MAX_ATTACHMENT_BYTES) {
                        bytes.fill(0)
                        withContext(Dispatchers.Main) { viewModel.showMessage(attachmentTooLargeMessage()) }
                        return@launch
                    }
                    val displayName = queryDisplayName(context, uri) ?: "attachment.bin"
                    withContext(Dispatchers.Main) {
                        viewModel.addAttachment(displayName, formatAttachmentSize(bytes.size), bytes)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        viewModel.showMessage(UiMessage(R.string.edit_attachment_empty))
                    }
                }
            }
        }
    }
}

/** 库内自定义图标池的位图解码（PNG → ImageBitmap 为 CPU 操作，移出主线程）。 */
@Composable
private fun rememberDecodedCustomIcons(viewModel: EntryEditViewModel): List<CustomIconItem> {
    val customIconOptions by viewModel.customIconOptions.collectAsStateWithLifecycle()
    var decodedCustomIcons by remember { mutableStateOf<List<CustomIconItem>>(emptyList()) }
    LaunchedEffect(customIconOptions) {
        decodedCustomIcons = withContext(Dispatchers.Default) {
            customIconOptions.mapNotNull { (id, bytes) ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                    CustomIconItem(id, it.asImageBitmap())
                }
            }
        }
    }
    return decodedCustomIcons
}

/** 相册 Photo Picker（TASK-15：选图降采样为 ≤128px PNG 后上传，解码失败如实提示）。 */
@Composable
private fun rememberCustomIconPicker(
    viewModel: EntryEditViewModel,
    scope: CoroutineScope
): ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val pngBytes = rawBytes?.let { decodeAndScaleToPng(it) }
                    withContext(Dispatchers.Main) {
                        if (pngBytes != null) {
                            viewModel.onCustomIconUploaded(pngBytes)
                        } else {
                            viewModel.showMessage(UiMessage(R.string.icon_invalid_not_png))
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        viewModel.showMessage(UiMessage(R.string.icon_invalid_not_png))
                    }
                }
            }
        }
    }
}
