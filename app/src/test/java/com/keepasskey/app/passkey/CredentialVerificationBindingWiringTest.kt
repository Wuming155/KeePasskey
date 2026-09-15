package com.keepasskey.app.passkey

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-76（威胁建模 Q-2）「CM 通道验证强度」接线检查（静态源码比对）。
 *
 * 缺陷形态是**接线退化**：CM / Passkey 通道的用户验证原先只凭 `BiometricResult.Success`
 * 回调放行（hook 可伪造），而自动填充通道早已用 Keystore `CryptoObject` 做密码学绑定。
 * 该行为在 JVM 侧无法端到端构造（依赖 `BiometricPrompt` 与 Keystore），故沿用仓库既有
 * 静态接线检查先例（`ObscuredTouchWiringTest` / `CredentialProviderIntegrityWiringTest`），
 * 守护三条不变式：① 认证确实传入绑定 Cipher；② 放行前确实校验 `isBound`；
 * ③ 取不到 Cipher 时走手动确认而非无绑定放行。
 */
class CredentialVerificationBindingWiringTest {

    @Test
    fun `CM 通道的用户验证要求密码学绑定`() {
        val source = readSource(
            "app/src/main/java/com/keepasskey/app/passkey/CredentialVerificationLauncher.kt"
        )

        assertTrue(
            "认证必须传入 Keystore 绑定 Cipher（cipher = authCipher）",
            source.contains("cipher = authCipher")
        )
        assertTrue(
            "准备绑定 Cipher 必须调用 prepareAutofillAuthCipher()",
            source.contains("prepareAutofillAuthCipher()")
        )
        assertTrue(
            "放行前必须校验认证结果确带绑定 Cipher（AutofillAuthBindingPolicy.isBound）",
            source.contains("AutofillAuthBindingPolicy.isBound(result)")
        )
        assertTrue(
            "无法建立绑定时必须退化为受保护窗口内手动确认（showManualConfirmation），不得无绑定放行",
            source.contains("showManualConfirmation(title, manualHint, confirmText, cancelText, onVerified, onRejected)")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
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

        /** 自工作目录向上回溯的层数（app 模块测试工作目录为 app/，1 层即仓库根） */
        const val ROOT_SEARCH_DEPTH = 4
    }
}
