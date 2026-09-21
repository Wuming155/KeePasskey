package com.keepasskey.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P2-242` 守护用例：锁定「**Passkey 徽标防折行 ↔ 列表行与详情页标题自适应让位**」布局契约。
 *
 * ## 本项要防的失效形态
 * 2026-09-21 真机实拍物证确证：
 * 1. 当条目标题较长（如 `cm-probe-user@example.com`）时，列表行未限制标题宽度权重，
 *    导致右侧 `PasskeyBadge` 宽度被挤压，内部 "Passkey" 7 个字母发生软换行（softWrap），
 *    形成丑陋的垂直竖排文本穿插在钥匙图标右侧；
 * 2. 当条目标题更长（如 `fake-passkey-probe@example.com@www.passkeys.io` 46 字符）时，
 *    标题完全占满整行宽度，导致右侧 `PasskeyBadge` 完全被挤出屏幕，用户误以为通行密钥未保存。
 *
 * 故本类断言以下布局守卫约束：
 * 1. [PasskeyBadge] 内的文本必须显式锁定 `maxLines = 1` 且 `softWrap = false`，杜绝垂直挤压变形；
 * 2. [StandardEntryLayout] 标题 Text 必须声明 `Modifier.weight(1f, fill = false)`；
 * 3. [EntryHeaderSection] 详情页标题及其父容器同样必须具有 weight 让位约束。
 */
class PasskeyBadgeLayoutWiringTest {

    private val securityBadgeSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/components/SecurityBadge.kt")

    private val rowLayoutsSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultEntryRowLayouts.kt")

    private val detailComponentsSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/ui/screens/detail/EntryDetailComponents.kt")

    @Test
    fun `PasskeyBadge 内部文本必须显式声明单行且禁止软换行`() {
        val passkeyBadgeBlock = securityBadgeSource
            .substringAfter("fun PasskeyBadge(")
            .substringBefore("@Composable\nfun PasswordStrengthBar")

        assertTrue(
            "PasskeyBadge 文本必须显式指定 maxLines = 1",
            passkeyBadgeBlock.contains("maxLines = 1")
        )
        assertTrue(
            "PasskeyBadge 文本必须显式指定 softWrap = false，防止窄宽度下折行为单字竖排",
            passkeyBadgeBlock.contains("softWrap = false")
        )
    }

    @Test
    fun `列表行标题 Text 必须声明 weight 约束以向 PasskeyBadge 自适应让位`() {
        val standardEntryBlock = rowLayoutsSource
            .substringAfter("internal fun StandardEntryLayout(")
            .substringBefore("if (groupPath != null)")

        assertTrue(
            "StandardEntryLayout 中标题 Text 必须带 Modifier.weight(1f, fill = false)，防止挤压 PasskeyBadge",
            standardEntryBlock.contains("modifier = Modifier.weight(1f, fill = false)")
        )
        assertTrue(
            "StandardEntryLayout 必须包含 PasskeyBadge 呈现逻辑",
            standardEntryBlock.contains("PasskeyBadge()")
        )
    }

    @Test
    fun `详情页头部标题 Text 所在容器与标题自身必须声明 weight 约束`() {
        val detailHeaderBlock = detailComponentsSource
            .substringAfter("internal fun EntryHeaderSection(")
            .substringBefore("if (groupPath != null)")

        assertTrue(
            "EntryHeaderSection 中的 Column 容器必须声明 weight(1f) 占满可用宽度",
            detailHeaderBlock.contains("Column(modifier = Modifier.weight(1f))")
        )
        assertTrue(
            "EntryHeaderSection 中标题 Text 必须带 Modifier.weight(1f, fill = false)，防止挤出 PasskeyBadge",
            detailHeaderBlock.contains("modifier = Modifier.weight(1f, fill = false)")
        )
        assertTrue(
            "EntryHeaderSection 必须包含 PasskeyBadge 呈现逻辑",
            detailHeaderBlock.contains("PasskeyBadge()")
        )
    }

    private fun readSource(relativePath: String): String {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: File(".")
        val file = File(root, relativePath)
        assertTrue("源文件必须存在: ${file.path}", file.exists())
        return file.readText()
    }
}
