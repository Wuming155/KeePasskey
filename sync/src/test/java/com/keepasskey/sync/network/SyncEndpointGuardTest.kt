package com.keepasskey.sync.network

import com.keepasskey.sync.model.SyncException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * ISSUE-P1-05（ZT-05）SSRF 与主机注入防线单元测试。
 *
 * 三类注入必须被拒（对应验收标准）：
 * 1. 桶名 `x@evil.com`（authority userinfo 改写真实主机）；
 * 2. 桶名 `x#`（fragment 截断 authority）；
 * 3. 端点内网 IP（`169.254.169.254` 云元数据 / RFC1918 / 环回）SSRF 直连。
 */
class SyncEndpointGuardTest {

    // ---------- 桶名（主机注入）校验 ----------

    @Test
    fun `桶名注入 x at evil dot com 被拒`() {
        val ex = runCatching { SyncEndpointGuard.validateBucketName("x@evil.com/") }.exceptionOrNull()
        assertTrue("含 @ 与 / 的桶名必须被拒", ex is SyncException.InvalidEndpointError)
    }

    @Test
    fun `桶名注入 x 井号 被拒`() {
        val ex = runCatching { SyncEndpointGuard.validateBucketName("x#") }.exceptionOrNull()
        assertTrue("含 # 的桶名必须被拒", ex is SyncException.InvalidEndpointError)
    }

    @Test
    fun `桶名注入 x 问号 被拒`() {
        val ex = runCatching { SyncEndpointGuard.validateBucketName("x?") }.exceptionOrNull()
        assertTrue("含 ? 的桶名必须被拒", ex is SyncException.InvalidEndpointError)
    }

    @Test
    fun `桶名被格式化为内网 IP 被拒`() {
        val ex = runCatching { SyncEndpointGuard.validateBucketName("192.168.5.4") }.exceptionOrNull()
        assertTrue("IP 地址格式桶名必须被拒（AWS 规范 + 主机混淆）", ex is SyncException.InvalidEndpointError)
    }

    @Test
    fun `非法桶名（大写 过短 相邻点 保留前后缀）被拒`() {
        assertTrue(runCatching { SyncEndpointGuard.validateBucketName("MyBucket") }.exceptionOrNull() is SyncException.InvalidEndpointError)
        assertTrue(runCatching { SyncEndpointGuard.validateBucketName("ab") }.exceptionOrNull() is SyncException.InvalidEndpointError)
        assertTrue(runCatching { SyncEndpointGuard.validateBucketName("my..bucket") }.exceptionOrNull() is SyncException.InvalidEndpointError)
        assertTrue(runCatching { SyncEndpointGuard.validateBucketName("xn--bucket") }.exceptionOrNull() is SyncException.InvalidEndpointError)
        assertTrue(runCatching { SyncEndpointGuard.validateBucketName("sthree-bucket") }.exceptionOrNull() is SyncException.InvalidEndpointError)
        assertTrue(runCatching { SyncEndpointGuard.validateBucketName("-leading") }.exceptionOrNull() is SyncException.InvalidEndpointError)
    }

    @Test
    fun `合法桶名通过校验`() {
        // 现有测试与真实场景所用桶名必须全部放行
        listOf("my-secure-vault", "test-bucket", "my-vault", "examplebucket", "keepasskey-test", "abc")
            .forEach { bucket ->
                val ex = runCatching { SyncEndpointGuard.validateBucketName(bucket) }.exceptionOrNull()
                assertTrue("合法桶名 \"$bucket\" 不应被拒: $ex", ex == null)
            }
    }

    // ---------- 端点主机（SSRF）校验 ----------

    @Test
    fun `端点内网与云元数据字面 IP 被拒`() {
        listOf(
            "https://169.254.169.254/latest/meta-data/",
            "https://192.168.1.1/dav",
            "https://10.0.0.5/",
            "https://172.16.0.9/",
            "https://127.0.0.1/",
            "https://100.64.0.1/"
        ).forEach { endpoint ->
            val ex = runCatching { SyncEndpointGuard.validateEndpointHost(endpoint) }.exceptionOrNull()
            assertTrue("内网/保留网段端点 \"$endpoint\" 必须被拒", ex is SyncException.InvalidEndpointError)
        }
    }

    @Test
    fun `端点本地与内网保留主机名被拒`() {
        listOf(
            "https://localhost/dav",
            "https://dav.localhost/",
            "https://metadata.google.internal/",
            "https://nas.local/"
        ).forEach { endpoint ->
            val ex = runCatching { SyncEndpointGuard.validateEndpointHost(endpoint) }.exceptionOrNull()
            assertTrue("本地/内网保留名端点 \"$endpoint\" 必须被拒", ex is SyncException.InvalidEndpointError)
        }
    }

    @Test
    fun `端点 userinfo 注入被拒`() {
        // `https://good.com@evil.com/`：用户以为连 good.com，实际连 evil.com
        val ex = runCatching { SyncEndpointGuard.validateEndpointHost("https://good.com@evil.com/") }.exceptionOrNull()
        assertTrue("userinfo 注入端点必须被拒", ex is SyncException.InvalidEndpointError)
    }

    @Test
    fun `公网商业云端点通过校验`() {
        listOf(
            "https://s3.amazonaws.com",
            "https://dav.example.com/remote.php/webdav",
            "dav.example.com/remote.php/webdav",
            "https://account.cloudflare.com/client/v4/accounts/xxx/r2/webdav"
        ).forEach { endpoint ->
            val ex = runCatching { SyncEndpointGuard.validateEndpointHost(endpoint) }.exceptionOrNull()
            assertTrue("公网端点 \"$endpoint\" 不应被拒: $ex", ex == null)
        }
    }

    @Test
    fun `显式白名单豁免放行内网端点`() {
        val ex = runCatching {
            SyncEndpointGuard.validateEndpointHost("https://192.168.1.10/dav", allowedHosts = setOf("192.168.1.10"))
        }.exceptionOrNull()
        assertTrue("白名单主机应被显式豁免: $ex", ex == null)
    }

    // ---------- isBlockedAddress 网段判定 ----------

    @Test
    fun `isBlockedAddress 正确判定内网与公网地址`() {
        // 内网/保留：必须判定为 blocked
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.5.5", "192.168.0.1",
            "169.254.169.254", "0.0.0.0", "100.64.0.1", "240.0.0.1",
            "::1", "fc00::1", "fd12::1", "fe80::1", "::ffff:192.168.1.1"
        ).forEach { ip ->
            val addr = InetAddress.getByName(ip)
            assertTrue("$ip 应判定为内网/保留（blocked）", SyncEndpointGuard.isBlockedAddress(addr))
        }
        // 公网：不得误伤
        listOf("8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946")
            .forEach { ip ->
                val addr = InetAddress.getByName(ip)
                assertFalse("$ip 不应被判定为 blocked", SyncEndpointGuard.isBlockedAddress(addr))
            }
    }

    // ---------- SsrfGuardDns 连接期防线（DNS 重绑定） ----------

    /** 返回固定地址列表的假 DNS 委托（不触发真实网络） */
    private class FakeDns(private val result: List<InetAddress>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = result
    }

    @Test
    fun `SsrfGuardDns 拒绝解析到内网地址的主机`() {
        val dns = SsrfGuardDns(FakeDns(listOf(InetAddress.getByName("169.254.169.254"))))
        val ex = runCatching { dns.lookup("evil-rebind.example.com") }.exceptionOrNull()
        assertTrue("解析到内网 IP 必须整体拒绝", ex is UnknownHostException)
    }

    @Test
    fun `SsrfGuardDns 拒绝公网与内网混合应答的 DNS 重绑定`() {
        val mixed = listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1"))
        val dns = SsrfGuardDns(FakeDns(mixed))
        val ex = runCatching { dns.lookup("rebind.example.com") }.exceptionOrNull()
        assertTrue("混合应答（含任一内网 IP）必须整体拒绝，杜绝重绑定绕过", ex is UnknownHostException)
    }

    @Test
    fun `SsrfGuardDns 放行纯公网解析结果`() {
        val public = listOf(InetAddress.getByName("93.184.216.34"))
        val dns = SsrfGuardDns(FakeDns(public))
        assertEquals(public, dns.lookup("example.com"))
    }

    @Test
    fun `SsrfGuardDns 白名单主机豁免内网校验`() {
        val delegate = FakeDns(listOf(InetAddress.getByName("192.168.1.50")))
        val dns = SsrfGuardDns(delegate, allowedHosts = setOf("nas.home"))
        val resolved = dns.lookup("nas.home")
        assertEquals(1, resolved.size)
        assertEquals("192.168.1.50", resolved[0].hostAddress)
    }
}
