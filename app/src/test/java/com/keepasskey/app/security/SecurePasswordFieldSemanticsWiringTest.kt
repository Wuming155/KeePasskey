package com.keepasskey.app.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 口令字段显式语义守卫（原 `AccessibilityNoticeWiringTest` 的第 4 项判据独立承接；
 * ISSUE-P3-324 摘除无障碍提示链路时该守卫与被测生产代码无关，故迁出保留）。
 *
 * 失效形态：`SecurePasswordField` 只依赖框架从 `PasswordVisualTransformation` **隐式**推导
 * 口令语义（ISSUE-P2-44 AC②）——读屏 / 自动填充框架将拿不到显式的 `password()` 语义与
 * 口令键盘类型，必须以源码比对锁定「显式声明」不回潮为隐式。
 */
class SecurePasswordFieldSemanticsWiringTest {

    private val secureFieldSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/components/SecurePasswordField.kt")

    @Test
    fun `口令字段显式声明 password 语义`() {
        val source = secureFieldSource

        assertTrue(
            "SecurePasswordField 必须显式 semantics { password() }",
            source.contains("semantics { password() }")
        )
        assertTrue(
            "密码键盘类型不得回退为普通文本（框架据此下发 IME 语义）",
            source.contains("keyboardType = KeyboardType.Password")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
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
