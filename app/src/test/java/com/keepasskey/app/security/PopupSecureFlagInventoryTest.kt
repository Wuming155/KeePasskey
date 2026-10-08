package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Popup 系窗口的「敏感内容面」清单锁（ISSUE-P3-79）。
 *
 * ## 本项为何以「清单锁 + 记号扫描」收口，而不是给全部 Popup 加 flag
 *
 * 条目 AC③ 明确要求：**无敏感内容的调用点留痕说明「无需接线」，避免「全量加 flag」的过度改动**。
 * 2026-09-15 逐点直读源码核实（结论写入 `SecureDialog` 的 KDoc）：全仓 Popup 调用点恰好 4 处、
 * 8 个菜单项**全部为静态动作文案 / provider 名称**，无任何凭据类插值 ⇒ 无需接线。
 * 2026-09-26 顶栏「排序 / 锁定」收敛进溢出菜单后重新盘点：调用点仍 4 处、菜单项 10 个，
 * 新增两项均为静态动作文案 ⇒ 结论不变。
 * 2026-09-26 顶栏溢出菜单新增第 3 项「扫码」后重新盘点：调用点仍 4 处、菜单项 11 个，
 * 新增项为静态动作文案（字符串资源 `vault_scan_menu`），**无凭据类插值**——扫码结果
 * 不经菜单渲染、直接经 CharArray 上行 ViewModel ⇒ 结论不变。
 * 2026-09-26 `ISSUE-P3-337` 第 4 片（扫码导入通行密钥）后重新盘点：**分流不新增菜单项**
 * （顶栏仍那一个「扫码」项，`otpauth:` / JSON 两种载荷在同一对话框里分流），
 * 新增的是**一个确认对话框** `PasskeyImportConfirmDialog`——它按 `PD-48` 裁决三
 * **跟随「禁止截屏与录屏」开关**（与 `PD-47` 的扫码取景同口径），故**不**列入本文件所核的
 * 「无条件强制遮罩」4 类对话框，也不新增 Popup 调用点 ⇒ 调用点仍 4 处、菜单项仍 11 个，
 * 结论不变。其正文只呈现 rpId / 类型 / 拟用标题 / 丢弃项 / 未导入把数，
 * **不回显 `credentialId` / `userHandle` / 私钥**（`SecureDialogWindowEffect` 之外的第二层防护）。
 * 2026-09-26 同条目**第 4 片（下）**（编辑页 Q1 附加入口 + AC⑪① 只读锁）后重新盘点：编辑页
 * **复用了上面这两个现件**——扫码对话框只多了一个 `titleRes` 参数（AC⑤ 允许的「参数化」），
 * 确认对话框多的是「替换」语义的一行正文 ⇒ **零新增对话框、零新增 Popup 调用点、零新增菜单项**；
 * 新增的只读卡片只渲染**非受保护**字段值（rpId / 算法 / 计数器），受保护字段改显「不显示明文」
 * 说明 ⇒ 调用点仍 4 处、菜单项仍 11 个，结论不变。
 * 2026-09-27 `ISSUE-P3-342`（编辑页「解除绑定」改为真实写操作）后重新盘点：新增
 * **一个确认对话框** `EntryEditUnbindPasskeyDialog`（不可逆动作须先表态），它与
 * `PasskeyImportConfirmDialog` 同族——按 `PD-48` 裁决三**跟随「禁止截屏与录屏」开关**，
 * 故同样**不**列入本文件所核的「无条件强制遮罩」4 类；正文只说「将删除本条目的通行密钥凭据
 * （私钥与 PRF 种子一并移除）」，**不回显任何凭据材料** ⇒ 零新增 Popup 调用点、零新增菜单项，
 * 调用点仍 4 处、菜单项仍 11 个，结论不变。
 * 2026-09-28 `ISSUE-P3-360` AC④a（顶栏溢出菜单新增「选择」项进入批量模式）后重新盘点：
 * 调用点仍 4 处（`VaultListTopBars` 既有 DropdownMenu 内加一项，非新调用点）；
 * 菜单项 11 → **12**；新增项为纯静态动作文案（`vault_menu_select_entries`，无任何插值、
 * 不渲染凭据）⇒ 仍无需接线，由本测试的记号扫描与计数判据当场复验。
 * 2026-10-08 `ISSUE-P2-544`（高级搜索自顶栏独立图标收敛进溢出菜单）后重新盘点：
 * 调用点仍 4 处（同上，既有 DropdownMenu 内加一项，非新调用点）；菜单项 12 → **13**；
 * 新增项为纯静态动作文案（`search_advanced_title`，无任何插值、不渲染凭据），
 * 同批**删除**了顶栏那个独立 `Tune` 图标钮（不属菜单项、不含 Popup 调用点，不影响计数口径）
 * ⇒ 仍无需接线，由本测试的记号扫描与计数判据当场复验。
 *
 * 但「今天无需接线」不等于「永远无需」：一旦某个菜单开始渲染口令 / TOTP，或新增了第 5 个
 * Popup 调用点，前次的结论即失效。故把该前提**变成可执行的守卫**：
 *
 * 1. **清单锁**：Popup 调用点所在文件集合必须与已核实清单**完全相等**——
 *    新增（未复核）或删除（清单失效）都会报红，强制重新盘点；
 * 2. **敏感记号扫描**：逐一抽取每个 `DropdownMenu(...)` 的**菜单块**（花括号配平），
 *    断言块内不出现凭据类记号（`readString(` / `password` / `totp` / `otpauth` / `secret` 等）
 *    ——一旦菜单项开始渲染凭据，本用例当场失败并提示必须补
 *    `PopupProperties(securePolicy = SecureFlagPolicy.SecureOn)`。
 */
class PopupSecureFlagInventoryTest {

    @Test
    fun `Popup 调用点清单与已核实清单完全一致`() {
        val actual = popupCallSiteFiles()
            .map { it.relativeTo(repositoryRoot).invariantSeparatorsPath }
            .toSet()

        assertEquals(
            "Popup 调用点集合已变化——请重新盘点其菜单内容：无凭据类插值则在" +
                "SecureDialog 的 KDoc 与本清单中登记「无需接线」；有凭据则必须补 " +
                "PopupProperties(securePolicy = SecureFlagPolicy.SecureOn) 后再更新本清单。",
            VERIFIED_POPUP_CALL_SITES,
            actual
        )
    }

    @Test
    fun `任何菜单块内都不得出现凭据类记号`() {
        val offenders = mutableListOf<String>()

        popupCallSiteFiles().forEach { file ->
            val source = file.readText()
            menuBlocks(source).forEachIndexed { index, block ->
                SENSITIVE_MARKERS.filter { block.contains(it) }.forEach { marker ->
                    offenders += "${file.name} 第 ${index + 1} 个菜单块含敏感记号 `$marker`"
                }
            }
        }

        assertTrue(
            "Popup 菜单块出现凭据类内容，必须为该调用点补 securePolicy（ISSUE-P3-79）：$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `菜单块抽取必须真的覆盖到菜单项（防空扫守住本用例自身的有效性）`() {
        val blocks = popupCallSiteFiles().flatMap { menuBlocks(it.readText()) }
        val itemCount = blocks.sumOf { Regex("DropdownMenuItem\\(").findAll(it).count() }

        assertTrue(
            "至少应抽取到 4 个 DropdownMenu 菜单块（含 ExposedDropdownMenuBox 内的那个），实际 ${blocks.size}",
            blocks.size >= 4
        )
        assertTrue(
            "抽取到的菜单块必须包含 DropdownMenuItem，否则上面的敏感记号扫描是**空扫**、" +
                "本用例等于失效（历史上曾因括号 / 花括号共用一个深度计数器而截断在参数表处）",
            itemCount > 0
        )
        assertEquals(
            "菜单项计数须与已核实清单一致（13 项：2026-10-08 ISSUE-P2-544 新增「高级搜索」，" +
                "此前 12 项系 2026-09-28 ISSUE-P3-360 AC④a 新增「选择」）；" +
                "计数变化说明菜单结构变动，须重新盘点",
            13,
            itemCount
        )
    }

    /** 全仓（`app/src/main/java`）包含 Popup 系调用点的源文件 */
    private fun popupCallSiteFiles(): List<File> =
        File(repositoryRoot, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { source ->
                POPUP_CALL_MARKERS.any { source.readText().contains(it) }
            }
            .toList()

    /**
     * 抽取每个 `DropdownMenu(` 调用的**完整文本**：参数表（括号配平）＋其后的尾随 lambda
     * （花括号配平，即菜单项内容）。仅用于静态记号扫描，不追求完整 Kotlin 解析。
     *
     * 注意：**不能**用单一深度计数器同时统计 `(` 与 `{`——`DropdownMenu(expanded = …,
     * onDismissRequest = { … }) { 菜单项 }` 中参数表内的 `{…}` 会先把深度归零，
     * 导致抽取在参数表结束处截断，菜单项被漏扫（等于本用例失效）。
     */
    private fun menuBlocks(source: String): List<String> {
        val blocks = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = source.indexOf("DropdownMenu(", from)
            if (start < 0) break
            val openParen = source.indexOf('(', start)
            val parenEnd = matchDelimiter(source, openParen, '(', ')') ?: break
            // 参数表之后的第一个 `{` 即菜单内容的尾随 lambda
            val braceStart = source.indexOf('{', parenEnd)
            val braceEnd = if (braceStart >= 0) matchDelimiter(source, braceStart, '{', '}') else null
            val end = braceEnd ?: parenEnd
            blocks += source.substring(start, end + 1)
            from = end + 1
        }
        return blocks
    }

    /** 自 [openIndex] 处的 [open] 起配平，返回对应 [close] 的下标；未闭合返回 null。 */
    private fun matchDelimiter(source: String, openIndex: Int, open: Char, close: Char): Int? {
        if (openIndex < 0) return null
        var depth = 0
        var index = openIndex
        while (index < source.length) {
            when (source[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private companion object {
        /** Popup 系窗口的 Compose 调用记号 */
        val POPUP_CALL_MARKERS = listOf("DropdownMenu(", "ExposedDropdownMenuBox(")

        /**
         * **已核实清单（2026-09-15 逐点直读源码；2026-09-26 随顶栏菜单收敛重新盘点为
         * 4 处调用点、10 个菜单项）**：全部静态文案 ⇒ 无需接线。
         * 该清单与 `SecureDialog` 的 KDoc「未能覆盖」一节保持同步。
         */
        val VERIFIED_POPUP_CALL_SITES = setOf(
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListTopBars.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultGroupRow.kt",
            // §194：详情页顶栏的溢出菜单**原样**下沉到同包段落组件，调用点随之迁到新文件。
            // 重新盘点结论不变：3 个菜单项全是静态动作文案（移动到分组 / 删除条目 / 删除共享图标），
            // 无口令 / TOTP / 密钥 / 用户数据插值 ⇒ 仍无需接线；当时菜单项总数 8 未变
            // （2026-09-26 顶栏「排序 / 锁定」收敛进溢出菜单后现为 10），
            // 由本测试的「菜单块敏感记号扫描」与「计数判据」当场复验（只换定位，不放宽强度）。
            "app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailTopBarSections.kt",
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncComponents.kt"
        )

        /**
         * 凭据类记号：命中即说明该菜单可能承载敏感明文（口令 / TOTP / 密钥 / 用户数据插值）。
         * 保守取值——`password` 亦覆盖 `passwordField` 一类命名，宁可误报促人工复核。
         */
        val SENSITIVE_MARKERS = listOf(
            "readString(",
            "password",
            "Password",
            "totp",
            "Totp",
            "TOTP",
            "otpauth",
            "secret",
            "Secret",
            "userName"
        )

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
