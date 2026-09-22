package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 包名黑名单管理对话框（填充侧 / 保存侧共用）的**槽位接线守卫**（`ISSUE-P3-264` 的直接产物）。
 *
 * 起因：该弹窗曾把「关闭」焊进**状态相关**的 confirm 槽（`manualEntry` 为真时右下角变为
 * 可禁用的「新增」），且 else 分支只引用 `onDismiss` 而未调用——Kotlin 对 Unit 期望的 lambda
 * 会静默丢弃函数值 ⇒ 非手工模式（默认态）下全弹窗唯一的「关闭」是**死按钮**，正是用户所报
 * 「点了不关」的根因；同弹窗此前还与 §196 / §198 记下的「零测试引用」同源。
 *
 * 判据形态与本仓其余接线守卫一致（`SettingsSubscreenScaffoldWiringTest` 先例）：读源码文本
 * 比对，不依赖运行时。锚点缺失即报红（§77 口径：防空扫）；并以 §199 代入法证明区分力——
 * 把 §205 的历史坏形态文本代入同一谓词必须报红，不得只以「现态通过」自证。
 */
class PackageBlocklistDialogWiringTest {

    @Test
    fun `关闭必须无条件由dismiss槽承载且confirm槽不得状态化`() {
        val violations = wiringViolations(readSource(DIALOGS), readSource(SECTIONS))
        assertTrue("接线守卫不应报红：$violations", violations.isEmpty())
    }

    /**
     * 区分力证明（§199 代入法）：§205 的历史坏形态（confirm 槽吃 manualEntry 并承载「关闭」、
     * else 分支引用 onDismiss 未调用、dismiss 槽随状态消失）代入同一谓词必须逐项报红。
     */
    @Test
    fun `历史坏形态代入谓词必须报红`() {
        val badDialog = """
            confirmButton = {
                PackageBlocklistConfirmButton(
                    manualEntry = manualEntry,
                    pendingPackage = pendingPackage,
                    onConfirm = { if (manualEntry) submit(pendingPackage) else onDismiss }
                )
            },
            dismissButton = {
                PackageBlocklistDismissButton(manualEntry = manualEntry, onDismiss = onDismiss)
            }
        """.trimIndent()
        val badSections = """
            internal fun PackageBlocklistConfirmButton(
                manualEntry: Boolean,
                pendingPackage: String,
                onConfirm: () -> Unit
            ) {
                if (manualEntry) {
                    TextButton(onClick = onConfirm, enabled = pendingPackage.isNotBlank()) {
                        Text(stringResource(R.string.btn_add))
                    }
                } else {
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(R.string.btn_close))
                    }
                }
            }

            internal fun PackageBlocklistDismissButton(
                manualEntry: Boolean,
                onDismiss: () -> Unit
            ) {
                if (manualEntry) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_close))
                    }
                }
            }
        """.trimIndent()

        val violations = wiringViolations(badDialog, badSections)
        assertTrue("历史坏形态必须至少报出 confirm 槽承载关闭与 dismiss 槽状态化两类", violations.size >= 2)
        assertTrue(violations.any { it.contains("btn_close") })
        assertTrue(violations.any { it.contains("manualEntry") })
        assertTrue(violations.any { it.contains("onDismiss") })
    }

    /** 反向哨兵：谓词的锚点若被人从源码里改名 / 挪走，第一个用例必须因防空扫报红而非静默通过。 */
    @Test
    fun `谓词锚点与真实源码形态一致`() {
        val sections = readSource(SECTIONS)
        assertTrue(
            "切片锚点失效——守卫谓词已扫不到目标函数（防空扫失败）",
            sections.contains("internal fun PackageBlocklistConfirmButton(") &&
                sections.contains("internal fun PackageBlocklistDismissButton(")
        )
        assertTrue(
            "dismiss 槽切片应包含关闭文案锚点（R.string.btn_close）",
            sliceFunction(sections, "PackageBlocklistDismissButton")!!.contains("R.string.btn_close")
        )
    }

    /** 手工输入必须可退回名单模式（真双态切换），且回退文案 zh + en 双语在位。 */
    @Test
    fun `手工输入回退路径与双语文案在位`() {
        val dialogs = readSource(DIALOGS)
        val sections = readSource(SECTIONS)
        assertTrue(
            "onToggleManual 必须是真双态切换（manualEntry = !manualEntry）",
            dialogs.contains("manualEntry = !manualEntry")
        )
        assertTrue(
            "手工输入分支必须渲染回退入口（autofill_blacklist_manual_back）",
            sliceFunction(sections, "PackageBlocklistBody")!!.contains("R.string.autofill_blacklist_manual_back")
        )
        for (res in listOf("app/src/main/res/values/strings.xml", "app/src/main/res/values-en/strings.xml")) {
            assertTrue(
                "$res 缺少 autofill_blacklist_manual_back 文案",
                readSource(res).contains("autofill_blacklist_manual_back")
            )
        }
    }

    /**
     * 接线谓词：返回违反项清单（空 = 通过）。锚点缺失一律计为违反项（防空扫）。
     * 同一谓词同时作用于真实源码与历史坏形态文本（区分力证明共用）。
     */
    private fun wiringViolations(dialogSource: String, sectionsSource: String): List<String> {
        val found = mutableListOf<String>()

        // ① confirm 槽组件不得承载「关闭」文案，也不得再吃 manualEntry 状态
        val confirmFn = sliceFunction(sectionsSource, "PackageBlocklistConfirmButton")
            ?: return listOf("未找到 PackageBlocklistConfirmButton（防空扫）")
        if (confirmFn.contains("btn_close")) found += "confirm 槽不得承载 btn_close（ISSUE-P3-264）"
        if (confirmFn.contains("manualEntry")) found += "confirm 槽组件不得再吃 manualEntry 状态"

        // ② dismiss 槽组件必须无条件渲染「关闭」
        val dismissFn = sliceFunction(sectionsSource, "PackageBlocklistDismissButton")
            ?: return listOf("未找到 PackageBlocklistDismissButton（防空扫）")
        if (!dismissFn.contains("btn_close")) found += "dismiss 槽必须承载 btn_close"
        if (dismissFn.contains("manualEntry") || dismissFn.contains("if (")) {
            found += "dismiss 槽不得状态化（manualEntry 分支即「关闭随状态消失」的旧形态）"
        }

        // ③ 调用点必须把 DismissButton 直挂 onDismiss，且不得复活 §205 的状态相关转发
        if (!dialogSource.contains("PackageBlocklistDismissButton(onDismiss = onDismiss)")) {
            found += "对话框未把 PackageBlocklistDismissButton 直挂 onDismiss"
        }
        if (dialogSource.contains("else onDismiss")) {
            found += "confirm 槽存在「else onDismiss」状态相关关闭——else 分支未调用即死按钮（§205 / ISSUE-P3-264 坏形态）"
        }
        return found
    }

    /** 取顶层函数源码片段：从声明行到下一个顶格收括号（本仓 ktlint 格式下等价于函数体范围）。 */
    private fun sliceFunction(source: String, name: String): String? {
        val start = source.indexOf("internal fun $name(")
        if (start < 0) return null
        val end = source.indexOf("\n}", start)
        if (end < 0) return null
        return source.substring(start, end)
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SUBSCREEN_DIR =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens"

        const val DIALOGS = "$SUBSCREEN_DIR/AutofillBlocklistDialogs.kt"
        const val SECTIONS = "$SUBSCREEN_DIR/PackageBlocklistDialogSections.kt"

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
