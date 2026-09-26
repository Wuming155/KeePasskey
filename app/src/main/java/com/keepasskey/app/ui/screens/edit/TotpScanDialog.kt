package com.keepasskey.app.ui.screens.edit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.keepasskey.app.R
import com.keepasskey.app.security.SecureDialogWindowEffect
import com.keepasskey.app.security.dialogWindowOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TOTP 种子二维码扫码对话框（ISSUE-P3-319）。
 *
 * ## 迁移背景与依赖口径
 *
 * 替代 `com.journeyapps:zxing-android-embedded`（2023 后停更，取景建立在已废弃的
 * Camera1 API 上）：取景改为 [androidx.camera.view.PreviewView]（CameraX 1.6.2），
 * 解码改为 `com.google.zxing:core` 3.5.4 纯算法 [MultiFormatReader]（仅 QR_CODE，
 * 与原 `ScanOptions.QR_CODE` 等价）——零网络、零遥测、零 ML Kit。
 *
 * ## 承载与加固迁移口径（ISSUE-P3-71 / P3-103 的等效覆盖；PD-47 改判见下）
 *
 * 对话框由 Compose [Dialog] 创建**独立窗口**，挂在编辑页所在 Activity 的组合内
 * （`FLAG_SECURE` 是窗口级属性，不会从 Activity 窗口传播，见 [SecureDialogWindowEffect]
 * 的缺口说明）。原 `SecureCaptureActivity` 的三层防护在本对话框逐项等效落地：
 * - `FLAG_SECURE`：**跟随设置页「禁止截屏与录屏」开关**（`flagSecureEnabled`，`PD-47` 2026-09-26 改判，
 *   推翻 ISSUE-P3-71 以来的无条件遮罩口径）——开关开 ⇒ `DialogProperties.securePolicy` 传
 *   `SecureFlagPolicy.SecureOn` + [SecureDialogWindowEffect] 施加；开关关 ⇒ `SecureFlagPolicy.SecureOff`
 *   + 包装不施加（可截屏）。对话框窗口该 flag 的实际决定者是 `securePolicy`（`ISSUE-P2-246`：
 *   默认 `Inherit` 会按宿主窗口清除），故**不得**改回无条件 `SecureOn` 或省略该参数；
 * - `setHideOverlayWindows(true)`：在 [ScanDialogWindowHardening] 内对**对话框窗口**
 *   直接施加（权限 `HIDE_OVERLAY_WINDOWS` 已在 Manifest 显式声明）——**不随开关变化**；
 * - `decorView.filterTouchesWhenObscured = true`：由 [SecureDialogWindowEffect]
 *   统一施加（遮挡态下丢弃整棵视图子树的触摸，反点击劫持）——**不随开关变化**（`flagSecure=false`
 *   只跳过 `FLAG_SECURE`，过滤照常）。
 *
 * 静态守卫：`SensitiveWindowHardeningTest` 锁定「`securePolicy` 由 `flagSecureEnabled` 条件驱动」
 * 与「设置值经 `EntryEditViewModel` → `EntryEditPickers` → 本对话框的上行链路」，回退无条件 `SecureOn` 即报红。
 *
 * 原 CaptureActivity 的 `sensorLandscape` 方向锁**不再保留**：方向锁是 zxing 全屏
 * 取景 UX 的产物，本对话框嵌入编辑页流内、跟随 Activity 既有方向行为（裁决登记见
 * 批次文档 ISSUE-P3-319 ④(c)）。
 *
 * ## 敏感数据铁律
 *
 * 解码结果（zxing [com.google.zxing.Result.text]）是框架边界的 `String`（不可避免，
 * 与原 `ScanContract` 回调同口径），**即刻**转 `CharArray` 上行
 * [onDecoded]（→ `EntryEditViewModel.onTotpSecretChangeSecure`），不新增任何
 * String 落地 / 持久化敏感种子的路径；单次交付由 [AtomicBoolean] 保证（连续帧不重复上行）。
 *
 * ## 运行时权限
 *
 * `CAMERA` 为本清单显式声明（替换原 zxing 传递注入）。打开对话框即经
 * [ActivityResultContracts.RequestPermission] 请求一次；拒绝时如实提示并提供重试按钮
 * （不静默失效），关闭对话框后编辑页的扫码入口保持可见。
 */
@Composable
internal fun TotpScanDialog(
    /** 是否施加 `FLAG_SECURE`：设置页「禁止截屏与录屏」开关值（`PD-47`，跟随开关）。 */
    flagSecureEnabled: Boolean,
    /** 解码成功回调：参数为二维码文本即刻转出的 CharArray，消费侧负责清零。 */
    onDecoded: (CharArray) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 跟随防截屏开关（PD-47）：开 ⇒ SecureOn 强制遮罩；关 ⇒ SecureOff 显式不遮罩。
            // 对话框窗口 FLAG_SECURE 的实际决定者（ISSUE-P2-246：默认 Inherit 会按宿主窗口清除），
            // 故两支都必须显式写出，不得回退为省略参数的 Inherit
            securePolicy = if (flagSecureEnabled) {
                SecureFlagPolicy.SecureOn
            } else {
                SecureFlagPolicy.SecureOff
            },
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        ScanDialogWindowHardening(flagSecureEnabled)
        val context = LocalContext.current
        var cameraPermissionGranted by remember {
            mutableStateOf(hasCameraPermission(context))
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> cameraPermissionGranted = granted }
        // 首次打开即请求（与原 zxing CaptureActivity 行为一致）；拒绝后**不**自动重发
        //（系统「不再询问」时 launch 会立即静默拒绝），改由下方显式重试按钮驱动
        LaunchedEffect(cameraPermissionGranted) {
            if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.edit_scan_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (cameraPermissionGranted) {
                    TotpCameraPreview(
                        onDecoded = onDecoded,
                        onDismiss = onDismiss
                    )
                } else {
                    // 权限拒绝态：如实提示 + 重试入口（保持扫码能力可达，不静默失效）
                    Text(
                        text = stringResource(R.string.edit_scan_camera_permission_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.edit_scan_retry))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
    }
}

/**
 * 对话框窗口的反 overlay 加固：对**本对话框窗口**施加 `setHideOverlayWindows(true)`，
 * 退出组合时复位——与 [SecureDialogWindowEffect]（`FLAG_SECURE` 按 [flagSecureEnabled] 条件施加 +
 * 遮挡触摸过滤）互补，共同构成原 `SecureCaptureActivity` 三层防护的等效迁移。
 *
 * 反 overlay 与遮挡触摸过滤**不随防截屏开关变化**（`PD-47` 只改 `FLAG_SECURE` 一路）。
 */
@Composable
private fun ScanDialogWindowHardening(flagSecureEnabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view) {
        val dialogWindow = view.dialogWindowOrNull()
        dialogWindow?.setHideOverlayWindows(true)
        onDispose {
            dialogWindow?.setHideOverlayWindows(false)
        }
    }
    // 统一包装：同窗 FLAG_SECURE（跟随开关，PD-47）+ decorView.filterTouchesWhenObscured = true（恒施加）
    SecureDialogWindowEffect(flagSecure = flagSecureEnabled)
}

/**
 * CameraX 取景 + 后台线程 QR 解码。
 *
 * - 取景：[PreviewView] 承载 [Preview] 流（强制 `COMPATIBLE`/TextureView 实现模式，防 Surface 图层
 *   溢出透明对话框窗口泄漏相机画面，`ISSUE-P3-333`，见 [TotpCameraPreview] 内注释）；分析流 [ImageAnalysis] 用
 *   `STRATEGY_KEEP_ONLY_LATEST`（丢帧保延迟）；
 * - 解码：单线程执行器（**后台线程**，不在主线程做 CPU 解码）喂 [MultiFormatReader]，
 *   仅 QR_CODE；帧内按 4 个旋转方向重试（相机传感器方向与竖屏显示不一致时帧可能旋转
 *   90°/270°），单帧失败属常态、静默丢弃保证分析线程存活；
 * - 交付：解码命中后回主线程上行一次，随即 [onDismiss] 关闭对话框
 *   （组合离开 → DisposableEffect 统一 unbind 相机、停分析线程）。
 */
@Composable
private fun TotpCameraPreview(
    onDecoded: (CharArray) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 强制 COMPATIBLE（TextureView）而非默认 PERFORMANCE（SurfaceView）：SurfaceView 模式下内部
    // SurfaceView 按相机分辨率布局、再经 FILL_CENTER 视图矩阵放大，其独立 Surface 图层不受视图
    // 层级 clipChildren 裁剪；本对话框窗口在卡片外透明，取景区上下各溢出的约 177px 会从卡片上缘
    // 漏成一条相机画面条带（ISSUE-P3-333；SurfaceFlinger 图层读数与 camera-view 1.6.2 源码对拍实证：
    // 源码推导 scale=max(1072/1200,1076/1600)=0.893、上溢177px，与实测 y[681..2111] vs 视图 y[857..1933] 吻合）。
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // 解码独占单线程：MultiFormatReader 非线程安全，单线程串行喂帧即满足契约
    val decodeExecutor = remember {
        Executors.newSingleThreadExecutor { r -> Thread(r, "totp-qr-decode") }
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val delivered = remember { AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraError by remember { mutableStateOf(false) }

    // 对话框关闭（组合离开）时的资源回收：先停分析线程、解绑相机（unbindAll 线程安全）
    DisposableEffect(Unit) {
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            decodeExecutor.shutdown()
            cameraProvider?.unbindAll()
        }
    }

    LaunchedEffect(previewView) {
        try {
            val provider = ProcessCameraProvider.getInstance(context)
                .awaitOn(ContextCompat.getMainExecutor(context))
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(decodeExecutor) { image ->
                var decoded: String? = null
                try {
                    decoded = decodeQrFrame(image, reader)
                } catch (t: Throwable) {
                    // 单帧解不出 / 帧格式异常属常态，吞掉保证分析线程存活
                } finally {
                    image.close()
                }
                if (decoded != null && delivered.compareAndSet(false, true)) {
                    // 框架边界 String 即刻转 CharArray（敏感数据铁律），回主线程上行一次
                    mainHandler.post {
                        onDecoded(decoded.toCharArray())
                        onDismiss()
                    }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        } catch (t: Throwable) {
            // 相机不可用（被系统 / 其它应用占用等）：如实提示，不静默黑屏
            cameraError = true
        }
    }

    ScanCameraViewport(previewView = previewView, cameraError = cameraError)
}

/**
 * 取景区视口：[PreviewView] 画面 + 叠加层（正常为取景提示；相机不可用时为如实错误文案）。
 */
@Composable
private fun ScanCameraViewport(
    previewView: PreviewView,
    cameraError: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (cameraError) {
            Text(
                text = stringResource(R.string.edit_scan_camera_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            )
        } else {
            Text(
                text = stringResource(R.string.edit_scan_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * 单帧 QR 解码：Y 平面（灰度）→ [PlanarYUVLuminanceSource] → [HybridBinarizer]，
 * 未命中则**字节级手工旋转** Y 平面后重试——4 个方向均失败返回 null。 *
 * 旋转必须自己做：[PlanarYUVLuminanceSource] 不支持
 * `BinaryBitmap.rotateCounterClockwise()`（真机实证抛 `UnsupportedOperationException`，
 * 依赖它会令多方向重试沦为死代码）。CameraX 只给 `rotationDegrees` 元数据、不旋转像素，
 * 故设备竖拍时帧相对显示旋转 90°/270°，只有尝试全部朝向才与拍摄角度无关地可解。
 */
private fun decodeQrFrame(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes[0]
    val rowStride = plane.rowStride
    val width = image.width
    val height = image.height
    // Y 平面按行距拷出并剥去行对齐 padding：得到连续的 width x height 灰度图
    val padded = ByteArray(rowStride * height)
    plane.buffer.get(padded, 0, minOf(plane.buffer.remaining(), padded.size))
    var data = ByteArray(width * height)
    for (row in 0 until height) {
        System.arraycopy(padded, row * rowStride, data, row * width, width)
    }
    var w = width
    var h = height
    repeat(4) {
        try {
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: NotFoundException) {
            // 换方向重试
        }
        val rotated = rotateYPlane90(data, w, h)
        data = rotated.first
        w = rotated.second
        h = rotated.third
    }
    return null
}

/** Y 平面顺时针旋转 90°：返回 (旋转后字节, 新宽, 新高)。迭代 4 次即覆盖全部朝向，方向无谓。 */
private fun rotateYPlane90(src: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
    val out = ByteArray(src.size)
    for (y in 0 until h) {
        for (x in 0 until w) {
            out[x * h + (h - 1 - y)] = src[y * w + x]
        }
    }
    return Triple(out, h, w)
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** [ListenableFuture] 的轻量挂起等待（不引入 kotlinx-coroutines-guava 依赖）。 */
private suspend fun <T> ListenableFuture<T>.awaitOn(executor: java.util.concurrent.Executor): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }, executor)
    }
