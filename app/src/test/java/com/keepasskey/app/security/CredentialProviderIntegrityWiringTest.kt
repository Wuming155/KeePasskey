package com.keepasskey.app.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-53（审计 F-24）「完整性门控接线」一致性检查（静态源码比对）。
 *
 * 本项失效形态不是「判定错」，而是**某条通道根本不查门控**：完整性策略原只在自动填充
 * fail-closed，CM 主通道（`KeePasskeyCredentialProviderService`）零命中——风险态下系统凭据
 * 弹窗仍可拿到候选，等于策略绕过。该缺陷在 JVM 侧无法以行为用例覆盖（服务依赖 Android
 * `CredentialProviderService` 框架），故沿用仓库既有静态接线检查先例（`ObscuredTouchWiringTest`
 * / `ColdStartAttachmentPurgeWiringTest`），守护以下不变式：
 *
 * 1. CM 服务在 **get 与 create 两条入口**各裁决一次 `awaitEnforcement()`（次数 ≥ 2）；
 * 2. 自动填充通道消费**同一**门控（两通道判据一致，无不对称）。
 */
class CredentialProviderIntegrityWiringTest {

    @Test
    fun `CM 两条入口与自动填充通道均消费完整性门控`() {
        val cmService = readSource(
            "app/src/main/java/com/keepasskey/app/passkey/KeePasskeyCredentialProviderService.kt"
        )
        val cmChecks = Regex("""runtimeIntegrityGate\.awaitEnforcement\(\)\.disableAutofill""")
            .findAll(cmService).count()
        assertTrue(
            "CM 服务应在 get / create 两条入口各裁决一次完整性门控（实际 $cmChecks 次）",
            cmChecks >= 2
        )

        val autofillService = readSource(
            "app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt"
        )
        assertTrue(
            "自动填充通道应消费同一完整性门控（awaitEnforcement）",
            autofillService.contains("runtimeIntegrityGate.awaitEnforcement()")
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
