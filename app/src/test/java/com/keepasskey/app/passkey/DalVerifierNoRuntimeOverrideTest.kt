package com.keepasskey.app.passkey

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DAL 校验器**不可被运行期重定向**的守卫（ISSUE-P3-125②）。
 *
 * 缺陷背景：端点与时钟曾以「测试注入点」为名做成 `@Singleton` 上的
 * `@Volatile internal var endpointOverride` / `clockMs`。`internal` 只限制**模块外**可见，
 * 于是本模块内任何生产代码都可以把 DAL 拉取改写到任意 URL——而 DAL 校验正是
 * 「RP 站点显式声明授权该应用」的唯一依据，重定向即等于整体架空该绑定。
 *
 * 现策略：两者改为**构造注入的只读策略**（生产由 `DalVerifierModule` 提供唯一实现）。
 * 本用例锁定该结构：源码中不得再出现可写的这两个字段声明，且必须存在构造参数与官方策略常量。
 */
class DalVerifierNoRuntimeOverrideTest {

    @Test
    fun `不得再存在可写的端点或时钟注入点`() {
        val code = stripComments(readSource(VERIFIER_SOURCE))

        assertFalse(
            "[$VERIFIER_SOURCE] 不得再声明可写的 endpointOverride 字段——" +
                "internal var 在模块内可被任意生产代码改写，等于可架空虚设的 DAL 校验",
            WRITABLE_ENDPOINT_OVERRIDE.containsMatchIn(code)
        )
        assertFalse(
            "[$VERIFIER_SOURCE] 不得再声明可写的 clockMs 字段（同因）",
            WRITABLE_CLOCK.containsMatchIn(code)
        )
    }

    @Test
    fun `端点与时钟必须为构造注入且生产策略唯一`() {
        val code = stripComments(readSource(VERIFIER_SOURCE))

        assertTrue(
            "DAL 校验器必须经构造注入端点解析策略",
            code.contains("private val endpointResolver: DalEndpointResolver")
        )
        assertTrue(
            "DAL 校验器必须经构造注入时钟策略",
            code.contains("private val clock: MillisClock")
        )
        assertTrue(
            "必须存在唯一的生产端点策略常量",
            code.contains(OFFICIAL_STRATEGY)
        )
        assertTrue(
            "生产 DI 必须提供这两个策略（DalVerifierModule）",
            File(repositoryRoot, DI_MODULE_SOURCE).isFile
        )

        // 生产策略的**行为**断言（比源码文本断言强）：官方端点 + 系统时钟。
        // 注：不以源码正则匹配该 URL——行内 `//` 会被「行注释剔除」误伤，行为断言不受此影响。
        assertEquals(
            "生产端点策略必须解析到官方 well-known 路径",
            "https://example.com/.well-known/assetlinks.json",
            DalEndpointResolver.Official.resolve("example.com")
        )
        val drift = kotlin.math.abs(MillisClock.SystemClock.now() - System.currentTimeMillis())
        assertTrue("生产时钟策略必须为系统时钟（偏差 ${drift}ms 过大）", drift < CLOCK_TOLERANCE_MS)
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    /** 剔除块注释与行注释——整改说明本身会写出被断言的字面量 */
    private fun stripComments(source: String): String = stripCommentsOnly(source)
    private companion object {
        const val VERIFIER_SOURCE =
            "app/src/main/java/com/keepasskey/app/passkey/DigitalAssetLinksVerifier.kt"
        const val DI_MODULE_SOURCE =
            "app/src/main/java/com/keepasskey/app/di/DalVerifierModule.kt"

        /** 禁止形态：可写的端点覆盖字段 */
        val WRITABLE_ENDPOINT_OVERRIDE = Regex("""var\s+endpointOverride""")

        /** 禁止形态：可写的时钟字段 */
        val WRITABLE_CLOCK = Regex("""var\s+clockMs""")

        /**
         * 必需形态：官方端点策略常量。
         * 仅断言常量名（不断言其后的 URL）——行为断言见测试主体，避免行内 `//` 被注释剔除误伤。
         */
        const val OFFICIAL_STRATEGY = "val Official = DalEndpointResolver"

        /** 生产时钟与系统时钟的允许偏差（毫秒） */
        const val CLOCK_TOLERANCE_MS = 5_000L


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
