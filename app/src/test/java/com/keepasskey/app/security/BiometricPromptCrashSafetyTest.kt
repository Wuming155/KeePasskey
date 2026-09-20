package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P1-223：生物识别开关与认证弹窗崩溃安全守卫单测。
 *
 * 背景：AndroidX BiometricPrompt 要求当认证器不包含 DEVICE_CREDENTIAL 时，
 * 必须调用 setNegativeButtonText() 显式设置非空文本，否则 build() 会抛出
 * IllegalArgumentException 导致主线程致命崩溃闪退。
 *
 * 本类通过结构化源码与契约守护验证：
 * 1. BiometricAuthManager 必须针对 !usesDeviceCredential 做了兜底，缺失参数时自动取 R.string.btn_cancel；
 * 2. 严禁出现未配置负向按钮文本直接 build() 的失误；
 * 3. BiometricEnableCoordinator 等调用处显式传入了取消按钮文本。
 */
class BiometricPromptCrashSafetyTest {

    private val authManagerSource = readSource(AUTH_MANAGER_PATH)
    private val coordinatorSource = readSource(COORDINATOR_PATH)

    @Test
    fun `纯生物识别分支必须具有负向按钮兜底保护`() {
        assertTrue(
            "BiometricAuthManager 必须判断 !usesDeviceCredential 并安全设置负向按钮",
            authManagerSource.contains("if (!usesDeviceCredential)")
        )
        assertTrue(
            "当负向按钮未显式传参时必须回退到 R.string.btn_cancel，严禁漏设引发闪退",
            authManagerSource.contains("activity.getString(R.string.btn_cancel)")
        )
    }

    @Test
    fun `严禁在非设备凭据分支仅依赖可空入参`() {
        // 修复前的缺陷写法：if (!usesDeviceCredential && negativeButtonText != null)
        assertFalse(
            "不得残留仅在 negativeButtonText != null 时才设置的危险逻辑（null 时会导致 build 抛出异常）",
            authManagerSource.contains("if (!usesDeviceCredential && negativeButtonText != null)")
        )
    }

    @Test
    fun `设置页生物识别协调器必须显式提供取消按钮文案`() {
        assertTrue(
            "BiometricEnableCoordinator 开启验证时必须显式透传 negativeButtonText",
            coordinatorSource.contains("negativeButtonText = strings.get(R.string.btn_cancel)")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在: $path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val AUTH_MANAGER_PATH =
            "app/src/main/java/com/keepasskey/app/security/BiometricAuthManager.kt"
        const val COORDINATOR_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/BiometricEnableCoordinator.kt"

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
            error("未能定位仓库根目录")
        }
    }
}
