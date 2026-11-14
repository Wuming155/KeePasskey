package com.keepasskey.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import com.keepasskey.core.log.AppLog
import java.io.File
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 原生库损坏 → 回落原因可见（`ISSUE-P2` C4 关闭条件②）。
 *
 * JVM 用例已用可替换探针覆盖「so 缺失 / KAT 失败」两态，但探针是**模拟**；本用例在真机上
 * 用**真实打包的 `.so`** 走一遍损坏链路，钉死三件事：
 *
 * 1. 打包产物确实存在于测试 APK 内（本模块 `extractNativeLibs="false"`，`.so` 不解包到
 *    `nativeLibraryDir`，故从 APK 的 `lib/<abi>/` 条目读取真实字节）；
 * 2. 把该 `.so` 截断后 `System.load` **确实抛 `UnsatisfiedLinkError`**——这正是
 *    `NativeCryptoLibrary.loaded` 捕获并归一为 `false` 的异常类型；若平台改抛别的类型，
 *    回落闸门会失效而无人察觉；
 * 3. 回落登记在真实 `AppLog` 通道上输出 `reason=LIBRARY_UNAVAILABLE`，且**同一内核只一行**。
 *
 * 敏感数据：无。本用例不接触任何凭据，只读写库文件与日志文本。
 */
@RunWith(AndroidJUnit4::class)
class NativeLibraryCorruptionDeviceTest {

    private val lines = mutableListOf<String>()

    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private val libraryEntry: String by lazy {
        "lib/${Build.SUPPORTED_ABIS.first()}/lib${NativeCryptoLibrary.LIBRARY_NAME}.so"
    }

    /** 从测试 APK 读出真实打包的 `.so` 字节（前 64 字节足够做截断样本）。 */
    private fun packagedLibraryHead(): ByteArray {
        val apk = File(testContext.applicationInfo.sourceDir)
        assertTrue("测试 APK 必须存在：${apk.absolutePath}", apk.isFile)
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(libraryEntry)
            if (entry == null) {
                val present = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".so") }
                    .toList()
                fail("测试 APK 内缺少 $libraryEntry；实际 .so 条目=$present")
            }
            return zip.getInputStream(entry!!).use { it.readBytes() }.copyOf(64)
        }
    }

    @After
    fun tearDown() {
        AppLog.diagnosticLineSink = null
        NativeFallbackLog.resetForTest()
    }

    @Test
    fun `打包的原生库存在于测试 APK 内`() {
        val head = packagedLibraryHead()
        assertTrue("原生库前 64 字节不应全零（截断样本须有 ELF 头）", head.any { it != 0.toByte() })
        assertEquals("ELF 魔数须为 0x7F 'E' 'L' 'F'", ELF_MAGIC, head.copyOf(4).toList())
    }

    private companion object {
        val ELF_MAGIC = listOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    }

    @Test
    fun `截断的原生库加载时抛 UnsatisfiedLinkError`() {
        val corrupt = File(testContext.cacheDir, "corrupt-${System.nanoTime()}-libkp.so")
        try {
            // 只留 ELF 头：程序头表被截断 ⇒ dlopen 必然失败。
            // 用**不同路径**加载，避开 loadLibrary 按 soname 的幂等缓存。
            corrupt.writeBytes(packagedLibraryHead())
            System.load(corrupt.absolutePath)
            fail("截断的 .so 不应加载成功")
        } catch (_: UnsatisfiedLinkError) {
            // 预期：这正是 NativeCryptoLibrary.loaded 捕获并归一为 false 的异常类型
        } finally {
            corrupt.delete()
        }
    }

    @Test
    fun `库不可用时回落行带 LIBRARY_UNAVAILABLE 且同内核只一行`() {
        AppLog.diagnosticLineSink = { _, tag, message ->
            if (tag == "NativeFallback") lines += message
        }
        NativeFallbackLog.resetForTest()
        NativeFallbackLog.libraryLoadedProbe = { false }

        NativeFallbackLog.noteFallbackOnce("AES-CBC")
        NativeFallbackLog.noteFallbackOnce("AES-CBC")
        NativeFallbackLog.noteFallbackOnce("Argon2")

        assertEquals("同一内核只登记一次、不同内核各一行", 2, lines.size)
        assertTrue("库不可用须标 LIBRARY_UNAVAILABLE：${lines[0]}", lines[0].contains("reason=LIBRARY_UNAVAILABLE"))
        assertTrue("库不可用不查询原因码：${lines[0]}", !lines[0].contains("code="))
        assertTrue("不同内核各自登记：${lines[1]}", lines[1].contains("Argon2"))
    }

    @Test
    fun `库可用但调用失败时回落行带原因码`() {
        AppLog.diagnosticLineSink = { _, tag, message ->
            if (tag == "NativeFallback") lines += message
        }
        NativeFallbackLog.resetForTest()
        NativeFallbackLog.libraryLoadedProbe = { true }
        NativeFallbackLog.errorCodeProbe = { NativeErrorCode.INVALID_ARGUMENT }

        NativeFallbackLog.noteFallbackOnce("Twofish-CBC")

        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("reason=KAT_MISMATCH_OR_ERROR"))
        assertTrue("须带具体原因码：${lines[0]}", lines[0].contains("code=INVALID_ARGUMENT"))
    }
}
