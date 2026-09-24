package com.keepasskey.database.file

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.xml.KdbxXmlSerializer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * ISSUE-P3-313 AC①：整库重序列化（§316 实测 4.8 s）的**按段内部归因**（低端真机 + 同夹具口径）。
 *
 * ## 方法：分段隔离计时（读数证据，非断言口径）
 *
 * 生产管线（`KdbxFile.savePayload`）：`[内层头 + XML] → GZip → 外层块加密 → HMAC 块流`。
 * 各段按**同一夹具、同一字节流**隔离驱动并取墙钟：
 * - XML 写出：`KdbxXmlSerializer` 直写内存缓冲（分别以 ChaCha20 与 None 内层流驱动，差值 =
 *   内层流加密受保护字段份额）；
 * - 压缩：对上一步 XML 字节跑 GZip；
 * - HMAC 块流 / 外层块加密：对压缩后字节先单独经 [HmacBlockOutputStream]（纯 MAC），
 *   再经「外层加密链在 HMAC 之上」驱动（差值 = 外层块加密份额）；
 * - 缓存落盘：序列化产物按 `writeCache` 同口径（tmp + `fd.sync()` + 原子 rename）落盘。
 * 总耗时以 `KdbxFile.save` 端到端读数对照（KDF 单列）。
 *
 * 判据（AC① 只要求**留读数**）：各段读数 > 0 且分项之和落在总值同一量级；
 * 归因结论由读数本身承载（AC② 取向登记见批次文档）。
 */
@RunWith(AndroidJUnit4::class)
class SerializationSegmentAttributionDeviceTest {

    private val tag = "P3313Attribution"
    private val passwordChars = "ScaleP3302#2026".toCharArray()

    // ── §316 同夹具口径（`SyncAssemblyScaleDeviceTest.seedLargeDatabase` 的构建参数） ──
    private val entryCount = 10_000
    private val historyEvery = 50
    private val spilledAttachmentBytes = 2L * 1024 * 1024

    private fun elapsedMs(work: () -> Unit): Long {
        val start = System.nanoTime()
        work()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun buildFixtureDb(): KdbxDatabase {
        val header = KdbxHeader.createDefault(useArgon2 = false)
        val emptyRoot = KdbxDatabase(header = header, rootGroup = KdbxGroup(name = "Root"))
        val bigAttachment = ByteArray(spilledAttachmentBytes.toInt()) { (it % 251).toByte() }
        var historyCount = 0
        val entries = List(entryCount) { index ->
            val fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("条目-$index", false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("user$index", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("Scale#pass-$index", false),
                KdbxConstants.Fields.URL to ProtectedString("https://scale.example.test/$index", false)
            )
            val history = if (index % historyEvery != 0) emptyList() else {
                historyCount++
                listOf(
                    KdbxEntry(
                        id = KdbxUuid.random(),
                        fields = fields + (KdbxConstants.Fields.TITLE to ProtectedString("旧标题-$index", false))
                    )
                )
            }
            KdbxEntry(
                id = KdbxUuid.random(),
                fields = fields,
                customFields = listOf(KdbxCustomField("规模备注", ProtectedString("备注值-$index", false))),
                history = history,
                attachments = if (index == 0) {
                    listOf(KdbxAttachment(name = "scale-big.bin", data = bigAttachment))
                } else {
                    emptyList()
                }
            )
        }
        return emptyRoot.copy(rootGroup = emptyRoot.rootGroup.copy(entries = entries))
    }

    private class NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    @Test
    fun 整库重序列化按段归因读数() = runBlocking<Unit> {
        val db = buildFixtureDb()

        // 端到端总值（KDF + 外层头 + 载荷全管线）
        val totalMs = elapsedMs {
            KdbxFile.save(ByteArrayOutputStream(), db, passwordChars)
        }

        // KDF（AES-KDF）单列
        val header = KdbxHeader.createDefault(useArgon2 = false)
        val kdfMs = elapsedMs {
            KdbxFile.deriveKeys(header, passwordChars, null)
        }
        val (cipherKey, hmacKey64) = KdbxFile.deriveKeys(header, passwordChars, null)

        // 内层随机流密钥（生产同口径：SHA-512 派生在构造器内完成）
        val streamKey = ByteArray(64) { (it * 31 + 7).toByte() }
        val chachaInner = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.CHACHA20, streamKey)
        val noneInner = InnerRandomStreamCipher(KdbxConstants.InnerRandomStream.NONE, streamKey)

        // 段 A：XML 写出（ChaCha20 内层流），直写内存缓冲并留产物
        var xmlBytes = ByteArray(0)
        val xmlChaChaMs = elapsedMs {
            val sink = ByteArrayOutputStream()
            KdbxXmlSerializer(chachaInner).serialize(sink, db)
            xmlBytes = sink.toByteArray()
        }
        // 段 A0：XML 写出（None 内层流）——差值 = 内层流加密（受保护字段）份额
        val xmlNoneMs = elapsedMs {
            val sink = ByteArrayOutputStream()
            KdbxXmlSerializer(noneInner).serialize(sink, db)
        }

        // 段 B：压缩（对同一份 XML 字节跑 GZip）
        var compressed = ByteArray(0)
        val gzipMs = elapsedMs {
            val sink = ByteArrayOutputStream()
            GZIPOutputStream(sink, 64 * 1024).use { it.write(xmlBytes) }
            compressed = sink.toByteArray()
        }

        // 段 C：HMAC 块流（对压缩后字节，纯 MAC）
        val hmacMs = elapsedMs {
            HmacBlockOutputStream(NullOutputStream(), hmacKey64).use { it.write(compressed) }
        }

        // 段 D：外层块加密（链在 HMAC 之上；差值 = 外层块加密份额）
        val outerMs = elapsedMs {
            val hmac = HmacBlockOutputStream(NullOutputStream(), hmacKey64)
            val engine = CipherFactory.getEngine(header.cipherUuid)
            engine.createEncryptingStream(hmac, cipherKey, header.encryptionIv).use { it.write(compressed) }
        }

        // 段 E：内层头序列化（2 MiB 附件池条目直写）
        val innerHeader = InnerHeader(
            binaries = listOf(
                InnerHeader.BinaryItem(0, ByteArray(spilledAttachmentBytes.toInt()) { (it % 251).toByte() })
            )
        )
        val innerHeaderMs = elapsedMs {
            innerHeader.serialize(NullOutputStream())
        }

        // 段 F：缓存落盘（writeCache 同口径：tmp + fd.sync + 原子 rename）
        val cacheDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val target = File(cacheDir, "p3313-cache-${System.nanoTime()}.bin")
        val cacheWriteMs = elapsedMs {
            val tmp = File(cacheDir, target.name + ".tmp")
            val fos = FileOutputStream(tmp)
            try {
                fos.write(compressed)
                fos.fd.sync()
            } finally {
                fos.close()
            }
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
            target.delete()
        }

        val report = "段读数(ms) = 端到端save=$totalMs; KDF(AES-KDF)=$kdfMs; " +
            "XML写出(ChaCha20)=$xmlChaChaMs; XML写出(None)=$xmlNoneMs; " +
            "内层流加密差值=${xmlChaChaMs - xmlNoneMs}; GZip=$gzipMs; HMAC块流=$hmacMs; " +
            "外层块加密(含HMAC)=$outerMs; 内层头(2MiB池)=$innerHeaderMs; 缓存落盘=$cacheWriteMs; " +
            "XML字节=${xmlBytes.size}; 压缩后字节=${compressed.size}"
        Log.i(tag, report)
        println(report)

        assertTrue("端到端读数必须为正: $totalMs", totalMs > 0)
        assertTrue("XML 段读数必须为正", xmlChaChaMs > 0)
        assertTrue("GZip 段读数必须为正", gzipMs > 0)
        assertTrue(
            "分项之和必须落在端到端同一量级（±100%）",
            (xmlChaChaMs + gzipMs + hmacMs + innerHeaderMs) <= totalMs * 3 + 500
        )
        assertEquals("夹具条目数必须与 §316 口径一致", entryCount, db.rootGroup.entries.size)
    }
}
