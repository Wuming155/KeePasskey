package com.keepasskey.sync.network

import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.s3.S3KeyCodec
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * ISSUE-P1-05（ZT-05）SSRF 与主机注入防线单元测试；
 * ISSUE-P2-425：构造期口径放宽为「加密即可、明文全拒」后的回归锁定。
 *
 * 判别面：
 * 1. 桶名注入（`x@evil.com` / `x#` / `x?` / IP 格式）恒被拒——主机注入防线不变；
 * 2. 端点仅拒三类：明文 scheme、userinfo 注入、云元数据字面量（`169.254.0.0/16` 红线）；
 *    自建 / 内网 HTTPS 端点放行（构造期放宽）；
 * 3. 连接期语义不变：[SsrfGuardDns] / `isBlockedAddress` 对内网/保留网段的判定原样保留，
 *    豁免主机解析到云元数据仍拒（红线不随豁免放行）；
 * 4. ENG-20261028-41：云元数据判据按**承载的 IPv4** 判定——4 字节、`::ffff:a.b.c.d`
 *    （16 字节 IPv4-mapped）与 `::a.b.c.d`（废弃的 IPv4-compatible）都必须命中，
 *    且构造期 / 豁免 DNS / 连接期复核三处都先判红线、后判白名单；
 * 5. ENG-20261028-22：S3 virtual-host 的豁免对象是实际请求主机 `bucket.端点主机`
 *    （`S3KeyCodec.requestHost` 推导），未授权的其他私网主机仍走普通阻断分支。
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

    // ---------- 端点主机（SSRF）校验（ISSUE-P2-425 放宽后口径：加密即可、明文全拒） ----------

    @Test
    fun `云元数据字面 IP 端点仍被拒（红线）`() {
        listOf(
            "https://169.254.169.254/latest/meta-data/",
            // IPv4-mapped 形态的绕过尝试同样命中红线（getByName 归一为 4 字节）
            "https://[::ffff:169.254.169.254]/"
        ).forEach { endpoint ->
            val ex = runCatching { SyncEndpointGuard.validateEndpointHost(endpoint) }.exceptionOrNull()
            assertTrue("云元数据端点 \"$endpoint\" 必须被拒（AC④ 红线）", ex is SyncException.InvalidEndpointError)
        }
    }

    @Test
    fun `云元数据端点即便同时命中显式白名单也拒绝（红线先于豁免）`() {
        // ENG-20261028-41：白名单只在连接期豁免「内网可用性」，不能把元数据端点带进构造期。
        // OkHttp 会把 `[::ffff:a.b.c.d]` 归一为 IPv4 文本，故白名单按解析后的实际主机书写，
        // 确保本例断言的确实是「白名单命中 + 红线优先」这一组合
        listOf("169.254.169.254", "::ffff:169.254.169.254").forEach { host ->
            val url = if (host.contains(':')) "https://[$host]/" else "https://$host/"
            val literal = requireNotNull(SyncEndpointGuard.parseIpLiteral(host))
            val ex = runCatching {
                SyncEndpointGuard.validateEndpointHost(url, allowedHosts = setOf(literal.hostAddress))
            }.exceptionOrNull()
            assertTrue("白名单不得放行元数据端点 \"$host\"，实际=$ex", ex is SyncException.InvalidEndpointError)
            assertTrue(
                "拒绝理由必须是云元数据红线而非其它构造期错误：\"$host\" 实际=$ex",
                ex?.message.orEmpty().contains("云元数据")
            )
            assertTrue(
                "命中白名单的元数据端点须在错误里说明红线不随豁免放行：\"$host\" 实际=$ex",
                ex?.message.orEmpty().contains("白名单")
            )
        }
    }

    @Test
    fun `IPv4-compatible 拼写的元数据字面量在构造期与判据中都命中红线`() {
        // 已废弃拼写（::a.b.c.d）：OkHttp 规范化为 `::a9fe:a9fe`（16 字节），只按
        // IPv4 字面比较会漏判，故内嵌 IPv4 判定必须覆盖该形态
        val literal = requireNotNull(SyncEndpointGuard.parseIpLiteral("::169.254.169.254"))
        assertTrue("IPv4-compatible 元数据字面量必须命中红线", SyncEndpointGuard.isCloudMetadataAddress(literal))

        val ex = runCatching { SyncEndpointGuard.validateEndpointHost("https://[::169.254.169.254]/") }.exceptionOrNull()
        assertTrue("compatible 拼写的元数据端点必须被拒，实际=$ex", ex is SyncException.InvalidEndpointError)
        assertTrue("拒绝理由必须是云元数据红线，实际=$ex", ex?.message.orEmpty().contains("云元数据"))
    }

    @Test
    fun `自建与内网 https 端点构造期放行（ISSUE-P2-425 放宽）`() {
        listOf(
            "https://192.168.1.1/dav",
            "https://10.0.0.5/",
            "https://172.16.0.9/",
            "https://127.0.0.1/",
            "https://100.64.0.1/",
            "https://[fe80::1]/dav",
            "https://[fc00::1]/dav",
            "https://localhost/dav",
            "https://dav.localhost/",
            "https://nas.local/",
            "https://metadata.google.internal/"
        ).forEach { endpoint ->
            val ex = runCatching { SyncEndpointGuard.validateEndpointHost(endpoint) }.exceptionOrNull()
            assertTrue("自建/内网 https 端点 \"$endpoint\" 不应被拒: $ex", ex == null)
        }
    }

    @Test
    fun `明文 http 端点被拒（明文全拒红线）`() {
        val ex = runCatching { SyncEndpointGuard.validateEndpointHost("http://dav.example.com/") }.exceptionOrNull()
        assertTrue("明文 http 端点必须被拒", ex is SyncException.InvalidEndpointError)
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

    // ---------- 云元数据判据（4 / 16 字节与映射拼写） ----------

    @Test
    fun `云元数据判据覆盖 4 字节与映射拼写且不误伤合法地址`() {
        // 4 字节：平台归一后的常规形态
        assertTrue(SyncEndpointGuard.isCloudMetadataAddress(InetAddress.getByName("169.254.169.254")))

        // 16 字节 IPv4-mapped（前 10 字节零 + 0xFF,0xFF 标记 + 偏移 12–13 的内嵌 IPv4）：
        // 用 Inet6Address.getByAddress 保留 16 字节形态，否则 getByAddress 会归一为 4 字节而测不到该分支
        val mappedMetadata = Inet6Address.getByAddress(null, mappedIpv6(169, 254, 169, 254), -1)
        assertEquals("映射形态必须保留 16 字节，否则该用例测不到 16 字节分支", 16, mappedMetadata.address.size)
        assertTrue("IPv4-mapped 元数据地址必须命中红线", SyncEndpointGuard.isCloudMetadataAddress(mappedMetadata))

        // 16 字节 IPv4-compatible（已废弃拼写）：内嵌 IPv4 相同，同样不得成为绕过口
        val compatMetadata = Inet6Address.getByAddress(null, ipv4CompatibleIpv6(169, 254, 169, 254), -1)
        assertEquals(16, compatMetadata.address.size)
        assertTrue("IPv4-compatible 元数据地址必须命中红线", SyncEndpointGuard.isCloudMetadataAddress(compatMetadata))

        // 合法私网 / 公网 / 普通 IPv6 对照：判据不得扩大
        listOf("192.168.1.50", "10.0.0.5", "93.184.216.34").forEach { ip ->
            assertFalse("$ip 不是云元数据地址", SyncEndpointGuard.isCloudMetadataAddress(InetAddress.getByName(ip)))
        }
        assertFalse(
            "映射的私网地址不是元数据地址（由网段复核处理）",
            SyncEndpointGuard.isCloudMetadataAddress(
                Inet6Address.getByAddress(null, mappedIpv6(192, 168, 1, 50), -1)
            )
        )
        assertFalse(
            "普通 IPv6 不是元数据地址",
            SyncEndpointGuard.isCloudMetadataAddress(InetAddress.getByName("2001:db8::1"))
        )
    }

    @Test
    fun `isBlockedAddress 对 16 字节映射形态递归按内嵌 IPv4 判定`() {
        // 既有 4 字节归一形态（getByName 已转换）之外的显式 16 字节形态：不得因形态差异漏判
        listOf(
            Inet6Address.getByAddress(null, mappedIpv6(192, 168, 1, 1), -1),
            Inet6Address.getByAddress(null, mappedIpv6(169, 254, 169, 254), -1),
            Inet6Address.getByAddress(null, ipv4CompatibleIpv6(10, 0, 0, 5), -1)
        ).forEach { addr ->
            assertTrue("16 字节形态 ${addr.hostAddress} 必须按内嵌 IPv4 判为 blocked", SyncEndpointGuard.isBlockedAddress(addr))
        }
        assertFalse(
            "映射的公网地址不得被误判为内网",
            SyncEndpointGuard.isBlockedAddress(Inet6Address.getByAddress(null, mappedIpv6(1, 1, 1, 1), -1))
        )
    }

    // ---------- SsrfGuardDns 连接期防线（DNS 重绑定） ----------

    /** 返回固定地址列表的假 DNS 委托（不触发真实网络） */
    private class FakeDns(private val result: List<InetAddress>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = result
    }

    /** 构造 16 字节 IPv4-mapped 地址字串（`::ffff:a.b.c.d`） */
    private fun mappedIpv6(a: Int, b: Int, c: Int, d: Int): ByteArray = ByteArray(16).apply {
        this[10] = 0xFF.toByte()
        this[11] = 0xFF.toByte()
        this[12] = a.toByte()
        this[13] = b.toByte()
        this[14] = c.toByte()
        this[15] = d.toByte()
    }

    /** 构造 16 字节 IPv4-compatible 地址字串（`::a.b.c.d`，已废弃） */
    private fun ipv4CompatibleIpv6(a: Int, b: Int, c: Int, d: Int): ByteArray = ByteArray(16).apply {
        this[12] = a.toByte()
        this[13] = b.toByte()
        this[14] = c.toByte()
        this[15] = d.toByte()
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

    @Test
    fun `SsrfGuardDns 豁免主机解析到云元数据仍被拒（红线不随豁免）`() {
        val dns = SsrfGuardDns(
            FakeDns(listOf(InetAddress.getByName("169.254.169.254"))),
            allowedHosts = setOf("metadata.google.internal")
        )
        val ex = runCatching { dns.lookup("metadata.google.internal") }.exceptionOrNull()
        assertTrue("豁免主机解析到 169.254.0.0/16 必须仍被拒（ISSUE-P2-425 AC④）", ex is UnknownHostException)
    }

    @Test
    fun `SsrfGuardDns 豁免主机解析到映射形态元数据仍被拒`() {
        // ENG-20261028-41：豁免分支的元数据判定先于地址批准，且映射形态按内嵌 IPv4 判
        val mapped = Inet6Address.getByAddress(null, mappedIpv6(169, 254, 169, 254), -1)
        val approvals = SsrfAddressApprovals()
        val dns = SsrfGuardDns(FakeDns(listOf(mapped)), allowedHosts = setOf("minio.nas.local"), approvals = approvals)

        val ex = runCatching { dns.lookup("minio.nas.local") }.exceptionOrNull()
        assertTrue("豁免主机解析到 IPv4-mapped 元数据必须整体拒绝，实际=$ex", ex is UnknownHostException)
        assertFalse(
            "被拒的元数据地址不得进入连接期豁免表",
            approvals.isApproved(InetAddress.getByName("169.254.169.254"))
        )
    }

    @Test
    fun `SsrfGuardDns 非豁免主机解析到 compatible 形态元数据仍被拒`() {
        val compat = Inet6Address.getByAddress(null, ipv4CompatibleIpv6(169, 254, 169, 254), -1)
        val dns = SsrfGuardDns(FakeDns(listOf(compat)))
        val ex = runCatching { dns.lookup("evil.example.com") }.exceptionOrNull()
        assertTrue("非豁免主机解析到元数据（含废弃拼写）必须拒绝，实际=$ex", ex is UnknownHostException)
    }

    // ---------- S3 virtual-host 实际请求主机的精确授权（ENG-20261028-22） ----------

    @Test
    fun `私网虚拟主机端点的桶主机获豁免而未授权私网主机仍被拒`() {
        // Provider 用 S3KeyCodec.requestHost 推导实际请求主机（virtual-host 为 bucket.端点主机），
        // 并把它作为唯一的已配置端点主机传给工厂；此处按同一组合验证两层守卫的判定
        val requestHost = requireNotNull(
            S3KeyCodec.requestHost("https://minio.nas.local:9000", "vault", usePathStyle = false)
        )
        val privateAddress = InetAddress.getByName("192.168.1.50")
        val approvals = SsrfAddressApprovals()
        val dns = SsrfGuardDns(
            FakeDns(listOf(privateAddress)),
            allowedHosts = setOf(requestHost),
            approvals = approvals
        )

        assertEquals("私网虚拟主机端点必须放行（否则自建内网 S3 不可用）", 1, dns.lookup(requestHost).size)
        assertTrue(
            "获批的解析地址须在连接期同样放行（纵深防御不得误伤自建端点）",
            approvals.isApproved(privateAddress)
        )
        assertTrue(
            "未授权的其他私网主机仍走普通阻断分支",
            runCatching { dns.lookup("other.nas.local") }.exceptionOrNull() is UnknownHostException
        )
    }

    @Test
    fun `SsrfGuardDns 豁免主机的解析地址获批登记进连接期豁免表`() {
        val approvals = SsrfAddressApprovals()
        val resolved = listOf(InetAddress.getByName("192.168.1.50"))
        val dns = SsrfGuardDns(FakeDns(resolved), allowedHosts = setOf("nas.home"), approvals = approvals)
        dns.lookup("nas.home")
        assertTrue(
            "豁免主机的解析地址必须登记（否则连接期复核会拦下自建同步）",
            approvals.isApproved(InetAddress.getByName("192.168.1.50"))
        )
    }
}
