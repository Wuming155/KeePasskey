package com.keepasskey.app.ui.components

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 触觉反馈覆盖面与收口守卫（**ISSUE-P3-364** 验收 AC①②③ + §349 收口不回归，
 * 静态源码比对，体例沿用 [com.keepasskey.app.ui.CloudSyncSwitchWiringTest] 的
 * 「真实计数 + 正控制防空转 + 扫描面下限」口径）。
 *
 * 本条修的**不是**接线（开关链 P3-358 已完整），而是**覆盖面不足**：整改前全仓只有
 * 11 个触点，最高频的验证码 / 生成器复制动作永无触感 ⇒ 用户报「开了没用」。
 * 故本类的断言指向三层：
 *
 * 1. **收口唯一**（§349 不回归）：`LocalHapticFeedback` / `performHapticFeedback`
 *    在 `app/src/main` 全仓仅允许出现在 `Haptics.kt`，新增触点不得裸调；
 * 2. **覆盖面有下限**：全仓 `maybeHaptic(HapticFeedbackType.…)` 触点总数 ≥ 17
 *    （P3-364 整改前基线 11 + AC① 新增 ≥6），跌破即覆盖回退；
 * 3. **AC① 五个目标文件真实接线**：每个文件的 `rememberMaybeHaptic()` 调用 ≥ 1
 *    （导入不计——只数带 `()` 的调用点，防止「只 import 不调用」的假接线）。
 *
 * 所有计数均为**现跑现读**的真实数字；扫描面（.kt 文件数）带下限，防空转假绿。
 */
class HapticCoverageWiringTest {

    // ========== AC② / §349：收口唯一，禁止裸调 ==========

    @Test
    fun `全仓 LocalHapticFeedback 仅限 Haptics 收口点`() {
        val files = appMainKotlinFiles()
        assertTrue(
            "扫描面异常：app/src/main 下 .kt 仅 ${files.size} 个（扫描器可能已失效）",
            files.size >= MIN_KT_FILES
        )

        val offenders = files
            .filter { it.name != HAPTICS_FILE && stripCommentsOnly(it.readText()).contains(LOCAL_HAPTIC) }
            .map { it.name }
        assertTrue("裸调 LocalHapticFeedback 越出收口点：$offenders", offenders.isEmpty())

        // 正控制：同一次扫描必须命中收口点自身的符号，否则「仅剩一处」只是扫描器失灵的假绿
        val hapticsSource = stripCommentsOnly(readSource(HAPTICS_PATH))
        assertTrue(
            "正控制失守：Haptics.kt 剥注释后未命中 $LOCAL_HAPTIC——零残留可能是扫描面失明",
            hapticsSource.contains(LOCAL_HAPTIC)
        )
    }

    @Test
    fun `全仓 performHapticFeedback 调用仅限 Haptics 收口点`() {
        val files = appMainKotlinFiles()
        assertTrue(
            "扫描面异常：app/src/main 下 .kt 仅 ${files.size} 个（扫描器可能已失效）",
            files.size >= MIN_KT_FILES
        )

        val offenders = files
            .filter { it.name != HAPTICS_FILE && stripCommentsOnly(it.readText()).contains(PERFORM_HAPTIC) }
            .map { it.name }
        assertTrue("裸调 performHapticFeedback 越出收口点：$offenders", offenders.isEmpty())

        val hapticsSource = stripCommentsOnly(readSource(HAPTICS_PATH))
        assertTrue(
            "正控制失守：Haptics.kt 剥注释后未命中 $PERFORM_HAPTIC——零残留可能是扫描面失明",
            hapticsSource.contains(PERFORM_HAPTIC)
        )
    }

    // ========== AC①：覆盖面下限（真实触点总数） ==========

    @Test
    fun `全仓触点总数不低于 P3-364 整改后的覆盖下限`() {
        val files = appMainKotlinFiles()
        assertTrue(
            "扫描面异常：app/src/main 下 .kt 仅 ${files.size} 个（扫描器可能已失效）",
            files.size >= MIN_KT_FILES
        )

        val total = files.sumOf { file ->
            stripCommentsOnly(file.readText()).countOccurrencesOf(TOUCHPOINT_PREFIX)
        }
        assertTrue(
            "全仓触点数 $total 低于覆盖下限 $MIN_TOUCHPOINTS" +
                "（P3-364 整改前基线 11 + AC① 新增 ≥6；跌破即覆盖面回退）",
            total >= MIN_TOUCHPOINTS
        )

        // 正控制：收口调用本身必须被命中，否则计数恒 0 的「绿」无鉴别力
        assertTrue(
            "正控制失守：同一次扫描未命中任何 rememberMaybeHaptic()——触点计数通道可能失效",
            files.sumOf { stripCommentsOnly(it.readText()).countOccurrencesOf("rememberMaybeHaptic()") } >= 1
        )
    }

    // ========== AC①：五个目标文件各自真实接线 ==========

    @Test
    fun `五个目标文件各自至少一处 rememberMaybeHaptic 调用`() {
        TARGET_FILES.forEach { path ->
            val source = stripCommentsOnly(readSource(path))
            // 只数带 `()` 的调用点：import 行无括号，「只 import 不调用」计 0 即红
            val count = source.countOccurrencesOf("rememberMaybeHaptic()")
            assertTrue(
                "$path 的 rememberMaybeHaptic() 调用数为 $count，ISSUE-P3-364 AC①② 要求 ≥1",
                count >= 1
            )
        }
    }

    // ========== helpers ==========

    private fun String.countOccurrencesOf(token: String): Int {
        var count = 0
        var at = indexOf(token)
        while (at >= 0) {
            count++
            at = indexOf(token, at + token.length)
        }
        return count
    }

    /** app/src/main 下全部 `.kt`（唯一生产可运行面） */
    private fun appMainKotlinFiles(): List<File> =
        File(repositoryRoot, APP_MAIN).walkTopDown()
            .filter { it.isFile && it.name.endsWith(KOTLIN_SUFFIX) }
            .toList()

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val KOTLIN_SUFFIX = ".kt"
        const val APP_MAIN = "app/src/main"
        const val HAPTICS_FILE = "Haptics.kt"
        const val HAPTICS_PATH =
            "app/src/main/java/com/keepasskey/app/ui/components/Haptics.kt"
        const val LOCAL_HAPTIC = "LocalHapticFeedback"
        const val PERFORM_HAPTIC = "performHapticFeedback"

        /** 收口回调的触点调用前缀（含类型实参起点，计数时逐个命中） */
        const val TOUCHPOINT_PREFIX = "maybeHaptic(HapticFeedbackType."

        /**
         * 扫描面下限：实测 app/src/main 共 442 个 .kt（P3-364 整改时读数）。
         * 跌破 400 视为扫描器 / 源码树异常，防「零命中假绿」。
         */
        const val MIN_KT_FILES = 400

        /**
         * 触点总数下限：P3-364 整改前基线 11 + AC① 至少新增 6 = 17。
         * 整改实际落地 18（列表徽标 1 + TotpCard 三按钮 3 + 打开网址 1 + 生成器复制 1 + 认证器复制 1）。
         */
        const val MIN_TOUCHPOINTS = 17

        /** ISSUE-P3-364 AC① 要求补齐触感的五个目标文件（相对仓库根） */
        val TARGET_FILES = listOf(
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultEntryRowLayouts.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailCards.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailComponents.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/generator/GeneratorDisplayCard.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/authenticator/AuthenticatorScreen.kt"
        )

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
