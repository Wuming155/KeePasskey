package com.keepasskey.app.ui.screens.edit

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高密度二维码在**生产解码器** [decodeQrFromPixels] 上的密度实测（`ISSUE-P3-337` 第 2.5 片，AC⑤′/AC⑧ 的前置）。
 *
 * ## 本用例能证什么、不能证什么
 *
 * **能证**：① 两档 CXF 载荷（规范附录 A 的裸 `Passkey` 对象 472 B、套 §3.1 `Header` 信封后的
 * 749 B 单凭据文档）在 EC=L / EC=Q 下的**模块数**——这是口径 9 的尺寸预算与 AC⑧ 实拍夹具
 * 选型的直接依据；② 相册通路（§340 定下的 `MAX_GALLERY_IMAGE_DIMENSION=2400`）在这两档载荷上
 * 经**生产解码器**解得出——即「相册导入通行密钥」在解码层面成立。
 *
 * **不能证**：相机通路。本轮把两种合成模型都跑了一遍，结论是**合成模型对本载荷密度没有鉴别力**：
 * - 「最近邻整数 px/模块 + 3×3 均值 + ±20 噪声」：默认 640×480（最坏 1.98 px/模块）**全部不可解**，
 *   但同一模型在 2400 短边出现**非单调**读数（0.5 可解 / 0.65 不可解 / 0.8 可解），说明该模型把
 *   「码在帧里的整数对齐」当成了变量，不可用于定量结论；
 * - 「亚像素 3×3 覆盖积分 + ±20 噪声」（更贴近真实采样）：从 1.98 px/模块起**几乎全部可解**，
 *   同样在个别档位出现非单调。
 * 两者互相矛盾 ⇒ 相机面的「能不能扫」**只能由实拍判定**（AC⑧③：透视、离焦梯度、摩尔纹、
 * 手写抖动都不在合成模型里）。**禁止**以本表的「可解」推定「相机实测能扫」。
 *
 * 配套的真机读数为 `ImageProxy` 实测（条目留痕：Redmi 4X 默认配置实拿 640×480、
 * 请求 1280×960 实拿 1280×960、极限请求实拿 4000×3000）⇒ 提分辨率在硬件上不构成瓶颈，
 * 瓶颈在光学，故 AC⑤′ 的分辨率改动**照做**，但声称范围按上述限制收窄。
 */
class QrDecodeDensityTest {

    private fun codeOf(payload: String, level: ErrorCorrectionLevel) =
        Encoder.encode(payload, level, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix

    /** 模块数（不含静区；zxing `Encoder` 的直接输出尺寸）。 */
    private fun moduleCount(payload: String, level: ErrorCorrectionLevel): Int = codeOf(payload, level).width

    /**
     * 亚像素覆盖渲染：每个输出像素按其面积对连续码面做 3×3 子采样覆盖积分（无噪声、无镜头像差），
     * 码占画面 [fill] 比例，帧为 `shortSide × shortSide`——与相机整帧解码同形态（周围是背景，
     * 解码器自行找定位点）。
     */
    private fun coverageFrame(payload: String, level: ErrorCorrectionLevel, shortSide: Int, fill: Double): IntArray {
        val matrix = codeOf(payload, level)
        val modules = matrix.width
        val px = shortSide * fill / modules
        val offset = (shortSide - modules * px) / 2.0
        val out = IntArray(shortSide * shortSide)
        for (y in 0 until shortSide) {
            for (x in 0 until shortSide) {
                var dark = 0
                for (sy in 0 until SUB) {
                    val v = (y + (sy + 0.5) / SUB - offset) / px
                    if (v < 0.0 || v >= modules) continue
                    for (sx in 0 until SUB) {
                        val u = (x + (sx + 0.5) / SUB - offset) / px
                        if (u < 0.0 || u >= modules) continue
                        if (matrix.get(u.toInt(), v.toInt()).toInt() == 1) dark++
                    }
                }
                val gray = (255 - dark * 255 / (SUB * SUB)).coerceIn(0, 255)
                out[y * shortSide + x] = 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
            }
        }
        return out
    }

    private fun decodes(payload: String, level: ErrorCorrectionLevel, shortSide: Int, fill: Double): Boolean =
        decodeQrFromPixels(coverageFrame(payload, level, shortSide, fill), shortSide, shortSide) != null

    @Test
    fun `一 两档 CXF 载荷的模块数与相册短边可解性读数`() {
        val bare = String(appendixPasskeyJson(), Charsets.UTF_8)
        val envelope = documentEnvelope(bare)
        val report = StringBuilder()
        for ((label, payload) in listOf("裸 passkey 对象 ${bytes(bare)} B" to bare, "文档信封 ${bytes(envelope)} B" to envelope)) {
            for (level in listOf(ErrorCorrectionLevel.L, ErrorCorrectionLevel.Q)) {
                val modules = moduleCount(payload, level)
                report.appendLine("$label EC=$level 模块=$modules")
                for (fill in FILLS) {
                    val pxPerModule = SIDE_GALLERY * fill / modules
                    val ok = decodes(payload, level, SIDE_GALLERY, fill)
                    report.appendLine(
                        "    短边 $SIDE_GALLERY 填充 $fill ⇒ px/模块 %.2f → %s".format(
                            pxPerModule, if (ok) "可解" else "不可解"
                        )
                    )
                    assertTrue(
                        "$label EC=$level（模块 $modules，%.2f px/模块）在相册通路短边下必须可解，"
                            .format(pxPerModule) + "否则「相册导入通行密钥」在解码层面就不成立",
                        ok
                    )
                }
            }
        }
        println("=== CXF 二维码密度读数（相册短边 $SIDE_GALLERY，亚像素覆盖渲染，无噪声）===\n$report")
    }

    /**
     * 合成模型的**无鉴别力**必须被锁住：默认 640×480 分析流的最坏档位（文档信封 EC=Q，121 模块，
     * 填充 0.5 ⇒ 1.98 px/模块）在亚像素覆盖渲染下**仍被解出**。
     *
     * 这条断言的方向与直觉相反，但它是本轮实测的事实：**能解出不代表相机能用**，
     * 只代表合成帧缺少真实光学的退化项。它存在的意义是防止后来者拿本表当「相机可扫」的证据——
     * 若哪天有人给渲染加了镜头退化而结果改变，本例会红并迫使改动人重新登记声称范围。
     */
    @Test
    fun `二 合成帧在默认短边最坏档位下仍可解 故不得据其声称相机可用`() {
        val envelope = documentEnvelope(String(appendixPasskeyJson(), Charsets.UTF_8))
        val modules = moduleCount(envelope, ErrorCorrectionLevel.Q)
        val pxPerModule = SIDE_DEFAULT * 0.5 / modules
        assertTrue(
            "算术前提：默认短边最坏档位须低于 2 px/模块（实际 %.2f）".format(pxPerModule),
            pxPerModule < 2.0
        )
        assertTrue(
            "合成帧在该密度下被解出 ⇒ 模型不含镜头退化，相机面的结论只能来自实拍（AC⑧③）",
            decodes(envelope, ErrorCorrectionLevel.Q, SIDE_DEFAULT, 0.5)
        )
    }

    /** 提分辨率的收益是**算术事实**：同一填充率下 960 短边拿到的 px/模块恒为 480 的两倍。 */
    @Test
    fun `三 拟改短边与默认短边的密度余量关系`() {
        val bare = String(appendixPasskeyJson(), Charsets.UTF_8)
        for (level in listOf(ErrorCorrectionLevel.L, ErrorCorrectionLevel.Q)) {
            val modules = moduleCount(bare, level)
            assertTrue(
                "模块数读数须落在 77~97（超出说明载荷或编码口径变了）",
                modules in 77..97
            )
            assertTrue(
                "960 短边的 px/模块须为 480 的两倍",
                SIDE_PROPOSED * 0.65 / modules >= SIDE_DEFAULT * 0.65 / modules * 2.0 - 1e-9
            )
        }
    }

    // ------------------------------------------------------------------
    // 夹具：直取规范附录 A（与解析器用例同一权威源，不另造）
    // ------------------------------------------------------------------

    private fun bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun appendixPasskeyJson(): ByteArray {
        val stream = javaClass.getResourceAsStream(APPENDIX_RESOURCE)
        assertNotNull("缺夹具资源 $APPENDIX_RESOURCE", stream)
        val text = requireNotNull(stream).use { it.readBytes().toString(Charsets.UTF_8) }
        val root = com.keepasskey.app.passkey.SimpleJson.parse(text)
        val accounts = com.keepasskey.app.passkey.SimpleJson.arrayAt(
            com.keepasskey.app.passkey.SimpleJson.asObject(root), "accounts"
        ).orEmpty()
        for (account in accounts) {
            for (item in com.keepasskey.app.passkey.SimpleJson.arrayAt(
                com.keepasskey.app.passkey.SimpleJson.asObject(account), "items"
            ).orEmpty()) {
                for (cred in com.keepasskey.app.passkey.SimpleJson.arrayAt(
                    com.keepasskey.app.passkey.SimpleJson.asObject(item), "credentials"
                ).orEmpty()) {
                    val obj = com.keepasskey.app.passkey.SimpleJson.asObject(cred) ?: continue
                    if (com.keepasskey.app.passkey.SimpleJson.string(obj, "type") == "passkey") {
                        return compactJson(obj).toByteArray(Charsets.UTF_8)
                    }
                }
            }
        }
        error("附录 A 示例里没有 passkey 凭据")
    }

    /** 套一层 §3.1 `Header` + `accounts/items/credentials` 信封，度量「文档级载荷」的真实尺寸。 */
    private fun documentEnvelope(passkeyJson: String): String =
        """{"version":{"major":1,"minor":0},"exporterRpId":"exporter.example.com",""" +
            """"exporterDisplayName":"Example Exporter","timestamp":1738368000,"accounts":[{"id":"YWNjdA",""" +
            """"username":"johndoe","email":"john@example.com","items":[{"id":"aXRlbQ","title":"webauthn.io",""" +
            """"credentials":[$passkeyJson]}]}]}"""

    private fun compactJson(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${compactJson(v)}" }
        is List<*> -> value.joinToString(",", "[", "]") { compactJson(it) }
        else -> "\"$value\""
    }

    private companion object {
        const val APPENDIX_RESOURCE = "/passkey-import/cxf-appendix-a-example.json"

        /** 官方默认分析流短边（`ImageAnalysis` 不设分辨率时的 640×480）。 */
        const val SIDE_DEFAULT = 480

        /** 口径 10 拟改的短边（1280×960）。 */
        const val SIDE_PROPOSED = 960

        /** 相册通路短边（§340 的 `MAX_GALLERY_IMAGE_DIMENSION=2400`）。 */
        const val SIDE_GALLERY = 2400

        /** 每边子采样数（3×3＝9 次采样/像素）。 */
        const val SUB = 3

        val FILLS = listOf(0.5, 0.65, 0.8)
    }
}
