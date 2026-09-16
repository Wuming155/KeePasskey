package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.cipher.CipherFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-85 回归：建库向导的「加密预设」必须**实际决定产物算法**。
 *
 * ## 缺陷背景（本用例锁定的对象）
 *
 * 预设原为 UI 侧裸字符串列表，落盘路径只以 `preset.contains("AES-KDF")` 反推 KDF，
 * **外层算法被整段丢弃**：`SessionOpener.create` 恒以 `AES_256_CBC` 建库。后果是
 * ① 默认选中的「ChaCha20 + Argon2id」产出 **AES-256-CBC** 库；
 * ② 「Twofish + AES-KDF」同样产出 **AES-256-CBC** 库。向导展示与产物长期不一致且无提示。
 *
 * ## 本用例分两层
 *
 * 1. **纯策略层**：预设表自身的完备性——每个预设都声明了「CipherFactory 支持的外层算法」
 *    与 KDF，标签唯一，未登记标签一律 fail-closed；
 * 2. **接线层（静态源码比对）**：落盘调用必须**逐字**把 `preset.cipherUuid` / `preset.useArgon2`
 *    传给会话层，且不得残留旧的 `preset.contains(` 字符串启发式——本项防护的失效形态
 *    **不是判定错，而是接线被静默改回硬编码**，JVM 无法构造完整 Hilt 图来观测，
 *    故沿用本仓既有静态接线守卫先例（[com.keepasskey.app.security.ObscuredTouchWiringTest]）。
 *
 * 端到端行为（写入真实 `.kdbx` 后读回文件头算法）由 database 模块的
 * `VaultCreationCipherTest` 覆盖。
 */
class CreateVaultPresetTest {

    @Test
    fun `每个预设都声明受支持的外层算法与 KDF`() {
        assertTrue("预设数量变化必须重新评估本用例", CreateVaultPreset.entries.size == 3)
        CreateVaultPreset.entries.forEach { preset ->
            assertTrue(
                "预设 ${preset.name} 的外层算法 ${preset.cipherUuid} 未被 CipherFactory 支持——" +
                    "落盘时会在 KdbxFile 抛「不支持的密码算法 UUID」",
                CipherFactory.isSupported(preset.cipherUuid)
            )
            assertTrue("预设 ${preset.name} 的标签不得为空", preset.label.isNotBlank())
            assertTrue("预设 ${preset.name} 的芯片短标签不得为空", preset.chipLabel.isNotBlank())
        }
    }

    @Test
    fun `三个预设的算法互不相同且覆盖 AES ChaCha20 Twofish`() {
        val ciphers = CreateVaultPreset.entries.map { it.cipherUuid }.toSet()
        assertEquals("预设必须覆盖三种互不相同的算法", 3, ciphers.size)
        assertEquals(
            setOf(
                KdbxConstants.Cipher.AES_256_CBC,
                KdbxConstants.Cipher.CHACHA20,
                KdbxConstants.Cipher.TWOFISH
            ),
            ciphers
        )
    }

    @Test
    fun `KDF 选择与标签一致且 AES-KDF 档唯一`() {
        val aesKdf = CreateVaultPreset.entries.filter { !it.useArgon2 }
        assertEquals("仅 Twofish 档使用 AES-KDF", listOf(CreateVaultPreset.TWOFISH_AES_KDF), aesKdf)
        assertTrue(aesKdf.single().label.contains("AES-KDF"))
        CreateVaultPreset.entries.filter { it.useArgon2 }.forEach { preset ->
            assertTrue("Argon2 档标签应含 Argon2：${preset.label}", preset.label.contains("Argon2"))
        }
    }

    @Test
    fun `默认预设与其标签保持既有 UI 取值`() {
        // 默认档此前就是「ChaCha20 + Argon2id」；本修复只让**算法真正生效**，不改变默认选择
        assertEquals(CreateVaultPreset.CHACHA20_ARGON2ID, CreateVaultPreset.DEFAULT)
        assertEquals("ChaCha20 + Argon2id", CreateVaultPreset.DEFAULT.label)
        assertEquals(KdbxConstants.Cipher.CHACHA20, CreateVaultPreset.DEFAULT.cipherUuid)
    }

    @Test
    fun `标签唯一且 fromLabel 可往返`() {
        val labels = CreateVaultPreset.entries.map { it.label }
        assertEquals("标签必须唯一，否则 fromLabel 会静默取首个匹配", labels.size, labels.toSet().size)
        labels.forEach { label ->
            assertEquals(
                "fromLabel 必须能还原标签 $label",
                label,
                CreateVaultPreset.fromLabel(label)?.label
            )
        }
    }

    @Test
    fun `未登记标签一律 fail-closed 且不得回落默认算法`() {
        assertNull(CreateVaultPreset.fromLabel(""))
        assertNull(CreateVaultPreset.fromLabel("ChaCha20"))        // 芯片短标签不是合法标识
        assertNull(CreateVaultPreset.fromLabel("ChaCha20 + Argon2")) // 少一个 id
        assertNull(CreateVaultPreset.fromLabel("AES-256 + AES-KDF"))
        assertNotNull("已登记标签不得为 null", CreateVaultPreset.fromLabel(CreateVaultPreset.DEFAULT.label))
    }

    @Test
    fun `建库落盘路径逐字传递预设的算法与 KDF`() {
        val source = readSource(COORDINATOR)
        assertTrue(
            "建库必须把 preset.cipherUuid 传给会话层（否则外层算法被硬编码丢弃，即 ISSUE-P2-85）",
            source.contains("cipherUuid = preset.cipherUuid")
        )
        assertTrue(
            "建库必须把 preset.useArgon2 传给会话层",
            source.contains("useArgon2 = preset.useArgon2")
        )
        assertFalse(
            "不得残留旧的字符串启发式（preset.contains(\"AES-KDF\")）——它只推 KDF、丢算法",
            source.contains("preset.contains(")
        )
    }

    @Test
    fun `会话层不得再硬编码外层算法`() {
        val opener = readSource(OPENER)
        assertTrue(
            "SessionOpener.create 必须接受并按实参建库",
            opener.contains("cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC") &&
                opener.contains("cipherUuid = cipherUuid,")
        )
        // 反向锁：createDefault 调用点不得把算法写成常量字面量
        assertFalse(
            "SessionOpener.create 内不得再以 KdbxConstants.Cipher.AES_256_CBC 常量调用 createDefault",
            opener.contains("KdbxHeader.createDefault(\n                    cipherUuid = KdbxConstants.Cipher.AES_256_CBC,")
        )
    }

    @Test
    fun `向导以枚举为单一真相源而非裸字符串`() {
        val dialog = readSource(WIZARD)
        assertTrue(
            "向导默认值必须取自枚举",
            dialog.contains("mutableStateOf(CreateVaultPreset.DEFAULT)")
        )
        assertTrue("向导芯片必须取枚举的表项", dialog.contains("CreateVaultPreset.entries"))
        assertTrue("芯片文案必须取枚举字段", dialog.contains("preset.chipLabel"))
        assertFalse(
            "向导内不得再出现裸预设字符串（标签只允许定义在 CreateVaultPreset）",
            dialog.contains("\"ChaCha20 + Argon2id\"") || dialog.contains("\"Twofish + AES-KDF\"")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val COORDINATOR =
            "app/src/main/java/com/keepasskey/app/data/repository/VaultLifecycleCoordinator.kt"
        const val OPENER = "database/src/main/java/com/keepasskey/database/session/SessionOpener.kt"
        const val WIZARD =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/CreateVaultWizardDialog.kt"

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
