package com.keepasskey.app.security

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 敏感**独立窗口**加固接线守护（ISSUE-P3-103，ISSUE-P3-319 迁移后口径）。
 *
 * 缺口背景：TOTP 二维码取景原为独立 zxing CaptureActivity（[SecureCaptureActivity]
 * 加固壳，ISSUE-P3-71）；ISSUE-P3-319 移除 zxing-android-embedded 后取景改为
 * **Compose 对话框**（`TotpScanDialog`）——Compose `Dialog` 创建**独立窗口**
 * （`DialogLayout`），加固属性不从 Activity 窗口传播，故仍须对对话框窗口逐项接线。
 * 该类防护的失效形态是「接线被静默删除」——JVM 无法构造真实取景窗口，
 * 故本用例以静态守卫锁定调用形态（断言前剔除注释，避免整改说明自身命中）。
 *
 * 三层防护各自不可替代（详见 `docs/architecture/已知工程限界.md` §3.3）：
 * - `FLAG_SECURE`：取景画面（可能含密钥种子二维码）禁截屏 / 录屏 / 多任务缩略图——
 *   对话框窗口该 flag 的实际决定者是 `DialogProperties.securePolicy`，调用点必须传
 *   `SecureFlagPolicy.SecureOn`（`ISSUE-P2-246`：默认 `Inherit` 会按宿主窗口清除）；
 * - `setHideOverlayWindows(true)`：阻断其它应用**新绘制** TYPE_APPLICATION_OVERLAY 悬浮窗；
 * - `decorView.filterTouchesWhenObscured = true`：遮挡态下丢弃整棵视图子树的触摸
 *   （反点击劫持），由统一包装 [SecureDialogWindowEffect] 施加，对**已存在**的遮挡窗口同样生效。
 *
 * 未覆盖（需设备侧验证）：真实截屏屏蔽、悬浮窗阻断与遮挡触摸丢弃的运行时效果。
 */
class SensitiveWindowHardeningTest {

    @Test
    fun `TOTP 扫码对话框必须同时接线防截屏与反遮挡两路防护`() {
        val code = stripComments(readSource(SCAN_DIALOG_SOURCE))

        assertTrue(
            "[$SCAN_DIALOG_SOURCE] 对话框未传 securePolicy = SecureFlagPolicy.SecureOn：" +
                "默认 Inherit 会按宿主窗口清除对话框窗口的 FLAG_SECURE（ISSUE-P2-246），" +
                "取景画面（含密钥种子二维码）会进截屏 / Recents 缩略图",
            code.contains("SecureFlagPolicy.SecureOn")
        )
        assertTrue(
            "[$SCAN_DIALOG_SOURCE] 缺少 setHideOverlayWindows(true)：无法阻断悬浮窗覆盖",
            code.contains("setHideOverlayWindows(true)")
        )
        assertTrue(
            "[$SCAN_DIALOG_SOURCE] 缺少 SecureDialogWindowEffect() 接线：" +
                "该包装承担 decorView.filterTouchesWhenObscured = true（遮挡态触摸未被丢弃，" +
                "反点击劫持缺口）与同窗 FLAG_SECURE 防御性施加",
            code.contains("SecureDialogWindowEffect()")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    /**
     * 剔除块注释与行注释——整改说明本身会写出关键字（如本用例断言的调用形态），
     * 不剔除即会「注释里的假接线」也算通过。
     */
    private fun stripComments(source: String): String = stripCommentsOnly(source)
    private companion object {
        const val SCAN_DIALOG_SOURCE =
            "app/src/main/java/com/keepasskey/app/ui/screens/edit/TotpScanDialog.kt"


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
