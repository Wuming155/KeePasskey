package com.keepasskey.app.ui.screens.edit

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 相册导入通路的纯解码函数单测（ISSUE-P3-334 AC④）。
 *
 * 覆盖三类样本：
 * 1. 正样本：QRCodeWriter 合成的二维码像素帧 → 解码回原文（生产链路：灰度化 + zxing）；
 * 2. 旋转样本：正样本像素帧顺时针旋转 90°（模拟 EXIF 朝向）→ 仍可解出原文
 *    （4 朝向重试是与拍摄角度无关可解的判据）；
 * 3. 负样本：非二维码图像（纯色帧）→ 返回 null，不误报。
 *
 * 测试数据为虚构 otpauth URI（测试资产纪律：不使用真实凭据）。
 */
class TotpGalleryImportDecodeTest {

    @Test
    fun `相册像素帧可解出二维码原文`() {
        val text = "otpauth://totp/Test:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Test"
        val (pixels, width, height) = encodeQrPixels(text)
        assertEquals(text, decodeQrFromPixels(pixels, width, height))
    }

    @Test
    fun `旋转90度的像素帧仍可解出二维码原文`() {
        val text = "otpauth://totp/Test:bob@example.com?secret=MFRGGZDFMZTWQ2LK&issuer=Test"
        val (pixels, width, height) = encodeQrPixels(text)
        val rotated = rotatePixels90Clockwise(pixels, width, height)
        assertEquals(text, decodeQrFromPixels(rotated.first, rotated.second, rotated.third))
    }

    @Test
    fun `非二维码纯色帧返回 null`() {
        val pixels = IntArray(128 * 128) { 0xFF808080.toInt() }
        assertNull(decodeQrFromPixels(pixels, 128, 128))
    }

    @Test
    fun `像素数组尺寸与声明不符时返回 null 而非越界`() {
        val pixels = IntArray(16)
        assertNull(decodeQrFromPixels(pixels, 8, 8))
        assertNull(decodeQrFromPixels(pixels, 0, 4))
    }

    /** zxing 编码 QR → ARGB 像素帧（黑模块 0xFF000000、白底 0xFFFFFFFF，含静区）。 */
    private fun encodeQrPixels(text: String): Triple<IntArray, Int, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 256, 256)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] =
                    if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Triple(pixels, width, height)
    }

    /** 像素帧顺时针旋转 90°（与生产 `rotateYPlane90` 同几何，模拟 EXIF 朝向差）。 */
    private fun rotatePixels90Clockwise(
        src: IntArray,
        w: Int,
        h: Int
    ): Triple<IntArray, Int, Int> {
        val out = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[x * h + (h - 1 - y)] = src[y * w + x]
            }
        }
        return Triple(out, h, w)
    }
}
