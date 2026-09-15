package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 清单导出面与启动器入口硬化守卫（ISSUE-P3-85）。
 *
 * ## 为什么是静态守卫而不是「CI 里另跑一段脚本」
 *
 * 本项要求「所有 `exported=true` 组件必须带 `permission` 或来自白名单」。
 * 放在**单元测试**里而非仅 CI 脚本，是因为它同时进 fast-gate 与本地 `test`——
 * 未来第三方依赖注入无权限保护的 `exported` 组件时**当场**报红，
 * 且白名单的**双向一致性**（既不过窄误红、也不留失效条目）可被断言。
 *
 * ## 两条不变式
 *
 * 1. **导出面最小化**：`exported=true` 的组件要么自带 `android:permission`，
 *    要么在 [EXPORTED_WITHOUT_PERMISSION] 白名单中（逐条附理由）；
 *    白名单与「实际无权限导出的组件集合」**双向相等**——多一条即失效条目，少一条即漏网。
 * 2. **启动器入口的回归约束**（P3-85 ①）：`MainActivity` 是唯一无权限保护的导出组件，
 *    故必须 `launchMode="singleTask"` + `taskAffinity=""`（消除反复拉起 / 清任务栈的
 *    DoS 与 UI 干扰面），且**不得消费任何外部 intent 数据**——
 *    该约束一旦被打破，「可被任意应用拉起」就从「DoS 面」升级为「输入注入面」。
 */
class ExportedComponentHygieneTest {

    private val manifestSource: String
        get() = readSource("app/src/main/AndroidManifest.xml")

    private val mainActivitySource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/MainActivity.kt")

    /** 清单中每个导出组件的 `android:name`（按导出面穷举） */
    private fun exportedComponents(): List<ExportedComponent> =
        COMPONENT_REGEX.findAll(manifestSource).mapNotNull { match ->
            val body = match.groupValues[2]
            if (!body.contains(EXPORTED_TRUE)) return@mapNotNull null
            val name = NAME_REGEX.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            ExportedComponent(
                tag = match.groupValues[1],
                name = name,
                permission = PERMISSION_REGEX.find(body)?.groupValues?.get(1)
            )
        }.toList()

    @Test
    fun `每个 exported 组件要么受权限保护要么在白名单内`() {
        val offenders = exportedComponents()
            .filter { it.permission == null }
            .filter { it.name !in EXPORTED_WITHOUT_PERMISSION }

        assertTrue(
            "下列组件导出且无 android:permission 保护，且不在白名单中："
                + offenders.joinToString { "${it.tag}:${it.name}" }
                + "。请补 android:permission，或在白名单中登记并写明理由。",
            offenders.isEmpty()
        )
    }

    @Test
    fun `白名单与无权限导出组件集合双向相等（防失效条目与漏网）`() {
        val withoutPermission = exportedComponents()
            .filter { it.permission == null }
            .map { it.name }
            .toSet()

        assertEquals(
            "白名单必须与「实际导出且无权限保护」的组件集合完全一致"
                + "（多出=失效条目，缺失=漏网）",
            withoutPermission,
            EXPORTED_WITHOUT_PERMISSION.keys
        )
    }

    @Test
    fun `受权限保护的导出组件必须声明具体权限`() {
        val protectedOnes = exportedComponents().filter { it.permission != null }

        assertTrue("本仓应存在受权限保护的导出服务（自动填充 / 凭据提供者）", protectedOnes.isNotEmpty())
        protectedOnes.forEach { component ->
            assertTrue(
                "${component.name} 的 android:permission 不得为空串",
                component.permission!!.isNotBlank()
            )
            assertTrue(
                "${component.name} 必须使用系统签名级 BIND_* 权限",
                component.permission!!.startsWith("android.permission.BIND_")
            )
        }
    }

    @Test
    fun `启动器入口声明 singleTask 与空 taskAffinity`() {
        val mainActivity = exportedComponents().single { it.name == LAUNCHER_ACTIVITY }
        val body = COMPONENT_REGEX.findAll(manifestSource)
            .map { it.groupValues[2] }
            .single { it.contains("android:name=\"$LAUNCHER_ACTIVITY\"") }

        assertEquals("启动器入口必须仍是唯一无权限导出的组件", null, mainActivity.permission)
        assertTrue(
            "ISSUE-P3-85 ①：MainActivity 必须 launchMode=\"singleTask\"",
            body.contains("android:launchMode=\"singleTask\"")
        )
        assertTrue(
            "ISSUE-P3-85 ①：MainActivity 必须 taskAffinity=\"\"",
            body.contains("android:taskAffinity=\"\"")
        )
    }

    @Test
    fun `启动器入口不得消费任何外部 intent 数据（DoS 面不得升级为输入注入面）`() {
        listOf(
            "getStringExtra",
            "getParcelableExtra",
            "getSerializableExtra",
            "onNewIntent",
            "intent.action",
            "intent?.action"
        ).forEach { api ->
            assertFalse(
                "MainActivity 不得出现 $api——启动器入口可被任意应用拉起，消费外部 intent 即成为输入注入面",
                mainActivitySource.contains(api)
            )
        }
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private data class ExportedComponent(val tag: String, val name: String, val permission: String?)

    private companion object {
        const val LAUNCHER_ACTIVITY = ".MainActivity"
        const val EXPORTED_TRUE = "android:exported=\"true\""

        /**
         * 允许「导出但无 android:permission」的组件白名单（逐条附理由）。
         *
         * **为什么只应有启动器入口**：启动器 Activity 必须导出才能被 Launcher 拉起，
         * 而 launcher Intent 无法附带自定义权限（系统不会持有），故只能靠
         * 「不消费外部 intent + singleTask + 空 taskAffinity」把攻击面压到 DoS 级。
         */
        val EXPORTED_WITHOUT_PERMISSION: Map<String, String> = mapOf(
            LAUNCHER_ACTIVITY to
                "启动器入口：必须导出才能被 Launcher 拉起；无权限可用，改以「不消费外部 intent" +
                " + singleTask + taskAffinity=\"\"」收敛攻击面（见同文件另两条用例）"
        )

        /** `<activity|service|receiver|provider ...>` 起始标签整体（跨行） */
        val COMPONENT_REGEX = Regex(
            """<(activity|service|receiver|provider)\b(.*?)>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val NAME_REGEX = Regex("""android:name="([^"]+)"""")
        val PERMISSION_REGEX = Regex("""android:permission="([^"]+)"""")

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
