package com.keepasskey.app.ui.screens.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.keepasskey.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TOTP 扫码对话框的「从相册导入二维码」通路（ISSUE-P3-334）。
 *
 * ## 为何存在
 *
 * [TotpScanDialog] 原先只有 CameraX 取景一条通路：用户手持二维码截图 / 已保存的二维码图片时
 * 无法识别，相机权限被拒时也没有替代手段。本组件在对话框内提供系统 Photo Picker 选图入口，
 * **相机权限被拒态同样渲染**，成为权限缺失时的兜底识别通路。
 *
 * ## 处理链路与线程口径
 *
 * 1. 选图：`PickVisualMedia`（Photo Picker，无存储权限依赖）；
 * 2. 读流 + 位图解码：`Dispatchers.IO`，**有界解码**——先按 `inJustDecodeBounds` 取尺寸，再按
 *    [MAX_GALLERY_IMAGE_DIMENSION] 算 `inSampleSize`，防止全尺寸照片解出亿级像素数组 OOM；
 * 3. 像素灰度化 + zxing 解码：`Dispatchers.Default`（CPU 密集段，工程规则「调度器语义」），
 *    按 4 个朝向重试（复用相机帧通路的 [rotateYPlane90]，覆盖 EXIF 旋转；镜像朝向不覆盖——
 *    实拍 / 截图二维码不会镜像）；
 * 4. 交付：与相机通路同口径——解码命中即刻转 `CharArray` 上行 [onDecoded]，随即 [onDismiss]；
 *    未识别到 / 读取失败在对话框内各自如实提示（不静默、不关闭、不回显图片或解码内容）。
 *
 * ## 敏感数据铁律
 *
 * 解码结果是框架边界的 `String`（不可避免，与相机通路同口径），**即刻**转 `CharArray` 上行，
 * 不新增任何 String 落地 / 持久化路径；解码失败路径不触碰解码内容、日志无明文。
 */

/** 相册图有界解码的单边像素上界：防全尺寸照片解出超大像素数组 OOM（AC②，命名常量禁魔法数字）。 */
internal const val MAX_GALLERY_IMAGE_DIMENSION = 2400

/**
 * 扫码对话框内的「从相册导入」按钮 + 进行中 / 失败态提示。
 *
 * @param onDecoded 解码成功回调：参数为二维码文本即刻转出的 CharArray，消费侧负责清零。
 * @param onDismiss 解码成功后关闭对话框（与相机通路「命中即关」同口径）。
 */
@Composable
internal fun TotpGalleryImport(
    onDecoded: (CharArray) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 解码进行中：禁用按钮防重复选图；失败态：非空即展示对应文案（下次选图前清零）
    var isDecoding by remember { mutableStateOf(false) }
    var errorMessageRes by remember { mutableStateOf<Int?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            errorMessageRes = null
            isDecoding = true
            scope.launch {
                try {
                    val frame = withContext(Dispatchers.IO) { loadGalleryPixels(context, uri) }
                    if (frame == null) {
                        errorMessageRes = R.string.edit_scan_gallery_read_error
                    } else {
                        val decoded = withContext(Dispatchers.Default) {
                            decodeQrFromPixels(frame.first, frame.second, frame.third)
                        }
                        if (decoded != null) {
                            // 框架边界 String 即刻转 CharArray（敏感数据铁律），交付一次后关闭对话框
                            onDecoded(decoded.toCharArray())
                            onDismiss()
                        } else {
                            errorMessageRes = R.string.edit_scan_gallery_no_qr
                        }
                    }
                } catch (t: Throwable) {
                    // 读流 / 解码异常：如实提示，对话框保持打开供更换图片重试
                    errorMessageRes = R.string.edit_scan_gallery_read_error
                } finally {
                    isDecoding = false
                }
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        TextButton(
            onClick = {
                pickerLauncher.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            enabled = !isDecoding
        ) {
            Text(stringResource(R.string.edit_scan_from_gallery))
        }
        val errorRes = errorMessageRes
        when {
            isDecoding -> Text(
                text = stringResource(R.string.edit_scan_gallery_decoding),
                style = MaterialTheme.typography.bodySmall
            )
            errorRes != null -> {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(errorRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 读取相册图并有界解码为像素帧：返回 (ARGB 像素, 宽, 高)；流打不开 / 图片不可解返回 null。
 *
 * 有界策略：先 `inJustDecodeBounds` 量尺寸，按 [MAX_GALLERY_IMAGE_DIMENSION] 取 `inSampleSize`
 * （2 的幂降采样，zxing 解码不需要原图像素密度，2400px 边长对屏幕 / 打印二维码足够）。
 * EXIF 朝向不在此处理——[decodeQrFromPixels] 的 4 朝向重试已覆盖旋转（见该函数 KDoc）。
 *
 * **`inJustDecodeBounds = true` 的 `decodeStream` 恒返回 `null`**（官方契约：只填
 * `outWidth`/`outHeight`、不分配位图），**返回值不可判空**——只验 `outWidth / outHeight > 0`
 * 作可解性判定（`ISSUE-P3-336`：曾以 `use { decodeStream(...) } != null` 判读尺寸成功，
 * 恒 false ⇒ 每次选图都报「图片读取失败」，解码根本不会执行）。该缺陷由
 * `TotpGalleryImportDecodeTest` 的源码静态守卫锁定回归。
 */
private fun loadGalleryPixels(
    context: Context,
    uri: Uri
): Triple<IntArray, Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // 官方两步加载口径第一步：调用只为填充 outWidth/outHeight，返回值恒 null、故意不接
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = gallerySampleSize(bounds.outWidth, bounds.outHeight)
    }
    val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    } ?: return null
    return try {
        pixelFrameOf(bitmap)
    } finally {
        bitmap.recycle()
    }
}

/** 按单边像素上界 [MAX_GALLERY_IMAGE_DIMENSION] 算 2 的幂降采样率（不低于 1）。 */
private fun gallerySampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    val longestSide = maxOf(width, height)
    while (longestSide / sampleSize > MAX_GALLERY_IMAGE_DIMENSION) {
        sampleSize *= 2
    }
    return sampleSize
}

/** 位图 → ARGB 像素数组（位图由调用方负责回收）。 */
private fun pixelFrameOf(bitmap: Bitmap): Triple<IntArray, Int, Int> {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return Triple(pixels, width, height)
}

/**
 * 相册像素帧 QR 解码（纯函数，宿主单测入口；ISSUE-P3-334 AC④）。
 *
 * 链路：ARGB 像素 → 亮度灰度 `Y = (299R + 587G + 114B) / 1000` → [PlanarYUVLuminanceSource]
 * （只消费 Y 平面，灰度图即合法入参）→ [HybridBinarizer]；未命中则经 [rotateYPlane90]
 * 逐朝向重试，4 个方向均失败返回 null。
 *
 * **非全不透明像素先合成到白底**（`ISSUE-P3-336` 同批加固）：相册常见透明底 QR PNG 经
 * `Bitmap.getPixels` 还原时 a=0 像素的 RGB 为 0，直接灰度化会把整图读成全黑、必解不出；
 * 按 `out = rgb·a/255 + 255·(255−a)/255` 合成到白底后与不透明图等价。
 *
 * 4 朝向与相机帧通路同口径：相册图可能带 EXIF 旋转（相机实拍二维码），zxing 对旋转后的
 * 码字方向敏感，只有全朝向重试才与拍摄角度无关地可解；镜像朝向不覆盖（实拍 / 截图不镜像）。
 * 每次调用新建 [MultiFormatReader]（非线程安全，独立调用免共享状态）。
 */
internal fun decodeQrFromPixels(pixels: IntArray, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    val luma = ByteArray(width * height)
    for (i in 0 until width * height) {
        val color = pixels[i]
        val alpha = (color ushr 24) and 0xFF
        var r = (color shr 16) and 0xFF
        var g = (color shr 8) and 0xFF
        var b = color and 0xFF
        if (alpha != 255) {
            // 合成到白底：rgb 按 alpha 缩放（a=0 ⇒ 全白），消除透明像素 RGB=0 的全黑陷阱
            r = (r * alpha + 255 * (255 - alpha)) / 255
            g = (g * alpha + 255 * (255 - alpha)) / 255
            b = (b * alpha + 255 * (255 - alpha)) / 255
        }
        luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }
    var data = luma
    var w = width
    var h = height
    repeat(4) {
        try {
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: ReaderException) {
            // NotFoundException / ChecksumException / FormatException 同根：换方向重试
            //（相机帧通路只吞 NotFoundException；此处单图多朝向，三类 ReaderException 均属「本朝向未命中」）
        }
        val rotated = rotateYPlane90(data, w, h)
        data = rotated.first
        w = rotated.second
        h = rotated.third
    }
    return null
}
