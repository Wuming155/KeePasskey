package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P3-575` AC③：外部打开准入策略的**纯 JVM**用例（不做任何真机 / Instrumentation）。
 *
 * 缺陷原形态（外部评审实证）：`OpenVaultEntryActivity` 对 URI **全程无 scheme 校验**，
 * 任意普通应用（无需任何权限、无需 `FLAG_GRANT`）显式 Intent 携带 `file://` 任意路径即可登记
 * 并覆写活动库指针；该 path 随后在解锁分支被 `File(path)` 当**相对路径**解析。
 * 判定此前混在 `Intent` / `Uri` / `ContentResolver` 里，宿主 JVM 无 `Uri.parse` 可测——
 * 抽出 [VaultOpenUriPolicy] 后，三类用例（攻击者 scheme / 相对路径 / 伪装后缀）逐条可断言。
 *
 * 三类用例与 [VaultOpenUriPolicy] 三条判据一一对应，互为**反例锁**：任何一条被放宽都会让对应用例变红。
 */
class VaultOpenUriPolicyTest {

    // ========== ① 攻击者 scheme：入站 URI 只接受 content:// ==========

    @Test
    fun `content 入站 URI 放行`() {
        assertAccept(VaultOpenUriPolicy.acceptInboundUri("content://com.example.provider/document/kdbx%3A123"))
    }

    @Test
    fun `file scheme 入站 URI 一律拒绝`() {
        // 攻击者最省力的形态：无 FLAG_GRANT 即可送达，且 File("file:/…") 会被当相对路径
        assertEquals(
            VaultOpenUriPolicy.Reason.UNSUPPORTED_SCHEME,
            rejectReason(VaultOpenUriPolicy.acceptInboundUri("file:///data/local/tmp/evil.kdbx"))
        )
        assertEquals(
            VaultOpenUriPolicy.Reason.UNSUPPORTED_SCHEME,
            rejectReason(VaultOpenUriPolicy.acceptInboundUri("file:/data/local/tmp/evil.kdbx"))
        )
    }

    @Test
    fun `http 与其它 scheme 入站 URI 一律拒绝`() {
        listOf(
            "http://evil.example/vault.kdbx",
            "https://evil.example/vault.kdbx",
            "intent://evil.example/vault.kdbx",
            "javascript:alert(1)",
            "content/" // 严格前缀：缺 // 不算 content
        ).forEach { uri ->
            assertEquals(
                "scheme=$uri 应被拒（非 content://）",
                VaultOpenUriPolicy.Reason.UNSUPPORTED_SCHEME,
                rejectReason(VaultOpenUriPolicy.acceptInboundUri(uri))
            )
        }
    }

    @Test
    fun `相对路径与裸文件名入站 URI 一律拒绝（空 scheme）`() {
        listOf(
            "evil.kdbx",
            "../evil.kdbx",
            "sub/evil.kdbx",
            "./evil.kdbx",
            "/data/local/tmp/evil.kdbx"
        ).forEach { uri ->
            assertEquals(
                "无 scheme 的路径入口应被拒：$uri",
                VaultOpenUriPolicy.Reason.UNSUPPORTED_SCHEME,
                rejectReason(VaultOpenUriPolicy.acceptInboundUri(uri))
            )
        }
    }

    @Test
    fun `空白与 null 入站 URI 拒绝为 EMPTY_URI`() {
        listOf(null, "", "   ").forEach { uri ->
            assertEquals(
                "空 URI 应判 EMPTY_URI",
                VaultOpenUriPolicy.Reason.EMPTY_URI,
                rejectReason(VaultOpenUriPolicy.acceptInboundUri(uri))
            )
        }
    }

    // ========== ② 伪装后缀：显示名必须是末段纯文件名的 .kdbx ==========

    @Test
    fun `纯文件名 kdbx 显示名放行且大小写不敏感`() {
        listOf("vault.kdbx", "Vault.KDBX", "my.vault.kdbx").forEach { name ->
            assertAccept(VaultOpenUriPolicy.acceptDisplayName(name))
        }
    }

    @Test
    fun `伪装为非 kdbx 后缀的显示名一律拒绝`() {
        listOf(
            "evil.kdbx.txt",
            "vault.txt",
            "notes.kdbx.backup",
            "vault.kdbx.tmp"
        ).forEach { name ->
            assertEquals(
                "非 .kdbx 结尾应判 NOT_KDBX_NAME：$name",
                VaultOpenUriPolicy.Reason.NOT_KDBX_NAME,
                rejectReason(VaultOpenUriPolicy.acceptDisplayName(name))
            )
        }
    }

    @Test
    fun `带目录分量的显示名一律拒绝（防 file 路径末段形态复活）`() {
        listOf(
            "sub/evil.kdbx",
            "..\\evil.kdbx",
            "/tmp/evil.kdbx",
            "a/b/../evil.kdbx"
        ).forEach { name ->
            assertEquals(
                "含目录分量应判 PATH_SHAPED_NAME：$name",
                VaultOpenUriPolicy.Reason.PATH_SHAPED_NAME,
                rejectReason(VaultOpenUriPolicy.acceptDisplayName(name))
            )
        }
    }

    @Test
    fun `空白与 null 显示名拒绝为 EMPTY_NAME`() {
        listOf(null, "", "   ").forEach { name ->
            assertEquals(
                "空显示名应判 EMPTY_NAME",
                VaultOpenUriPolicy.Reason.EMPTY_NAME,
                rejectReason(VaultOpenUriPolicy.acceptDisplayName(name))
            )
        }
    }

    // ========== ③ 已登记路径形态：只接受 content:// 或裸绝对路径 ==========

    @Test
    fun `content 与裸绝对路径判定为可解锁`() {
        assertTrue(
            "content:// 应可解锁",
            VaultOpenUriPolicy.isUnlockableStoredPath("content://com.example.provider/document/1")
        )
        // 裸绝对路径的平台形态因宿主而异（Windows `C:\…` / Linux `/…`），故用平台绝对夹具而非硬编码字面量
        // ——首版硬编码 "/data/…" 曾在 Windows 宿主误判为相对（§490.4 首轮全量红的根因）。
        val absolute = File(System.getProperty("java.io.tmpdir"), "vault.kdbx").absolutePath
        assertTrue("裸绝对路径应可解锁：$absolute", VaultOpenUriPolicy.isUnlockableStoredPath(absolute))
    }

    @Test
    fun `file 前缀与相对路径判定为不可解锁（堵 File 相对解析）`() {
        listOf(
            "file:/data/user/0/x.kdbx",
            "file:///data/user/0/x.kdbx",
            "http://evil.example/x.kdbx",
            "vault.kdbx",
            "../vault.kdbx",
            "",
            null
        ).forEach { path ->
            assertFalse(
                "非 content:// 非裸绝对路径应不可解锁：$path",
                VaultOpenUriPolicy.isUnlockableStoredPath(path)
            )
        }
    }

    @Test
    fun `isContentUri 只认 content 前缀`() {
        assertTrue(VaultOpenUriPolicy.isContentUri("content://a/b"))
        listOf("file:///a/b", "/a/b", "http://a/b", "", null).forEach { path ->
            assertFalse("非 content 前缀：$path", VaultOpenUriPolicy.isContentUri(path))
        }
    }

    // ========== 断言辅助 ==========

    private fun assertAccept(decision: VaultOpenUriPolicy.Decision) {
        assertTrue("期望放行但被拒绝：$decision", decision is VaultOpenUriPolicy.Decision.Accept)
    }

    private fun rejectReason(decision: VaultOpenUriPolicy.Decision): VaultOpenUriPolicy.Reason {
        val reject = decision as? VaultOpenUriPolicy.Decision.Reject
            ?: throw AssertionError("期望拒绝但被判为放行：$decision")
        return reject.reason
    }
}
