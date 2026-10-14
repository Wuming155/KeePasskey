package com.keepasskey.app.sync

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.net.URL
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-596/597：真实 HTTPS WebDAV 上验证「内容比较/三方合并 → KDBX 序列化 → 远端保存 → 下载重开」。
 *
 * 本类只由 `tools/local-sync/run_lab.py` 在显式 liveSyncTest 模式调用；普通 JVM 全量测试会在
 * Gradle 层排除此类，不产生 Assume/skipped 绿灯。远端最终文件会保存到 `liveArtifactDir`，供
 * `pykeepass` 独立实现回读验收到期语义和合并字段。
 */
class LiveSyncKdbxSemanticsTest {

    private val url = System.getProperty("liveWebdavUrl", "https://localhost:9443")
    private val user = System.getenv("WEBDAV_USER") ?: error("live WebDAV user is not configured")
    private val remotePassword = System.getenv("WEBDAV_PASSWORD") ?: error("live WebDAV password is not configured")
    private val certFile = File(System.getProperty("liveCertPath", "tools/local-sync/certs/cert.pem"))
    private val artifactDir = File(System.getProperty("liveArtifactDir", "app/build/live-sync-artifacts"))

    @Test
    fun `到期变化判据触发真实KDBX上传且远端下载重开保留新值`() {
        assertTrue("此用例只能由 liveSyncTest 显式调用", System.getProperty("liveSyncTest") == "true")
        val remoteName = "issue-p2-596-${UUID.randomUUID()}.kdbx"
        val password = newKdbxPassword()
        val baseEntry = entry("base", KdbxTimes(expires = false, expiryTime = FUTURE_A))
        val changedEntry = baseEntry.copy(times = baseEntry.times.copy(expires = true, expiryTime = FUTURE_B))
        val base = database(rootWith(baseEntry))
        val changed = base.copy(rootGroup = rootWith(changedEntry))

        try {
            assertTrue("到期语义改变必须被内容比较器识别", KdbxContentComparator.changed(changed, base))
            val bytes = save(changed, password)
            put(remoteName, bytes)
            val downloaded = get(remoteName)
            assertArrayEquals("WebDAV 远端必须返回实际序列化产物", bytes, downloaded)
            writeArtifact("issue-p2-596-expiry", downloaded)

            val reopened = load(downloaded, password)
            try {
                val reopenedEntry = reopened.rootGroup.allEntries().single()
                assertTrue("远端正式产物必须包含启用的到期语义", reopenedEntry.times.expires)
                assertEquals(FUTURE_B, reopenedEntry.times.expiryTime)
            } finally {
                reopened.clearSensitiveData()
                downloaded.fill(0)
                bytes.fill(0)
            }
        } finally {
            delete(remoteName)
            password.fill('\u0000')
            base.clearSensitiveData()
            changed.clearSensitiveData()
        }
    }

    @Test
    fun `三方合并保留本地标题与远端到期值并经真实WebDAV官方回读`() {
        assertTrue("此用例只能由 liveSyncTest 显式调用", System.getProperty("liveSyncTest") == "true")
        val remoteName = "issue-p2-597-${UUID.randomUUID()}.kdbx"
        val password = newKdbxPassword()
        val baseEntry = entry("base", KdbxTimes(expires = false, expiryTime = FUTURE_A))
        val localEntry = baseEntry.copy(fields = baseEntry.fields + (KdbxConstants.Fields.TITLE to ProtectedString("local-title")))
        val remoteEntry = baseEntry.copy(times = baseEntry.times.copy(expires = true, expiryTime = FUTURE_B))
        val baseRoot = rootWith(baseEntry)
        val localRoot = baseRoot.copy(entries = listOf(localEntry))
        val remoteRoot = baseRoot.copy(entries = listOf(remoteEntry))
        val merged = KdbxMerger.mergeDatabases(
            KdbxDatabaseLite(baseRoot),
            KdbxDatabaseLite(localRoot),
            KdbxDatabaseLite(remoteRoot)
        )
        val mergedEntry = merged.mergedRoot.allEntries().single()
        assertEquals("本地独立修改必须保留", "local-title", mergedEntry.title)
        assertTrue("远端独立修改的到期状态必须保留", mergedEntry.times.expires)
        assertEquals(FUTURE_B, mergedEntry.times.expiryTime)
        assertTrue("独立字段修改不应制造同字段冲突", merged.conflicts.isEmpty())

        val localDatabase = database(localRoot)
        val mergedDatabase = localDatabase.copy(
            rootGroup = merged.mergedRoot,
            deletedObjects = merged.mergedDeletedObjects,
            customIcons = merged.mergedCustomIcons
        )
        try {
            val bytes = save(mergedDatabase, password)
            put(remoteName, bytes)
            val downloaded = get(remoteName)
            assertArrayEquals("下载字节须与上传的合并 KDBX 完全相同", bytes, downloaded)
            writeArtifact("issue-p2-597-merged", downloaded)

            val reopened = load(downloaded, password)
            try {
                val reopenedEntry = reopened.rootGroup.allEntries().single()
                assertEquals("远端产物须保留本地标题", "local-title", reopenedEntry.title)
                assertTrue("远端产物须保留远端到期位", reopenedEntry.times.expires)
                assertEquals("远端产物须保留远端到期时间", FUTURE_B, reopenedEntry.times.expiryTime)
            } finally {
                reopened.clearSensitiveData()
                downloaded.fill(0)
                bytes.fill(0)
            }
        } finally {
            delete(remoteName)
            password.fill('\u0000')
            localDatabase.clearSensitiveData()
            mergedDatabase.clearSensitiveData()
        }
    }

    private fun trustedSslContext(): SSLContext {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certFile.inputStream()) as X509Certificate
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("local-sync", certificate)
        }
        val trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val trustManager = trustFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
    }

    /** 真实 HTTPS WebDAV PUT/GET；Provider 协议自身由 sync LiveSyncServersTest 覆盖。 */
    private fun put(path: String, bytes: ByteArray) {
        val connection = open(path, "PUT")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { it.write(bytes) }
            assertTrue("真实 WebDAV PUT 必须成功（HTTP ${connection.responseCode}）", connection.responseCode in 200..299)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(path: String): ByteArray {
        val connection = open(path, "GET")
        try {
            assertEquals("真实 WebDAV GET 必须成功", 200, connection.responseCode)
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun delete(path: String) {
        val connection = open(path, "DELETE")
        try {
            assertTrue("真实 WebDAV DELETE 必须成功", connection.responseCode in 200..299 || connection.responseCode == 404)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpsURLConnection =
        (URL("${url.trimEnd('/')}/$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = trustedSslContext().socketFactory
            hostnameVerifier = HostnameVerifier { hostname, _ -> hostname == "localhost" || hostname == "127.0.0.1" }
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = method
            setRequestProperty(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("$user:$remotePassword".toByteArray(Charsets.UTF_8))
            )
        }

    private fun entry(title: String, times: KdbxTimes) = KdbxEntry(
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title),
            KdbxConstants.Fields.USER_NAME to ProtectedString("synthetic-user")
        ),
        times = times
    )

    private fun rootWith(entry: KdbxEntry) = KdbxGroup(name = "Root", entries = listOf(entry))

    private fun database(root: KdbxGroup): KdbxDatabase {
        val header = KdbxHeader.createDefault(useArgon2 = false)
        val aes = header.kdfParameters as KdfParameters.Aes
        return KdbxDatabase(
            header = header.copy(kdfParameters = aes.copy(rounds = 1)),
            databaseName = "Live KDBX semantic probe",
            rootGroup = root
        )
    }

    private fun save(database: KdbxDatabase, password: CharArray): ByteArray {
        val passwordCopy = password.clone()
        return try {
            ByteArrayOutputStream().also { KdbxFile.save(it, database, passwordCopy) }.toByteArray()
        } finally {
            passwordCopy.fill('\u0000')
        }
    }

    private fun load(bytes: ByteArray, password: CharArray): KdbxDatabase {
        val passwordCopy = password.clone()
        return try {
            KdbxFile.load(ByteArrayInputStream(bytes), passwordCopy)
        } finally {
            passwordCopy.fill('\u0000')
        }
    }

    private fun writeArtifact(name: String, bytes: ByteArray) {
        artifactDir.mkdirs()
        File(artifactDir, "$name.kdbx").writeBytes(bytes)
    }

    private fun newKdbxPassword() = "Synthetic-Live-Kdbx-2026".toCharArray()

    private companion object {
        val FUTURE_A: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val FUTURE_B: Instant = Instant.parse("2031-02-03T04:05:06Z")
    }
}
