package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.testutil.stripCommentsOnly
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态守卫：`ISSUE-P3-337` 口径 10 的共享取景器分辨率改动（AC⑤′ 登记的**唯一** TOTP 共享面例外）。
 *
 * 立规理由与 §341 的 `decodeStream` 守卫同源：**「闸门存在 ≠ 闸门被执行」**——
 * 分辨率这一处只影响真机解码成功率，宿主单测完全覆盖不到（`ImageProxy` 由相机框架供给），
 * 一旦有人以「默认 640×480 够用」为由删掉 `ResolutionSelector` 或改回已废弃的
 * `setTargetResolution`，全量 JVM 套件仍会绿。故按源码扫描锁死三件事：
 * ① `setResolutionSelector` 在场，且 bound 走命名常量；
 * ② fallback 为 `CLOSEST_HIGHER_THEN_LOWER`（低于请求时优先取更高档，而不是往下降级）；
 * ③ 已废弃的 `setTargetResolution` / `setTargetAspectRatio` **不得出现**
 *   ——官方原文明写它们与 `setResolutionSelector` 互斥，混用时 `build()` 抛
 *   `IllegalArgumentException`（扫码对话框会在 `LaunchedEffect` 里直接崩）。
 */
class TotpScanCameraResolutionGuardTest {

    private val sourceCode: String by lazy {
        val file = File(repositoryRoot, SCAN_DIALOG_SOURCE)
        assertTrue("源码文件不存在（是否被重命名/移动）：$SCAN_DIALOG_SOURCE", file.isFile)
        stripCommentsOnly(file.readText())
    }

    @Test
    fun resolutionSelectorPresentWithNamedBound() {
        assertTrue(
            "[TotpScanDialog] 缺 setResolutionSelector：回到官方默认 bound 640×480 时，" +
                "CXF 单凭据载荷（77~121 模块）只剩 2~5 px/模块，相机通路无从解出" +
                "（ISSUE-P3-337 口径 10 / AC⑤′，真机回读默认实拿 640×480）",
            SELECTOR_WITH_BOUND.containsMatchIn(sourceCode)
        )
        assertTrue(
            "[TotpScanDialog] fallback 规则须是 CLOSEST_HIGHER_THEN_LOWER（拿不到请求档时往上取，" +
                "而不是往下降级到更小帧）",
            sourceCode.contains("ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER")
        )
    }

    @Test
    fun deprecatedResolutionApisAbsent() {
        assertFalse(
            "[TotpScanDialog] 出现已废弃的分辨率 API：setTargetResolution / setTargetAspectRatio 自 " +
                "camera 1.3.0 起废弃，且与 setResolutionSelector 互斥、混用时 build() 抛 " +
                "IllegalArgumentException（ISSUE-P3-337 口径 10）",
            DEPRECATED_RESOLVER.containsMatchIn(sourceCode)
        )
    }

    /** 口径反校：两条判据都必须「已知坏样本能抓、正常形态不误报」，否则等于没有守卫。 */
    @Test
    fun guardHasTeethOnKnownBadShapes() {
        assertFalse(
            "存在性判据是空转正则：完全没有 setResolutionSelector 的形态也被判为在场",
            SELECTOR_WITH_BOUND.containsMatchIn(
                "ImageAnalysis.Builder().setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST).build()"
            )
        )
        assertTrue(
            "存在性判据过严：用命名常量声明 bound 的正常形态未被认出",
            SELECTOR_WITH_BOUND.containsMatchIn(
                "setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(" +
                    "ResolutionStrategy(Size(CAMERA_ANALYSIS_WIDTH, CAMERA_ANALYSIS_HEIGHT), x))"
            )
        )
        assertTrue(
            "废弃 API 判据失效：setTargetResolution 的坏样本未被抓住",
            DEPRECATED_RESOLVER.containsMatchIn("builder.setTargetResolution(Size(640, 480))")
        )
        assertFalse(
            "废弃 API 判据误报：正常 setResolutionSelector 形态被当成废弃调用",
            DEPRECATED_RESOLVER.containsMatchIn("builder.setResolutionSelector(selector)")
        )
    }

    private companion object {
        const val SCAN_DIALOG_SOURCE = "app/src/main/java/com/keepasskey/app/ui/screens/edit/TotpScanDialog.kt"

        /** `setResolutionSelector(... ResolutionStrategy(Size(常量` 的在场形态 */
        val SELECTOR_WITH_BOUND = Regex(
            "setResolutionSelector\\([\\s\\S]{0,160}?ResolutionStrategy\\(\\s*Size\\(\\s*[A-Z_]"
        )

        /** 废弃的两个分辨率入口 */
        val DEPRECATED_RESOLVER = Regex("setTargetResolution|setTargetAspectRatio")

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
