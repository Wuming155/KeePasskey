package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-94（审计 F-20）回归：**清单权限口径**。
 *
 * 审计原表述为「合并清单冗余 / 废弃权限，且无 `tools:node="remove"`」，本批按以下三条裁决落地
 * （逐条附核实时点与依据，见清单内注释）：
 * 1. **不删除** `ACCESS_NETWORK_STATE` / `USE_BIOMETRIC`——二者是本模块**自身**的运行期需求，
 *    改依赖第三方库的传递声明属脆弱耦合；且 release 合并清单证据显示各权限**只出现一次**
 *    （合并器已去重），「冗余」仅存在于源文件层面；
 * 2. `USE_FINGERPRINT`（API 28 起弃用，仅由 `androidx.biometric` 旧兼容路径注入）加
 *    `tools:node="remove"`——minSdk 36 ⇒ 该路径永不执行；
 * 3. **保留** `CAMERA`（zxing 扫码取景所需），不得被移除。
 *
 * 断言前剔除 XML 注释（整改说明自身会提到权限名与 `tools:node` 字样）。
 */
class ManifestPermissionHygieneTest {

    private val declarations: String by lazy {
        val file = File(repositoryRoot, MANIFEST_PATH)
        assertTrue("清单文件不存在：$MANIFEST_PATH", file.isFile)
        file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    @Test
    fun `清单必须启用 tools 命名空间`() {
        assertTrue(
            "使用 tools:node 前必须在 <manifest> 上声明 xmlns:tools",
            declarations.contains("xmlns:tools=\"http://schemas.android.com/tools\"")
        )
    }

    @Test
    fun `弃用的 USE_FINGERPRINT 必须以 tools node remove 移除`() {
        assertTrue(
            "USE_FINGERPRINT 已被 API 28 弃用且 minSdk 36 下无用例路径，必须显式移除",
            Regex(
                """<uses-permission\s+android:name="android\.permission\.USE_FINGERPRINT"\s+tools:node="remove"\s*/>"""
            ).containsMatchIn(declarations)
        )
    }

    @Test
    fun `CAMERA 不得被移除（zxing 扫码取景所需）`() {
        assertFalse(
            "CAMERA 由 zxing 注入且为扫码功能所需，禁止对其施加 tools:node=\"remove\"",
            Regex(
                """<uses-permission\s+android:name="android\.permission\.CAMERA"\s+tools:node="remove""""
            ).containsMatchIn(declarations)
        )
    }

    @Test
    fun `本模块自身运行期依赖的权限必须保留声明`() {
        // AC① 未采纳的回归守卫：本模块自身运行期依赖网络状态与生物识别权限，
        // 不得为了「去重」而删掉声明（否则库清单一旦调整即静默失去权限）
        for (permission in listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.USE_BIOMETRIC",
            "android.permission.HIDE_OVERLAY_WINDOWS",
            "android.permission.POST_NOTIFICATIONS"
        )) {
            assertTrue(
                "$permission 必须由本模块显式声明（属本应用运行期需求，不依赖第三方库传递声明）",
                declarations.contains("android:name=\"$permission\"")
            )
            assertFalse(
                "$permission 不得被 tools:node=\"remove\" 移除",
                Regex("""android:name="${Regex.escape(permission)}"\s+tools:node="remove"""").containsMatchIn(declarations)
            )
        }
    }

    private companion object {
        const val MANIFEST_PATH = "app/src/main/AndroidManifest.xml"
        const val ROOT_SEARCH_DEPTH = 6

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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
