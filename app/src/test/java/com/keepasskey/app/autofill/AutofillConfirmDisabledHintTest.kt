package com.keepasskey.app.autofill

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-360 AC④（确认页落点）：填充确认按钮禁用时必须就地给出原因。
 *
 * 缺陷形态：`AutofillConfirmActivity.showManualConfirm` 对首现调用方要求勾选授权
 * 才置 `confirmEnabled = false`，但界面只把按钮变灰、无任何解释——用户不知道
 * 为什么点不动，更不知道勾哪里。
 *
 * 本用例为接线守卫（静态源码断言）：
 * 1. 禁用态 hint 必须拼接 `autofill_confirm_disabled_reason`，且与按钮门控共用同一条件；
 * 2. 该文案必须 values + values-en 双写（单语缺失即守卫红）。
 *
 * 规模闸门拆分后：界面体收口于 [AutofillConfirmManualScreen]，Activity 只作 setContent 委托，
 * 守卫改读拆分后文件（行为锚点不变）。
 */
class AutofillConfirmDisabledHintTest {

    private val screenSource: String by lazy { readSource(CONFIRM_SCREEN_PATH) }
    private val activitySource: String by lazy { readSource(CONFIRM_ACTIVITY_PATH) }

    @Test
    fun `确认按钮禁用时 hint 必须拼接原因文案且与门控同条件`() {
        assertTrue(
            "确认页界面必须在禁用态拼接原因文案（hint 位就地解释）",
            screenSource.contains("R.string.autofill_confirm_disabled_reason")
        )
        assertTrue(
            "原因文案必须由 confirmEnabled 门控（勾选授权后随之消失，条件不漂移）",
            screenSource.contains("hint = if (confirmEnabled)")
        )
        assertTrue(
            "按钮门控必须收敛为同一 confirmEnabled 局部值",
            screenSource.contains("confirmEnabled = confirmEnabled")
        )
        assertTrue(
            "门控条件本身不得被改写（首现调用方未勾选授权即禁用）",
            screenSource.contains("val confirmEnabled = !requiresExplicitAuthorization || trustChecked")
        )
        assertTrue(
            "Activity 必须把确认页委托给 AutofillConfirmManualScreen（唯一界面实现）",
            activitySource.contains("AutofillConfirmManualScreen(")
        )
    }

    @Test
    fun `禁用原因文案 values 与 values-en 双写`() {
        assertTrue(
            "默认语料必须含 autofill_confirm_disabled_reason",
            readSource(STRINGS_ZH_PATH).contains("autofill_confirm_disabled_reason")
        )
        assertTrue(
            "英文语料必须含 autofill_confirm_disabled_reason（双写纪律）",
            readSource(STRINGS_EN_PATH).contains("autofill_confirm_disabled_reason")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CONFIRM_ACTIVITY_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt"
        const val CONFIRM_SCREEN_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmManualScreen.kt"
        const val STRINGS_ZH_PATH = "app/src/main/res/values/strings.xml"
        const val STRINGS_EN_PATH = "app/src/main/res/values-en/strings.xml"

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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 $ROOT_SEARCH_DEPTH 层）")
        }
    }
}
