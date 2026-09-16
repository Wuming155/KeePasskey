package com.keepasskey.app.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 敏感**独立窗口**加固接线守护（ISSUE-P3-103）。
 *
 * 缺口背景：TOTP 二维码取景窗口（[SecureCaptureActivity]，继承 zxing `CaptureActivity`）
 * 游离于 `FlagSecureGuard` 的 attach 体系之外（后者只覆盖 `MainActivity` 与
 * `BaseCredentialActivity` 体系），因此必须**自行**接线全部三层防护。
 * 该类防护的失效形态是「接线被静默删除」——JVM 无法构造真实取景窗口，
 * 故本用例以静态守卫锁定三处调用形态（断言前剔除注释，避免整改说明自身命中）。
 *
 * 三层防护各自不可替代（详见 `docs/architecture/已知工程限界.md` §3.3）：
 * - `FLAG_SECURE`：取景画面（可能含密钥种子二维码）禁截屏 / 录屏 / 多任务缩略图；
 * - `setHideOverlayWindows(true)`：阻断其它应用**新绘制** TYPE_APPLICATION_OVERLAY 悬浮窗；
 * - `decorView.filterTouchesWhenObscured = true`：遮挡态下丢弃整棵视图子树的触摸（反点击劫持），
 *   对**已存在**的遮挡窗口同样生效。
 *
 * 未覆盖（需设备侧验证）：真实截屏屏蔽、悬浮窗阻断与遮挡触摸丢弃的运行时效果。
 */
class SensitiveWindowHardeningTest {

    @Test
    fun `TOTP 取景窗口必须同时接线防截屏与反遮挡两路防护`() {
        val code = stripComments(readSource(SECURE_CAPTURE_SOURCE))

        assertTrue(
            "[$SECURE_CAPTURE_SOURCE] 缺少 FLAG_SECURE：取景画面（含密钥种子二维码）会进截屏 / Recents 缩略图",
            code.contains("WindowManager.LayoutParams.FLAG_SECURE")
        )
        assertTrue(
            "[$SECURE_CAPTURE_SOURCE] 缺少 setHideOverlayWindows(true)：无法阻断悬浮窗覆盖",
            code.contains("setHideOverlayWindows(true)")
        )
        assertTrue(
            "[$SECURE_CAPTURE_SOURCE] 缺少 decorView.filterTouchesWhenObscured = true：" +
                "遮挡态触摸未被丢弃（反点击劫持缺口）",
            code.contains("decorView.filterTouchesWhenObscured = true")
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
    private fun stripComments(source: String): String =
        source
            .replace(BLOCK_COMMENT, "")
            .replace(LINE_COMMENT, "")

    private companion object {
        const val SECURE_CAPTURE_SOURCE =
            "app/src/main/java/com/keepasskey/app/security/SecureCaptureActivity.kt"

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")

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
