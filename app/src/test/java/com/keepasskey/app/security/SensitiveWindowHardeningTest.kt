package com.keepasskey.app.security

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 敏感**独立窗口**加固接线守护（ISSUE-P3-103，ISSUE-P3-319 迁移后口径；`PD-47` 改判后口径）。
 *
 * 缺口背景：TOTP 二维码取景原为独立 zxing CaptureActivity（[SecureCaptureActivity]
 * 加固壳，ISSUE-P3-71）；ISSUE-P3-319 移除 zxing-android-embedded 后取景改为
 * **Compose 对话框**（`TotpScanDialog`）——Compose `Dialog` 创建**独立窗口**
 * （`DialogLayout`），加固属性不从 Activity 窗口传播，故仍须对对话框窗口逐项接线。
 * 该类防护的失效形态是「接线被静默删除」——JVM 无法构造真实取景窗口，
 * 故本用例以静态守卫锁定调用形态（断言前剔除注释，避免整改说明自身命中）。
 *
 * 三层防护各自不可替代（详见 `docs/architecture/实现约定与验证现状.md` §3.3）：
 * - `FLAG_SECURE`：取景画面（可能含密钥种子二维码）禁截屏 / 录屏 / 多任务缩略图——
 *   **自 `PD-47`（2026-09-26）起跟随设置页「禁止截屏与录屏」开关**：对话框窗口该 flag 的
 *   实际决定者是 `DialogProperties.securePolicy`（`ISSUE-P2-246`：默认 `Inherit` 会按宿主窗口清除），
 *   故调用点必须由 `flagSecureEnabled` **条件显式**给出 `SecureOn`（开）/ `SecureOff`（关），
 *   不得回退为无条件 `SecureOn`（推翻用户开关）或省略参数的 `Inherit`；
 * - `setHideOverlayWindows(true)`：阻断其它应用**新绘制** TYPE_APPLICATION_OVERLAY 悬浮窗
 *   （**不随开关变化**）；
 * - `decorView.filterTouchesWhenObscured = true`：遮挡态下丢弃整棵视图子树的触摸
 *   （反点击劫持，**不随开关变化**），由统一包装 [SecureDialogWindowEffect] 施加
 *   （其 `flagSecure=false` 时只跳过 `FLAG_SECURE`，过滤照常），对**已存在**的遮挡窗口同样生效。
 *
 * 上行链路（`PD-47` 专查）：开关值必须真实到达对话框——`EntryEditViewModel.flagSecureEnabled`
 * → `EntryEditPickers` 下传 → `TotpScanDialog` 消费；断了任一环，对话框拿不到值即恒 fail-closed
 * （开关关不掉遮罩，正是 `ISSUE-P3-332` 的失效形态）。
 *
 * 未覆盖（需设备侧验证）：真实截屏屏蔽、悬浮窗阻断与遮挡触摸丢弃的运行时效果。
 */
class SensitiveWindowHardeningTest {

    @Test
    fun `TOTP 扫码对话框必须同时接线防截屏与反遮挡两路防护`() {
        val code = stripComments(readSource(SCAN_DIALOG_SOURCE))

        // PD-47：securePolicy 必须由开关条件驱动（开 ⇒ SecureOn、关 ⇒ SecureOff）。
        // 回退为无条件 SecureOn ⇒ 缺 SecureOff / flagSecureEnabled，红；
        // 改回省略参数的 Inherit ⇒ 三者皆缺，红。
        assertTrue(
            "[$SCAN_DIALOG_SOURCE] 对话框未由 flagSecureEnabled 条件驱动 securePolicy" +
                "（须同时具备 SecureFlagPolicy.SecureOn / SecureFlagPolicy.SecureOff / flagSecureEnabled；" +
                "无条件 SecureOn 推翻用户开关、省略参数的 Inherit 会在宿主窗口不带 flag 时被清除，见 PD-47 / ISSUE-P2-246）",
            code.contains("SecureFlagPolicy.SecureOn") &&
                code.contains("SecureFlagPolicy.SecureOff") &&
                code.contains("flagSecureEnabled")
        )
        assertTrue(
            "[$SCAN_DIALOG_SOURCE] 缺少 setHideOverlayWindows(true)：无法阻断悬浮窗覆盖" +
                "（反 overlay 不随防截屏开关变化，PD-47）",
            code.contains("setHideOverlayWindows(true)")
        )
        assertTrue(
            "[$SCAN_DIALOG_SOURCE] 缺少 SecureDialogWindowEffect(flagSecure = flagSecureEnabled) 接线：" +
                "该包装承担 decorView.filterTouchesWhenObscured = true（遮挡态触摸未被丢弃，" +
                "反点击劫持缺口，恒施加）与同窗 FLAG_SECURE 按开关条件施加（PD-47）",
            code.contains("SecureDialogWindowEffect(flagSecure = flagSecureEnabled)")
        )
        // ISSUE-P3-333：PreviewView 必须强制 COMPATIBLE（TextureView）——默认 PERFORMANCE（SurfaceView）
        // 的独立 Surface 图层不受视图层级 clipChildren 裁剪，FILL_CENTER 放大后的上下溢出会从
        // 透明对话框窗口的卡片上缘漏出相机画面条带（SurfaceFlinger 读数实证，回退即复发）。
        assertTrue(
            "[$SCAN_DIALOG_SOURCE] 缺少 implementationMode = PreviewView.ImplementationMode.COMPATIBLE" +
                "（回退默认 PERFORMANCE/SurfaceView ⇒ Surface 图层溢出透明对话框窗口，" +
                "卡片上缘重现相机画面条带，ISSUE-P3-333 复发）",
            code.contains("implementationMode = PreviewView.ImplementationMode.COMPATIBLE")
        )
    }

    @Test
    fun `防截屏开关值经 ViewModel 到对话框的上行链路已接线`() {
        val pickers = stripComments(readSource(ENTRY_EDIT_PICKERS_SOURCE))
        assertTrue(
            "[$ENTRY_EDIT_PICKERS_SOURCE] 未把 flagSecureEnabled 作为实参下传扫码对话框" +
                "（开关值到不了 TotpScanDialog ⇒ 遮罩恒开关无效，ISSUE-P3-332 复发）",
            pickers.contains("TotpScanDialog(") &&
                pickers.contains("flagSecureEnabled = flagSecureEnabled")
        )

        val viewModel = stripComments(readSource(ENTRY_EDIT_VIEW_MODEL_SOURCE))
        assertTrue(
            "[$ENTRY_EDIT_VIEW_MODEL_SOURCE] 缺少 flagSecureEnabled 状态流暴露" +
                "（设置值无上行通道，PD-47）",
            viewModel.contains("val flagSecureEnabled")
        )
    }

    /** 剔除块注释与行注释——整改说明本身会写出关键字（如本用例断言的调用形态），
     *  不剔除即会「注释里的假接线」也算通过。 */
    private fun stripComments(source: String): String = stripCommentsOnly(source)

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SCAN_DIALOG_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/edit/TotpScanDialog.kt"
        const val ENTRY_EDIT_PICKERS_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditPickers.kt"
        const val ENTRY_EDIT_VIEW_MODEL_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/edit/EntryEditViewModel.kt"

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
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

        const val ROOT_SEARCH_DEPTH = 4
    }
}
