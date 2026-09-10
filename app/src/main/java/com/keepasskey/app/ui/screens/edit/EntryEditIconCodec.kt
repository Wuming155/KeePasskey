package com.keepasskey.app.ui.screens.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** TASK-15：自定义图标降采样目标上限（KDBX 生态约定小尺寸 PNG，KeePassXC 默认 128px） */
private const val CUSTOM_ICON_MAX_PX = 128

/**
 * TASK-15：解码任意图片字节并降采样至 ≤[CUSTOM_ICON_MAX_PX] 的 PNG（KDBX CustomIcon 载荷）。
 * 解码失败（非图片/损坏数据）返回 null，调用方如实上浮错误。
 *
 * ISSUE-P3-31 批次 C：由 `EntryEditScreen.kt` 按**纯结构性拆分**搬出，
 * 采样算法与内存控制逐字保留；原为同文件 `private`，搬出后收敛为 `internal`（同包可见）。
 */
internal fun decodeAndScaleToPng(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // 先按 2 的幂次抽样粗降采样，再精确缩放至目标上限，控制峰值内存
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= CUSTOM_ICON_MAX_PX) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

    val scale = minOf(1f, CUSTOM_ICON_MAX_PX.toFloat() / maxOf(src.width, src.height))
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    } else {
        src
    }
    val output = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
    if (scaled !== src) scaled.recycle()
    src.recycle()
    return output.toByteArray()
}
