package com.keepasskey.app.ui.screens.edit

import android.graphics.BitmapFactory
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
    /** 断点5：拉起 zxing 扫码，结果以 CharArray 上行回填 TOTP 种子。 */
    val scanTotpQr: () -> Unit,
    /** TASK-15：拉起系统相册 Photo Picker，选图降采样为 ≤128px PNG 后上传。 */
    val pickCustomIcon: () -> Unit
)

/**
 * 装配编辑页的三个选择器。
 *
 * 语义与拆分前逐条一致：
 * - 附件读取跑 `Dispatchers.IO`，空文件与异常均如实提示（不静默丢弃）；
 * - 扫码结果（框架边界 String）**即刻**转 `CharArray` 走安全桥接上行（TASK-10）；
 * - 相册图片先经 [decodeAndScaleToPng] 降采样，解码失败如实提示；
 * - 扫码提示文案在 Composable 内解析后捕获，避免 stale 引用（原 LINT 修正）。
 */
@Composable
internal fun rememberEntryEditPickers(
    viewModel: EntryEditViewModel,
    scope: CoroutineScope
): EntryEditPickers {
    val context = LocalContext.current

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            viewModel.showMessage(UiMessage(R.string.edit_attachment_empty))
                        }
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

    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.onTotpSecretChangeSecure(it.toCharArray()) }
    }

    val customIconOptions by viewModel.customIconOptions.collectAsStateWithLifecycle()
    var decodedCustomIcons by remember { mutableStateOf<List<CustomIconItem>>(emptyList()) }
    LaunchedEffect(customIconOptions) {
        // PNG → ImageBitmap 解码为 CPU 操作，移出主线程
        decodedCustomIcons = withContext(Dispatchers.Default) {
            customIconOptions.mapNotNull { (id, bytes) ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                    CustomIconItem(id, it.asImageBitmap())
                }
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
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

    val scanPrompt = stringResource(R.string.edit_scan_totp_qr)

    return EntryEditPickers(
        decodedCustomIcons = decodedCustomIcons,
        pickAttachment = { attachmentPicker.launch("*/*") },
        scanTotpQr = {
            val options = ScanOptions()
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            options.setPrompt(scanPrompt)
            options.setBeepEnabled(false)
            options.setOrientationLocked(true)
            qrScanner.launch(options)
        },
        pickCustomIcon = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    )
}
