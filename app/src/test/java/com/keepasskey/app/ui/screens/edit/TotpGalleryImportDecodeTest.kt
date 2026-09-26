package com.keepasskey.app.ui.screens.edit

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 相册导入通路的纯解码函数单测（ISSUE-P3-334 AC④）+ 框架接线静态守卫（ISSUE-P3-336 AC③）。
 *
 * 覆盖样本：
 * 1. 正样本：QRCodeWriter 合成的二维码像素帧 → 解码回原文（生产链路：灰度化 + zxing）；
 * 2. 旋转样本：正样本像素帧顺时针旋转 90°（模拟 EXIF 朝向）→ 仍可解出原文
 *    （4 朝向重试是与拍摄角度无关可解的判据）；
 * 3. 负样本：非二维码图像（纯色帧）→ 返回 null，不误报；
 * 4. 透明底样本（ISSUE-P3-336 回归）：白底像素替换为 a=0、RGB=0 的全透明像素
 *    （透明底 QR PNG 经 `Bitmap.getPixels` 的真实形态）→ alpha 合成到白底后仍可解出原文；
 * 5. 静态守卫（ISSUE-P3-336）：`TotpScanDialog` 装配的源码中不得出现
 *    `decodeStream` 返回值 `!= null` 判空——`inJustDecodeBounds = true` 时它恒 `null`，
 *    判空恒 false 会让每次选图都走「图片读取失败」（JVM 无法构造该框架语义，源码扫描锁定）。
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

    @Test
    fun `透明底二维码像素帧可解出原文（alpha 合成到白底）`() {
        val text = "otpauth://totp/Test:carol@example.com?secret=GEZDGNBVGY3TQOJQ&issuer=Test"
        val (opaque, width, height) = encodeQrPixels(text)
        // 白底 → 全透明且 RGB=0（premultiplied 位图 getPixels 对 a=0 像素的真实还原形态）
        val transparentBg = IntArray(opaque.size) { i ->
            if (opaque[i] == WHITE_PIXEL) TRANSPARENT_PIXEL else opaque[i]
        }
        assertEquals(text, decodeQrFromPixels(transparentBg, width, height))
    }

    @Test
    fun `读尺寸不得以 decodeStream 返回值判空（inJustDecodeBounds 恒返回 null）`() {
        val file = File(repositoryRoot, GALLERY_IMPORT_SOURCE)
        assertTrue("源码文件不存在（是否被重命名/移动）：$GALLERY_IMPORT_SOURCE", file.isFile)
        val code = stripCommentsOnly(file.readText())
        assertFalse(
            "[$GALLERY_IMPORT_SOURCE] decodeStream 返回值被 != null 判空：inJustDecodeBounds=true " +
                "时 decodeStream 恒返回 null，判空恒 false ⇒ 每次选图都报「图片读取失败」、" +
                "解码根本不执行（ISSUE-P3-336 实测事故；读尺寸只验 outWidth/outHeight）",
            DECODE_RESULT_NULL_CHECK.containsMatchIn(code)
        )
        assertTrue(
            "[$GALLERY_IMPORT_SOURCE] 缺 outWidth 可解性判定（官方两步加载口径：读尺寸后必须验尺寸）",
            code.contains("bounds.outWidth <= 0")
        )
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
                    if (matrix.get(x, y)) BLACK_PIXEL else WHITE_PIXEL
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

    private companion object {
        /** 被守卫的生产源码（相对仓库根；app 模块测试工作目录为 app/，向上回溯定位仓库根）。 */
        const val GALLERY_IMPORT_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/edit/TotpGalleryImport.kt"

        /** `decodeStream(...)` 调用后 200 字符内出现 `!= null` 即命中（覆盖跨行的 `use { } != null` 形态）。 */
        val DECODE_RESULT_NULL_CHECK = Regex("""decodeStream[\s\S]{0,200}?!=\s*null""")

        const val BLACK_PIXEL = 0xFF000000.toInt()
        const val WHITE_PIXEL = 0xFFFFFFFF.toInt()
        const val TRANSPARENT_PIXEL = 0x00000000

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先（同 SensitiveWindowHardeningTest 口径）。 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
